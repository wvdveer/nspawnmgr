package com.nspawnmgr.service;

import com.nspawnmgr.crypto.EncryptedValue;
import com.nspawnmgr.crypto.SecretEncryptionService;
import com.nspawnmgr.domain.Container;
import com.nspawnmgr.domain.ContainerShare;
import com.nspawnmgr.domain.GuacamoleUserSecret;
import com.nspawnmgr.domain.User;
import com.nspawnmgr.guacamole.GuacamoleAdminClient;
import com.nspawnmgr.repository.ContainerShareRepository;
import com.nspawnmgr.repository.GuacamoleUserSecretRepository;
import com.nspawnmgr.repository.UserRepository;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.security.SecureRandom;
import java.util.Base64;
import java.util.List;

/**
 * Sharing. Every container is visible to every user; this only manages Guacamole connection
 * permissions - grants are lazy, created the first time a user is actually granted/connects, and
 * ContainerShare doubles as the ledger of who currently holds a live Guacamole grant so it can be
 * revoked in one place (e.g. when a container is deleted).
 */
@Service
public class ShareService {

    private final ContainerShareRepository containerShareRepository;
    private final UserRepository userRepository;
    private final GuacamoleUserSecretRepository guacamoleUserSecretRepository;
    private final GuacamoleAdminClient guacamoleAdminClient;
    private final SecretEncryptionService secretEncryptionService;
    private final GuacamoleUserSecretWriter guacamoleUserSecretWriter;
    private final SecureRandom secureRandom = new SecureRandom();

    public ShareService(ContainerShareRepository containerShareRepository,
                         UserRepository userRepository, GuacamoleUserSecretRepository guacamoleUserSecretRepository,
                         GuacamoleAdminClient guacamoleAdminClient, SecretEncryptionService secretEncryptionService,
                         GuacamoleUserSecretWriter guacamoleUserSecretWriter) {
        this.containerShareRepository = containerShareRepository;
        this.userRepository = userRepository;
        this.guacamoleUserSecretRepository = guacamoleUserSecretRepository;
        this.guacamoleAdminClient = guacamoleAdminClient;
        this.secretEncryptionService = secretEncryptionService;
        this.guacamoleUserSecretWriter = guacamoleUserSecretWriter;
    }

    /**
     * Always re-attempts the connection-permission grants, even when a {@link ContainerShare} row
     * already exists for this (container, user) pair - confirmed live: a user who already shared a
     * container's SSH access (creating that row) never received a later VNC grant on their first
     * VNC connect, because the old early-return treated "share row exists" as "every currently
     * relevant permission is already granted", which stops being true the moment a new access type
     * (RDP, VNC, ...) gets enabled on a container after the row was created. Guacamole's own
     * permission grant is a JSON-Patch "add", idempotent against an already-held permission, so
     * re-running it every call costs a few cheap local HTTP round-trips, not correctness. Only the
     * {@code ContainerShare} bookkeeping row insert stays skip-if-exists.
     *
     * @return the resolved Guacamole username - {@link #ensureGuacamoleUser} deliberately mutates a
     * separately re-fetched, row-locked {@code User} copy rather than {@code user} itself (see its
     * own javadoc on the race this avoids), so a brand-new account's name never lands back on the
     * caller's own {@code user} reference - confirmed live: {@code ContainerSessionService#start}
     * discarding this return value read {@code user.getGuacamoleUsername()} straight back off that
     * still-null reference immediately afterward, NPEing inside
     * {@code ConcurrentHashMap.compute(null, ...)} for every first-ever session a user started.
     */
    @Transactional
    public String grantAccess(Container container, User user) {
        String guacUsername = ensureGuacamoleUser(user);
        if (container.getGuacSshConnectionId() != null) {
            guacamoleAdminClient.grantConnectionPermission(guacUsername, container.getGuacSshConnectionId());
        }
        if (container.isRdpEnabled() && container.getGuacRdpConnectionId() != null) {
            guacamoleAdminClient.grantConnectionPermission(guacUsername, container.getGuacRdpConnectionId());
        }
        if (container.isVncEnabled() && container.getGuacVncConnectionId() != null) {
            guacamoleAdminClient.grantConnectionPermission(guacUsername, container.getGuacVncConnectionId());
        }
        if (!containerShareRepository.existsByContainerAndUser(container, user)) {
            containerShareRepository.save(new ContainerShare(container, user));
        }
        return guacUsername;
    }

    @Transactional
    public void revokeAccess(Container container, User user) {
        if (user.getGuacamoleUsername() != null) {
            if (container.getGuacSshConnectionId() != null) {
                guacamoleAdminClient.revokeConnectionPermission(user.getGuacamoleUsername(), container.getGuacSshConnectionId());
            }
            if (container.getGuacRdpConnectionId() != null) {
                guacamoleAdminClient.revokeConnectionPermission(user.getGuacamoleUsername(), container.getGuacRdpConnectionId());
            }
            if (container.getGuacVncConnectionId() != null) {
                guacamoleAdminClient.revokeConnectionPermission(user.getGuacamoleUsername(), container.getGuacVncConnectionId());
            }
        }
        containerShareRepository.deleteByContainerAndUser(container, user);
    }

