package com.nspawnmgr.web;

import com.nspawnmgr.crypto.SecretEncryptionService;
import com.nspawnmgr.domain.Container;
import com.nspawnmgr.domain.ContainerCredential;
import com.nspawnmgr.domain.CredentialType;
import com.nspawnmgr.domain.PamAuthSource;
import com.nspawnmgr.domain.User;
import com.nspawnmgr.repository.ContainerCredentialRepository;
import com.nspawnmgr.repository.ContainerRepository;
import com.nspawnmgr.repository.ContainerShareRepository;
import com.nspawnmgr.repository.UserRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestTemplate;
import org.springframework.web.util.UriComponentsBuilder;

import javax.servlet.http.HttpServletRequest;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Called by a container's own {@code /usr/local/bin/nspawnmgr-pam-verify.sh} (see
 * PamCredentialAuthService) to check a submitted RDP/SSH/etc. login password — the whole point of
 * {@code pam_nspawnmgr}, bypassing local {@code /etc/shadow}. Deliberately unauthenticated at the
 * Spring Security layer (see SecurityConfig's {@code /internal/pam-auth/**} permitAll — a
 * container has no nspawnmgr login cookie); this endpoint authenticates the caller itself, via the
 * per-container bearer token minted in {@code Container.pamAuthToken}. A bare {@code text/plain}
 * {@code allowed}/{@code denied} response, not JSON — the caller is a POSIX shell script with no
 * JSON parser available.
 *
 * <p>This is a password oracle reachable from every opted-in container, so unlike most endpoints
 * in this app it needs its own throttling independent of the normal user-session auth — see
 * {@link #failuresByToken}.
 */
@RestController
public class PamAuthVerifyController {

    private static final Logger log = LoggerFactory.getLogger(PamAuthVerifyController.class);
    private static final int MAX_FAILURES = 5;
    private static final Duration LOCKOUT = Duration.ofSeconds(30);
    private static final Duration AUTH_BACKEND_TIMEOUT = Duration.ofSeconds(10);

    private final ContainerRepository containerRepository;
    private final ContainerCredentialRepository credentialRepository;
    private final UserRepository userRepository;
    private final ContainerShareRepository containerShareRepository;
    private final SecretEncryptionService secretEncryptionService;
    private final RestTemplate restTemplate = new RestTemplate();
    private final ConcurrentHashMap<String, FailureTracker> failuresByToken = new ConcurrentHashMap<>();

    public PamAuthVerifyController(ContainerRepository containerRepository,
                                    ContainerCredentialRepository credentialRepository,
                                    UserRepository userRepository,
                                    ContainerShareRepository containerShareRepository,
                                    SecretEncryptionService secretEncryptionService) {
        this.containerRepository = containerRepository;
        this.credentialRepository = credentialRepository;
        this.userRepository = userRepository;
        this.containerShareRepository = containerShareRepository;
        this.secretEncryptionService = secretEncryptionService;
    }

    @PostMapping("/internal/pam-auth/verify")
    public ResponseEntity<String> verify(@RequestParam String token, @RequestParam String username,
                                          @RequestParam String password, HttpServletRequest request) {
        if (isLockedOut(token)) {
            return denied();
        }
        Optional<Container> container = containerRepository.findByPamAuthToken(token);
        if (container.isEmpty()) {
            recordFailure(token);
            return denied();
        }
        boolean allowed = checkPassword(container.get(), username, password, request);
        if (allowed) {
            failuresByToken.remove(token);
            return allowed();
        }
        recordFailure(token);
        return denied();
    }

    private boolean checkPassword(Container container, String username, String password, HttpServletRequest request) {
        PamAuthSource source = container.getPamAuthSource();
        return switch (source) {
            case RDP_PASSWORD -> checkStoredCredential(container, CredentialType.RDP_PASSWORD, username, password);
            case VNC_PASSWORD -> checkStoredCredential(container, CredentialType.VNC_PASSWORD, username, password);
            case NSPAWNMGR_AUTH_BACKEND -> checkAuthBackend(container, username, password, request);
        };
    }

    private boolean checkStoredCredential(Container container, CredentialType type, String username, String password) {
        Optional<ContainerCredential> credential = credentialRepository.findByContainerAndType(container, type);
        if (credential.isEmpty()) {
            return false;
        }
        String decrypted = secretEncryptionService.decrypt(credential.get().getSecretCiphertext(), credential.get().getIv());
        return constantTimeEquals(username, credential.get().getAccountName()) && constantTimeEquals(password, decrypted);
    }

    /**
     * Relays to auth.war's own {@code /verify} endpoint over loopback, same Tomcat instance —
     * derives the port from this very request rather than a separate config value, since auth.war
     * is always co-located on the same connector nspawnmgr.war itself was just reached on.
     *
     * <p>A valid org credential alone is deliberately not sufficient here — confirmed live
     * (fed1, 2026-08-13): once a real local account existed for the typed username, this path let
     * that person straight into the container's OS-level login over its exposed RDP port, even
     * though nspawnmgr's own owner never granted them access and the login never touches
     * nspawnmgr's web app, Guacamole SSO, or its audit log at all. {@link #isAuthorizedFor} closes
     * that: the authenticated identity must resolve to an nspawnmgr {@link User} who is either the
     * container's owner or holds an existing {@link com.nspawnmgr.domain.ContainerShare} grant
     * (the same ledger {@link com.nspawnmgr.service.ShareService#grantAccess} maintains) before the
     * credential check is allowed to succeed.
     */
    private boolean checkAuthBackend(Container container, String username, String password, HttpServletRequest request) {
        String url = UriComponentsBuilder.newInstance()
                .scheme("http").host("localhost").port(request.getServerPort())
                .path("/auth/verify")
                .toUriString();
        try {
            MultiValueMap<String, String> body = new LinkedMultiValueMap<>();
            body.add("username", username);
            body.add("password", password);
            HttpHeaders headers = new HttpHeaders();
            headers.setContentType(MediaType.APPLICATION_FORM_URLENCODED);
            String result = restTemplate.postForObject(url, new HttpEntity<>(body, headers), String.class);
            // Deliberately logs username and password *length* only, never the password itself -
            // added purely to debug a live report that a genuinely correct nspawnmgr login
            // password was rejected via this path (2026-08-13) - the exception-only logging below
            // gave zero visibility into a same-request "auth.war said no" outcome, which is
            // silent otherwise.
            log.info("pam_nspawnmgr auth-backend check for user '{}' (password length {}): auth.war returned '{}'",
                    username, password.length(), result);
            return "SUCCESS".equals(result) && isAuthorizedFor(container, username);
        } catch (RestClientException e) {
            log.warn("auth.war /verify call failed for user '{}': {}", username, e.getMessage());
            return false;
        }
    }

    // Package-private (not private) purely so PamAuthVerifyControllerTest can exercise this
    // authorization logic directly, without needing to mock the raw RestTemplate call
    // checkAuthBackend makes to auth.war.
    boolean isAuthorizedFor(Container container, String username) {
        Optional<User> user = userRepository.findByUsernameIgnoreCase(username);
        if (user.isEmpty()) {
            log.warn("pam_nspawnmgr: '{}' authenticated against the org backend but has no nspawnmgr "
                    + "account, so no access grant on container '{}' can exist - denying", username, container.getName());
            return false;
        }
        // getOwner() may be an uninitialized lazy proxy here (this method runs outside the
        // transaction that loaded `container`) - only its id is read, which Hibernate resolves
        // from the proxy itself without a fresh query, so this doesn't need a re-fetch-with-join
        // the way touching any other field on a lazy association would.
        boolean isOwner = container.getOwner() != null && container.getOwner().getId().equals(user.get().getId());
        boolean isShared = containerShareRepository.existsByContainerAndUser(container, user.get());
        if (!isOwner && !isShared) {
            log.warn("pam_nspawnmgr: '{}' authenticated against the org backend but has no access grant "
                    + "on container '{}' (not owner, no share) - denying", username, container.getName());
            return false;
        }
        return true;
    }

    private boolean constantTimeEquals(String a, String b) {
        if (a == null || b == null) {
            return false;
        }
        byte[] aBytes = a.getBytes(StandardCharsets.UTF_8);
        byte[] bBytes = b.getBytes(StandardCharsets.UTF_8);
        int diff = aBytes.length ^ bBytes.length;
        for (int i = 0; i < Math.max(aBytes.length, bBytes.length); i++) {
            byte x = i < aBytes.length ? aBytes[i] : 0;
            byte y = i < bBytes.length ? bBytes[i] : 0;
            diff |= x ^ y;
        }
        return diff == 0;
    }

    private boolean isLockedOut(String token) {
        FailureTracker tracker = failuresByToken.get(token);
        return tracker != null && tracker.count.get() >= MAX_FAILURES
                && tracker.lastFailure.plus(LOCKOUT).isAfter(Instant.now());
    }

    private void recordFailure(String token) {
        FailureTracker tracker = failuresByToken.computeIfAbsent(token, t -> new FailureTracker());
        tracker.count.incrementAndGet();
        tracker.lastFailure = Instant.now();
    }

    private ResponseEntity<String> allowed() {
        return ResponseEntity.ok().contentType(MediaType.TEXT_PLAIN).body("allowed");
    }

    private ResponseEntity<String> denied() {
        return ResponseEntity.ok().contentType(MediaType.TEXT_PLAIN).body("denied");
    }

    private static final class FailureTracker {
        private final AtomicInteger count = new AtomicInteger();
        private volatile Instant lastFailure = Instant.now();
    }
}
