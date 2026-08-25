package com.nspawnmgr.service;

import com.nspawnmgr.cli.ContainerCliExecutor;
import com.nspawnmgr.cli.ScriptRunResult;
import com.nspawnmgr.config.PamAuthProperties;
import com.nspawnmgr.crypto.SecretEncryptionService;
import com.nspawnmgr.domain.Container;
import com.nspawnmgr.domain.ContainerCredential;
import com.nspawnmgr.domain.ContainerOutboundAllowlistEntry;
import com.nspawnmgr.domain.ContainerPamService;
import com.nspawnmgr.domain.ContainerState;
import com.nspawnmgr.domain.CredentialType;
import com.nspawnmgr.domain.PackageManager;
import com.nspawnmgr.domain.PamAuthSource;
import com.nspawnmgr.domain.PamServiceCatalog;
import com.nspawnmgr.domain.PortMappingProtocol;
import com.nspawnmgr.guacamole.GuacamoleAdminClient;
import com.nspawnmgr.repository.ContainerCredentialRepository;
import com.nspawnmgr.repository.ContainerOutboundAllowlistRepository;
import com.nspawnmgr.repository.ContainerPamServiceRepository;
import com.nspawnmgr.repository.ContainerRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.net.URI;
import java.security.SecureRandom;
import java.time.Duration;
import java.util.Base64;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Owns the per-container "pam_nspawnmgr" replacement login check: which PAM services on a
 * container (see {@link ContainerPamService}/{@link PamServiceCatalog}) skip local
 * {@code /etc/shadow} entirely and instead verify a submitted password via this webapp's own
 * {@code /internal/pam-auth/verify} endpoint (see {@code PamAuthVerifyController}), checking
 * whatever source the owner configured ({@link Container#getPamAuthSource()}). Built to sidestep
 * a still-unexplained Fedora {@code unix_chkpwd}/{@code /etc/shadow} {@code EACCES} bug rather
 * than fix it at the kernel level: {@code default=die} in the injected PAM line means neither a
 * pass nor a fail ever falls through to the distro's own local-shadow check.
 */
@Service
public class PamCredentialAuthService {

    private static final Logger log = LoggerFactory.getLogger(PamCredentialAuthService.class);
    private static final Duration APPLY_TIMEOUT = Duration.ofSeconds(30);
    private static final String VERIFY_SCRIPT_PATH = "/usr/local/bin/nspawnmgr-pam-verify.sh";
    private static final String TOKEN_PATH = "/etc/nspawnmgr/pam-auth-token";
    private static final String MARKER_START = "# BEGIN nspawnmgr-pam-auth";
    private static final String MARKER_END = "# END nspawnmgr-pam-auth";
    private static final int RDP_PORT = 3389;

    private static final Map<PackageManager, String> ENSURE_CURL = Map.of(
            PackageManager.APT, "command -v curl >/dev/null 2>&1 || DEBIAN_FRONTEND=noninteractive apt-get install -y curl",
            PackageManager.DNF, "command -v curl >/dev/null 2>&1 || dnf install -y curl",
            PackageManager.APK, "command -v curl >/dev/null 2>&1 || apk add --no-cache curl",
            PackageManager.PACMAN, "command -v curl >/dev/null 2>&1 || pacman -S --noconfirm curl"
    );

    private final ContainerRepository containerRepository;
    private final ContainerPamServiceRepository pamServiceRepository;
    private final ContainerCredentialRepository credentialRepository;
    private final ContainerOutboundAllowlistRepository outboundAllowlistRepository;
    private final ContainerCliExecutor cliExecutor;
    private final GuacamoleAdminClient guacamoleAdminClient;
    private final SecretEncryptionService secretEncryptionService;
    private final PamAuthProperties pamAuthProperties;
    private final SecureRandom secureRandom = new SecureRandom();

    public PamCredentialAuthService(ContainerRepository containerRepository,
                                     ContainerPamServiceRepository pamServiceRepository,
                                     ContainerCredentialRepository credentialRepository,
                                     ContainerOutboundAllowlistRepository outboundAllowlistRepository,
                                     ContainerCliExecutor cliExecutor,
                                     GuacamoleAdminClient guacamoleAdminClient,
                                     SecretEncryptionService secretEncryptionService,
                                     PamAuthProperties pamAuthProperties) {
        this.containerRepository = containerRepository;
        this.pamServiceRepository = pamServiceRepository;
        this.credentialRepository = credentialRepository;
        this.outboundAllowlistRepository = outboundAllowlistRepository;
        this.cliExecutor = cliExecutor;
        this.guacamoleAdminClient = guacamoleAdminClient;
        this.secretEncryptionService = secretEncryptionService;
        this.pamAuthProperties = pamAuthProperties;
    }

    public List<ContainerPamService> listServices(Container container) {
        return pamServiceRepository.findByContainer(container);
    }

    /**
     * Owner-facing settings change: validates, persists, and — if the container is currently
     * RUNNING — re-applies live so the change takes effect without a restart. If the container
     * isn't running, the new config is picked up the next time it reaches RUNNING (see {@link
     * #reapplyIfConfigured}, called from ContainerReadinessPollingService).
     */
    @Transactional
    public void updateSettings(Container container, PamAuthSource source, Set<String> serviceNames) {
        Set<String> unknown = serviceNames.stream()
                .filter(name -> !PamServiceCatalog.KNOWN_SERVICES.contains(name))
                .collect(Collectors.toSet());
        if (!unknown.isEmpty()) {
            throw new IllegalArgumentException("Unknown PAM service name(s): " + unknown);
        }
        requireSourceCredential(container, source);

        container.setPamAuthSource(source);
        container.touch();
        containerRepository.save(container);

        pamServiceRepository.deleteByContainer(container);
        // Flushed immediately, not left pending until the transaction's own commit - confirmed
        // live (fed2, 2026-08-13): Hibernate's default flush order runs every pending INSERT
        // before any pending DELETE regardless of the order they were called in, so re-saving a
        // service name that was already configured (the common case - the checkbox was already
        // checked) tried to insert the new row before the old one was actually gone, hitting
        // uq_container_pam_services. Forcing the delete to execute now avoids the two ever
        // colliding in the same flush.
        pamServiceRepository.flush();
        for (String serviceName : serviceNames) {
            pamServiceRepository.save(new ContainerPamService(container, serviceName));
        }

        String hostname = cliExecutor.getInternalAddress(container.getName(), container.getBackend());
        if (!hostname.isEmpty()) {
            reconcileRdpConnectionCredentials(container, hostname);
        }

        if (container.getState() == ContainerState.RUNNING) {
            applyToContainer(container);
        }
    }

    /**
     * Guacamole's own RDP connection (see {@code ProvisioningService.provisionRdp}) is created
     * once, at provisioning time, with a *fixed* username/password baked in - it has no interactive
     * login form for a real-credential connection, so it always auto-submits whatever it was last
     * told to. That's independent of this feature's own {@code pamAuthSource} setting, so once an
     * owner switches the RDP check away from the default {@code RDP_PASSWORD}, nspawnmgr's own
     * "Connect" button kept submitting the RDP_PASSWORD credential straight into a check that no
     * longer accepts it - RDP through nspawnmgr's UI simply stopped working, confirmed live
     * (fed1, 2026-08-14). Keeps the connection's own submitted credential in lockstep with
     * whatever {@code xrdp-sesman} is actually configured to check right now: the matching stored
     * credential for {@code RDP_PASSWORD}/{@code VNC_PASSWORD}, or - for
     * {@code NSPAWNMGR_AUTH_BACKEND}, where there's no single fixed secret that could ever match an
     * arbitrary org credential - switches the connection to prompt-credentials mode instead, same
     * as an external host's connection, so Guacamole shows a real login form. A no-op whenever
     * {@code xrdp-sesman} isn't one of the checked services, or the container has no RDP connection
     * at all - not a per-service-selection change, this is specifically the interaction between the
     * check this feature runs and the one connection that can never provide non-default credentials
     * to satisfy it.
     *
     * <p>Public (not just called from {@link #updateSettings}) because
     * {@code ContainerReadinessPollingService} also re-syncs the RDP connection's hostname on every
     * boot/restart - it used to do that with its own hardcoded {@code RDP_PASSWORD}-only logic,
     * which would have silently undone this reconciliation the next time the container restarted.
     * Callers own resolving a real {@code hostname} first ({@code ""} isn't handled here) since
     * "no address yet" means different things to each caller (skip vs. retry next poll tick).
     */
    public void reconcileRdpConnectionCredentials(Container container, String hostname) {
        if (container.getGuacRdpConnectionId() == null) {
            return;
        }
        Set<String> serviceNames = pamServiceRepository.findByContainer(container).stream()
                .map(ContainerPamService::getServiceName)
                .collect(Collectors.toSet());
        // xrdp-sesman not checked (or being turned off) - PAM source is irrelevant to RDP, so the
        // connection must go back to the one credential that's always actually correct: whatever
        // provisionRdp's own Linux account password already is.
        PamAuthSource effectiveSource = serviceNames.contains(PamServiceCatalog.XRDP_SESMAN)
                ? container.getPamAuthSource() : PamAuthSource.RDP_PASSWORD;
        String connectionName = container.getName() + "-rdp";
        String security = container.getRdpSecurity().guacamoleValue();
        switch (effectiveSource) {
            case RDP_PASSWORD -> credentialRepository.findByContainerAndType(container, CredentialType.RDP_PASSWORD)
                    .ifPresent(cred -> guacamoleAdminClient.updateRdpConnection(container.getGuacRdpConnectionId(),
                            connectionName, hostname, RDP_PORT, cred.getAccountName(), decrypt(cred), security));
            case VNC_PASSWORD -> credentialRepository.findByContainerAndType(container, CredentialType.VNC_PASSWORD)
                    .ifPresent(cred -> guacamoleAdminClient.updateRdpConnection(container.getGuacRdpConnectionId(),
                            connectionName, hostname, RDP_PORT, cred.getAccountName(), decrypt(cred), security));
            case NSPAWNMGR_AUTH_BACKEND -> guacamoleAdminClient.updateRdpConnectionPromptCredentials(
                    container.getGuacRdpConnectionId(), connectionName, hostname, RDP_PORT, security);
        }
    }

    private String decrypt(ContainerCredential credential) {
        return secretEncryptionService.decrypt(credential.getSecretCiphertext(), credential.getIv());
    }

    /**
     * Default-enables this container's replacement check for {@code xrdp-sesman} only, checking
     * the RDP credential just provisioned — called right after {@code
     * ProvisioningService.provisionRdp} saves it, only for Fedora-family containers (see that
     * call site's own comment on why {@code PackageManager.DNF} is used as the "is this Fedora"
     * proxy).
     *
     * <p>A no-op if any PAM service is already configured for this container — confirmed live
     * (fed1, 2026-08-13): the container row exists and is editable in the UI as soon as it's
     * created, well before background provisioning reaches this call, so an owner saving their
     * own choice via {@link #updateSettings} first (even a value-identical one) is a real race,
     * not a hypothetical one. Provisioning's own default must defer to whatever's already there
     * rather than blindly inserting and colliding with it.
     */
    @Transactional
    public void enableDefaultForFedoraRdp(Container container) {
        if (!pamServiceRepository.findByContainer(container).isEmpty()) {
            return;
        }
        container.setPamAuthSource(PamAuthSource.RDP_PASSWORD);
        container.touch();
        containerRepository.save(container);
        pamServiceRepository.save(new ContainerPamService(container, PamServiceCatalog.XRDP_SESMAN));
        applyToContainer(container);
    }

    /**
     * Re-applies whatever is currently configured — called once a container reaches RUNNING, in
     * case its settings changed (or were set for the first time, at provisioning) while it was
     * stopped. A no-op when nothing is configured.
     */
    public void reapplyIfConfigured(Container container) {
        if (!pamServiceRepository.findByContainer(container).isEmpty()) {
            applyToContainer(container);
        }
    }

    private void requireSourceCredential(Container container, PamAuthSource source) {
        CredentialType required = switch (source) {
            case RDP_PASSWORD -> CredentialType.RDP_PASSWORD;
            case VNC_PASSWORD -> CredentialType.VNC_PASSWORD;
            case NSPAWNMGR_AUTH_BACKEND -> null;
        };
        if (required != null && credentialRepository.findByContainerAndType(container, required).isEmpty()) {
            throw new IllegalStateException(
                    "Can't check against " + required + " — " + container.getName() + " has no such credential yet");
        }
    }

    private void applyToContainer(Container container) {
        if (container.getPamAuthToken() == null) {
            container.setPamAuthToken(generateToken());
            container.touch();
            containerRepository.save(container);
        }
        Set<String> enabled = pamServiceRepository.findByContainer(container).stream()
                .map(ContainerPamService::getServiceName)
                .collect(Collectors.toCollection(LinkedHashSet::new));

        // container.getTemplate() may be a lazy proxy tied to whatever session originally loaded it
        // (open-in-view is off) - buildApplyScript reads it now (effectivePackageManager), so a
        // plain lazy proxy from outside this method's own transaction throws
        // LazyInitializationException - confirmed live (fed1, 2026-08-13). Same fix
        // ContainerPortMappingService.rewriteSettings/ProvisioningService.ensureVncServerRunning
        // already needed for the identical reason.
        Container withTemplate = containerRepository.findByIdWithTemplate(container.getId())
                .orElseThrow(() -> new IllegalArgumentException("No such container: " + container.getId()));

        String script = buildApplyScript(withTemplate, enabled);
        ScriptRunResult result = cliExecutor.runScript(container.getName(), container.getBackend(), script, APPLY_TIMEOUT);
        if (!result.success()) {
            log.warn("Failed to apply pam_nspawnmgr settings on container {} (exit {})",
                    container.getName(), result.exitCode());
        }

        if (!enabled.isEmpty()) {
            ensureOutboundAllowlistEntry(container);
        }
    }

    private String buildApplyScript(Container container, Set<String> enabledServices) {
        String verifyUrl = pamAuthProperties.callbackBaseUrl() + "/internal/pam-auth/verify";
        StringBuilder script = new StringBuilder();
        script.append("set -e\n");
        PackageManager packageManager = container.effectivePackageManager();
        script.append(packageManager != null ? ENSURE_CURL.getOrDefault(packageManager, "true") : "true").append('\n');
        script.append("mkdir -p /etc/nspawnmgr\n");
        script.append("umask 077\n");
        script.append("cat > ").append(TOKEN_PATH).append(" <<'NSPAWNMGR_TOKEN'\n")
                .append(container.getPamAuthToken()).append('\n')
                .append("NSPAWNMGR_TOKEN\n");
        script.append("chmod 600 ").append(TOKEN_PATH).append('\n');
        script.append("cat > ").append(VERIFY_SCRIPT_PATH).append(" <<'NSPAWNMGR_SCRIPT'\n")
                .append(verifyScriptBody(verifyUrl))
                .append("NSPAWNMGR_SCRIPT\n");
        script.append("chmod 700 ").append(VERIFY_SCRIPT_PATH).append('\n');

        for (String serviceName : PamServiceCatalog.KNOWN_SERVICES) {
            script.append(pamFileUpdateSnippet(serviceName, enabledServices.contains(serviceName)));
        }
        return script.toString();
    }

    private String verifyScriptBody(String verifyUrl) {
        return """
                #!/bin/sh
                read -r nspawnmgr_password
                nspawnmgr_token=$(cat %s 2>/dev/null) || exit 1
                [ -n "$nspawnmgr_token" ] || exit 1
                nspawnmgr_response=$(curl -s -m 10 --fail \\
                    --data-urlencode "token=$nspawnmgr_token" \\
                    --data-urlencode "username=$PAM_USER" \\
                    --data-urlencode "password=$nspawnmgr_password" \\
                    %s) || exit 1
                [ "$nspawnmgr_response" = "allowed" ] && exit 0
                exit 1
                """.formatted(TOKEN_PATH, verifyUrl);
    }

    /**
     * Idempotent either way: strips any previously-inserted block first, then (if {@code
     * enabled}) re-adds it at the very top of the {@code auth} stack, ahead of the distro's own
     * {@code include}/{@code @include} line — {@code default=die} means neither a pass nor a
     * fail ever falls through to whatever that include pulls in.
     *
     * <p>Also overrides the {@code account} stack with an unconditional {@code pam_permit.so},
     * for the same reason (and via the same fix already used for Fedora's {@code sshd} in the
     * bake script — see the "Fedora PAM/SSH broken" memory): the distro's own {@code
     * pam_unix.so account} still reads {@code /etc/shadow} via the same broken helper as the
     * {@code auth} phase, and hits the identical {@code EACCES} regardless of which user or
     * whether the password check even passed - confirmed live on fed1 (2026-08-13): {@code
     * pam_acct_mgmt failed: Authentication service cannot retrieve authentication info} appeared
     * for both the pre-existing {@code admin} account and a freshly-created {@code ward} account
     * alike, and "User is not authorized" is xrdp's own client-facing wording for that same
     * account-phase failure - nothing to do with a wrong password or an unauthorized identity.
     * Bypassing just the {@code auth} phase (as originally shipped) was never enough on its own.
     */
    private String pamFileUpdateSnippet(String serviceName, boolean enabled) {
        String file = "/etc/pam.d/" + serviceName;
        StringBuilder snippet = new StringBuilder();
        snippet.append("if [ -f '").append(file).append("' ]; then\n");
        snippet.append("  sed -i '/^").append(MARKER_START).append("$/,/^").append(MARKER_END).append("$/d' '")
                .append(file).append("'\n");
        if (enabled) {
            snippet.append("  { printf '%s\\n' '").append(MARKER_START).append("'; ")
                    .append("printf '%s\\n' 'auth [success=done default=die] pam_exec.so expose_authtok quiet ")
                    .append(VERIFY_SCRIPT_PATH).append("'; ")
                    .append("printf '%s\\n' 'account [success=done default=die] pam_permit.so'; ")
                    .append("printf '%s\\n' '").append(MARKER_END).append("'; ")
                    .append("cat '").append(file).append("'; } > '").append(file).append(".nspawnmgr.tmp' && mv '")
                    .append(file).append(".nspawnmgr.tmp' '").append(file).append("'\n");
        }
        snippet.append("fi\n");
        return snippet.toString();
    }

    private void ensureOutboundAllowlistEntry(Container container) {
        URI uri = URI.create(pamAuthProperties.callbackBaseUrl());
        String host = uri.getHost();
        int port = uri.getPort() > 0 ? uri.getPort() : ("https".equals(uri.getScheme()) ? 443 : 80);
        boolean exists = outboundAllowlistRepository.findByContainer(container).stream()
                .anyMatch(entry -> entry.getDestinationHost().equals(host) && entry.getDestinationPort() == port
                        && entry.getProtocol() == PortMappingProtocol.TCP);
        if (!exists) {
            outboundAllowlistRepository.save(new ContainerOutboundAllowlistEntry(container, host, port, PortMappingProtocol.TCP));
        }
    }

    private String generateToken() {
        byte[] bytes = new byte[32];
        secureRandom.nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }
}