    @Transactional
    public void revokeAllForContainer(Container container) {
        for (ContainerShare share : containerShareRepository.findByContainer(container)) {
            revokeAccess(container, share.getUser());
        }
    }

    public List<ContainerShare> listShares(Container container) {
        return containerShareRepository.findByContainer(container);
    }

    /** Decrypted password nspawnmgr uses to log in as this user's Guacamole account for SSO. */
    @Transactional(readOnly = true)
    public String guacamolePassword(User user) {
        GuacamoleUserSecret secret = guacamoleUserSecretRepository.findById(user.getId())
                .orElseThrow(() -> new IllegalStateException("No Guacamole credentials provisioned for user " + user.getId()));
        return secretEncryptionService.decrypt(secret.getPasswordCiphertext(), secret.getIv());
    }

    /**
     * Self-service password set/reset — creates the Guacamole account first if the user doesn't
     * have one yet (using their chosen password as the initial one, unlike {@link #ensureGuacamoleUser}'s
     * random one), or resets an existing account's password otherwise. Either way, keeps nspawnmgr's
     * own encrypted copy in sync, since {@link #guacamolePassword} depends on it matching what
     * Guacamole actually has for the SSO login in {@code ContainerSessionService} to keep working.
     */
    @Transactional
    public void setGuacamolePassword(User user, String newPassword) {
        EncryptedValue encrypted = secretEncryptionService.encrypt(newPassword);
        if (user.getGuacamoleUsername() == null) {
            String guacUsername = desiredGuacUsername(user);
            guacamoleAdminClient.createOrGetUser(guacUsername, newPassword);
            User managedUser = userRepository.getReferenceById(user.getId());
            guacamoleUserSecretRepository.save(new GuacamoleUserSecret(managedUser, encrypted.ciphertextBase64(), encrypted.ivBase64()));
            user.setGuacamoleUsername(guacUsername);
            userRepository.save(user);
        } else {
            guacamoleAdminClient.updateUserPassword(user.getGuacamoleUsername(), newPassword);
            GuacamoleUserSecret secret = guacamoleUserSecretRepository.findById(user.getId())
                    .orElseThrow(() -> new IllegalStateException(
                            "No Guacamole credentials row for user " + user.getId() + " despite a guacamoleUsername already being set"));
            // No explicit save() here: GuacamoleUserSecret.isNew() is hardcoded true (see its own
            // javadoc), so save() would attempt a duplicate-PK insert on an existing row. Mutating
            // this managed entity's setters inside this @Transactional method lets JPA's own dirty
            // checking flush the UPDATE at commit instead.
            secret.setPasswordCiphertext(encrypted.ciphertextBase64());
            secret.setIv(encrypted.ivBase64());
        }
    }

    /**
     * Row-locks the user (see UserRepository#findByIdForUpdate) rather than trusting the passed-in
     * `user` reference's own guacamoleUsername field - confirmed live, two containers created
     * together for the same owner both raced past a plain in-memory null-check here and both tried
     * to create a Guacamole account + insert a guacamole_user_secrets row for that owner, the loser
     * failing on the secrets table's primary-key constraint. Worse than just a loud failure: since
     * the Guacamole-account-creation race and the DB-insert race aren't the same race (nothing
     * stops one thread's HTTP call and the other thread's DB insert from each "winning"
     * independently), a plain catch-and-swallow on the constraint violation could silently leave
     * nspawnmgr's stored password out of sync with whatever password Guacamole actually has. The
     * pessimistic lock closes both races at once: a second transaction requesting this same user
     * row blocks at the database level until the first commits, then reads that already-committed
     * guacamoleUsername/secret instead of racing to create a second one.
     */
    private String ensureGuacamoleUser(User user) {
        User locked = userRepository.findByIdForUpdate(user.getId())
                .orElseThrow(() -> new IllegalArgumentException("No such user: " + user.getId()));
        if (locked.getGuacamoleUsername() != null) {
            return locked.getGuacamoleUsername();
        }
        String guacUsername = desiredGuacUsername(locked);
        String password = generatePassword();
        guacamoleAdminClient.createOrGetUser(guacUsername, password);
        EncryptedValue encrypted = secretEncryptionService.encrypt(password);
        guacamoleUserSecretRepository.save(new GuacamoleUserSecret(locked, encrypted.ciphertextBase64(), encrypted.ivBase64()));
        locked.setGuacamoleUsername(guacUsername);
        userRepository.save(locked);
        return guacUsername;
    }

    /**
     * Recovers from a stale Guacamole account link: nspawnmgr's own stored {@code guacamoleUsername}
     * / encrypted password no longer gets this user logged into Guacamole (confirmed live -
     * {@code guacamole_user_secrets} and Guacamole's own separate database can drift out of sync,
     * e.g. Guacamole's database got reprovisioned independently of nspawnmgr's own during this
     * project's own self-hosted-install troubleshooting), so every SSO session-start attempt fails
     * with a 403 "Invalid login" that {@link com.nspawnmgr.service.ContainerSessionService} has no
     * way to tell apart from a genuine credential problem on its own. Regenerates a fresh password
     * and pushes it to Guacamole via the admin API - {@link GuacamoleAdminClient#createOrGetUser}
     * covers "the account doesn't exist in Guacamole at all" (creates it with the new password);
     * {@link GuacamoleAdminClient#updateUserPassword} unconditionally afterward covers "it exists but
     * with a different password than nspawnmgr's own stored copy" - then re-encrypts the stored copy
     * so a caller's own immediate retry (using the returned plaintext) succeeds. Row-locks the user
     * for the same race-safety reason {@link #ensureGuacamoleUser} does.
     *
     * <p>Unlike {@code ensureGuacamoleUser}, that user-row lock alone isn't enough to safely decide
     * insert-vs-update for the {@code GuacamoleUserSecret} row here - confirmed live (arch-kde,
     * 2026-08-14): a plain {@code findById} occasionally still reported "not present" for a row
     * that genuinely already existed, so the old insert-when-absent branch hit
     * {@code guacamole_user_secrets_pkey} instead. Delegates to {@link GuacamoleUserSecretWriter}
     * (see its own javadoc for why this can't just be a try/catch inline here) - try the insert
     * first, and only fall back to an explicit update on a genuine conflict, rather than trusting a
     * preceding existence check.
     */
    @Transactional
    public String resetGuacamoleAccount(User user) {
        User locked = userRepository.findByIdForUpdate(user.getId())
                .orElseThrow(() -> new IllegalArgumentException("No such user: " + user.getId()));
        String guacUsername = locked.getGuacamoleUsername() != null ? locked.getGuacamoleUsername() : desiredGuacUsername(locked);
        String password = generatePassword();
        guacamoleAdminClient.createOrGetUser(guacUsername, password);
        guacamoleAdminClient.updateUserPassword(guacUsername, password);
        EncryptedValue encrypted = secretEncryptionService.encrypt(password);
        try {
            guacamoleUserSecretWriter.insert(locked, encrypted.ciphertextBase64(), encrypted.ivBase64());
        } catch (DataIntegrityViolationException e) {
            guacamoleUserSecretWriter.update(locked.getId(), encrypted.ciphertextBase64(), encrypted.ivBase64());
        }
        if (!guacUsername.equals(locked.getGuacamoleUsername())) {
            locked.setGuacamoleUsername(guacUsername);
            userRepository.save(locked);
        }
        return password;
    }

    /**
     * Keeps the Guacamole account's username in sync with nspawnmgr's own username. Picked up
     * lazily on the next "Connect" click rather than eagerly on every request: nspawnmgr's own
     * username is itself re-synced from the external identity on every login (see
     * UserService.upsert), and Guacamole's REST API has no rename operation — the username is the
     * row's primary key — so a rename here means create-under-new-name, re-grant whatever
     * connections {@link ContainerShare} says this user currently holds, then delete the old
     * account. No-op if the user has no Guacamole account yet (ensureGuacamoleUser already creates
     * it under the right name) or the name already matches — the common case, so this stays cheap.
     */
    @Transactional
    public void syncGuacamoleUsername(User user) {
        String desired = desiredGuacUsername(user);
        String current = user.getGuacamoleUsername();
        if (current == null || current.equals(desired)) {
            return;
        }
        String password = guacamolePassword(user);
        guacamoleAdminClient.createOrGetUser(desired, password);
        for (ContainerShare share : containerShareRepository.findByUser(user)) {
            Container container = share.getContainer();
            if (container.getGuacSshConnectionId() != null) {
                guacamoleAdminClient.grantConnectionPermission(desired, container.getGuacSshConnectionId());
            }
            if (container.isRdpEnabled() && container.getGuacRdpConnectionId() != null) {
                guacamoleAdminClient.grantConnectionPermission(desired, container.getGuacRdpConnectionId());
            }
            if (container.isVncEnabled() && container.getGuacVncConnectionId() != null) {
                guacamoleAdminClient.grantConnectionPermission(desired, container.getGuacVncConnectionId());
            }
        }
        guacamoleAdminClient.deleteUser(current);
        user.setGuacamoleUsername(desired);
        userRepository.save(user);
    }

    /** Falls back to the old "u<id>" synthetic name only if nspawnmgr itself has no username for this user. */
    private String desiredGuacUsername(User user) {
        String username = user.getUsername();
        return (username != null && !username.isBlank()) ? username : "u" + user.getId();
    }

    private String generatePassword() {
        byte[] bytes = new byte[24];
        secureRandom.nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }
}
