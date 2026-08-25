package com.nspawnmgr.service;

import com.nspawnmgr.cli.CommandResult;
import com.nspawnmgr.cli.ContainerCliException;
import com.nspawnmgr.cli.ContainerCliExecutor;
import com.nspawnmgr.crypto.EncryptedValue;
import com.nspawnmgr.crypto.SecretEncryptionService;
import com.nspawnmgr.domain.Container;
import com.nspawnmgr.domain.ContainerState;
import com.nspawnmgr.domain.ContainerUser;
import com.nspawnmgr.domain.ContainerUserActionRequest;
import com.nspawnmgr.domain.ContainerUserActionState;
import com.nspawnmgr.domain.ContainerUserActionType;
import com.nspawnmgr.domain.User;
import com.nspawnmgr.repository.ContainerRepository;
import com.nspawnmgr.repository.ContainerUserActionRequestRepository;
import com.nspawnmgr.repository.ContainerUserRepository;
import com.nspawnmgr.repository.UserRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

/**
 * Manages the Linux users inside a MANAGED container: listing (live when the container is
 * RUNNING, falling back to a database cache otherwise — there's no way to exec into a stopped
 * container), and adding/changing passwords (always requires the container to actually be up, plus
 * a sudo password — either the stored secret, transparently, or an admin approving a pending
 * request with one typed in, exactly like container-creation approval).
 */
@Service
public class ContainerUserService {

    /** Standard Linux username rules — also makes it safe to interpolate into `useradd ... <username>`. */
    private static final Pattern USERNAME_PATTERN = Pattern.compile("^[a-z_][a-z0-9_-]{0,31}$");
    private static final Duration COMMAND_TIMEOUT = Duration.ofSeconds(30);
    private static final int MIN_HUMAN_UID = 1000;
    private static final int MAX_HUMAN_UID = 65533;

    private final ContainerRepository containerRepository;
    private final UserRepository userRepository;
    private final ContainerCliExecutor cliExecutor;
    private final ContainerUserRepository containerUserRepository;
    private final ContainerUserActionRequestRepository actionRequestRepository;
    private final SecretEncryptionService secretEncryptionService;
    private final SettingsService settingsService;
    private final ProvisioningService provisioningService;

    public ContainerUserService(ContainerRepository containerRepository, UserRepository userRepository,
                                 ContainerCliExecutor cliExecutor, ContainerUserRepository containerUserRepository,
                                 ContainerUserActionRequestRepository actionRequestRepository,
                                 SecretEncryptionService secretEncryptionService, SettingsService settingsService,
                                 ProvisioningService provisioningService) {
        this.containerRepository = containerRepository;
        this.userRepository = userRepository;
        this.cliExecutor = cliExecutor;
        this.containerUserRepository = containerUserRepository;
        this.actionRequestRepository = actionRequestRepository;
        this.secretEncryptionService = secretEncryptionService;
        this.settingsService = settingsService;
        this.provisioningService = provisioningService;
    }

    @Transactional
    public List<String> listUsers(Container container) {
        container = attached(container);
        if (container.getState() == ContainerState.RUNNING) {
            return refreshAndListLive(container);
        }
        return containerUserRepository.findByContainer(container).stream().map(ContainerUser::getUsername).toList();
    }

    /** @return true if the request is now pending admin approval, false if it was applied immediately. */
    @Transactional
    public boolean addUser(Container container, User requester, String username, String password) {
        validateUsername(username);
        container = attached(container);
        if (needsApproval(container)) {
            createPendingRequest(container, attachedUser(requester), ContainerUserActionType.ADD_USER, username, password);
            return true;
        }
        executeAddUser(container, username, password, null);
        return false;
    }

    /** @return true if the request is now pending admin approval, false if it was applied immediately. */
    @Transactional
    public boolean changePassword(Container container, User requester, String username, String newPassword) {
        validateUsername(username);
        container = attached(container);
        if (needsApproval(container)) {
            createPendingRequest(container, attachedUser(requester), ContainerUserActionType.CHANGE_PASSWORD, username, newPassword);
            return true;
        }
        executeChangePassword(container, username, newPassword, null);
        return false;
    }

    /**
     * Re-points this container's generated SSH/RDP/VNC credentials at {@code newAccountName} —
     * any account already shown in {@link #listUsers} qualifies, not just the one auto-picked at
     * creation time (see ProvisioningService.resolveInitialAccountName). Same
     * immediate-vs-pending-approval branching as {@link #addUser}/{@link #changePassword} — this
     * needs the identical sudo-capable exec-in-container access those do.
     *
     * @return true if the request is now pending admin approval, false if it was applied immediately.
     */
    @Transactional
    public boolean changePrimaryAccount(Container container, User requester, String newAccountName) {
        validateUsername(newAccountName);
        container = attached(container);
        if (needsApproval(container)) {
            actionRequestRepository.save(new ContainerUserActionRequest(container, attachedUser(requester),
                    ContainerUserActionType.CHANGE_PRIMARY_ACCOUNT, newAccountName, null, null));
            return true;
        }
        provisioningService.changePrimaryAccount(container, newAccountName, null);
        return false;
    }

    /**
     * The controller loads {@code container} in its own (already-closed) transaction, so it's
     * detached relative to this method's — fine for reading scalar fields (name, state), but
     * persisting a *new* entity that references it directly (ContainerUser, ContainerUserActionRequest)
     * would throw "detached entity passed to persist". A reference resolved in *this* session fixes
     * it — same pattern ShareService.ensureGuacamoleUser already uses for the same reason.
     */
    private Container attached(Container container) {
        return containerRepository.getReferenceById(container.getId());
    }

    /** Same reasoning as {@link #attached(Container)}, for a User (requester/admin) coming from the controller. */
    private User attachedUser(User user) {
        return userRepository.getReferenceById(user.getId());
    }

    public List<ContainerUserActionRequest> listPendingRequests() {
        return actionRequestRepository.findByStateWithContainerAndRequestedBy(ContainerUserActionState.PENDING);
    }

    /**
     * {@code sudoPassword} is only actually required (and used) when in admin-approval mode — a
     * request can just as easily be pending because the container wasn't RUNNING at request time,
     * with a stored sudo secret configured the whole time, in which case approving it should work
     * exactly like the immediate-execution path: {@code null} override, falls back to that secret.
     * Anything submitted is ignored in that case rather than erroring, since the frontend only shows
     * the field at all when {@code settingsService.sshApprovalRequired()} is true.
     */
    @Transactional
    public ContainerUserActionRequest approveRequest(Long requestId, User admin, char[] sudoPassword) {
        ContainerUserActionRequest request = actionRequestRepository.findByIdWithContainer(requestId)
                .orElseThrow(() -> new IllegalArgumentException("No such request: " + requestId));
        requirePending(request);
        if (request.getContainer().getState() != ContainerState.RUNNING) {
            throw new IllegalStateException("Container must be running to apply this request");
        }
        char[] sudoOverride = settingsService.sshApprovalRequired() ? requireSudoPassword(sudoPassword) : null;
        switch (request.getActionType()) {
            case ADD_USER -> executeAddUser(request.getContainer(), request.getUsername(),
                    decryptRequestPassword(request), sudoOverride);
            case CHANGE_PASSWORD -> executeChangePassword(request.getContainer(), request.getUsername(),
                    decryptRequestPassword(request), sudoOverride);
            case CHANGE_PRIMARY_ACCOUNT -> provisioningService.changePrimaryAccount(
                    request.getContainer(), request.getUsername(), sudoOverride);
        }
        resolve(request, ContainerUserActionState.APPLIED, attachedUser(admin));
        return request;
    }

    /** {@code null} for a CHANGE_PRIMARY_ACCOUNT request - it never had a password to begin with,
     *  see {@link #changePrimaryAccount}. */
    private String decryptRequestPassword(ContainerUserActionRequest request) {
        return secretEncryptionService.decrypt(request.getPasswordCiphertext(), request.getIv());
    }

    private char[] requireSudoPassword(char[] sudoPassword) {
        if (sudoPassword == null || sudoPassword.length == 0) {
            throw new IllegalArgumentException("A sudo password is required to approve this request");
        }
        return sudoPassword;
    }

    @Transactional
    public ContainerUserActionRequest denyRequest(Long requestId, User admin) {
        ContainerUserActionRequest request = actionRequestRepository.findByIdWithContainer(requestId)
                .orElseThrow(() -> new IllegalArgumentException("No such request: " + requestId));
        requirePending(request);
        resolve(request, ContainerUserActionState.DENIED, attachedUser(admin));
        return request;
    }

    private void requirePending(ContainerUserActionRequest request) {
        if (request.getState() != ContainerUserActionState.PENDING) {
            throw new IllegalStateException("Request " + request.getId() + " is not pending (current state: " + request.getState() + ")");
        }
    }

    private void resolve(ContainerUserActionRequest request, ContainerUserActionState state, User admin) {
        request.setState(state);
        request.setResolvedAt(Instant.now());
        request.setResolvedBy(admin);
        // No reason to keep a decryptable copy of the password around once the request is resolved.
        request.setPasswordCiphertext(null);
        request.setIv(null);
    }

    private boolean needsApproval(Container container) {
        return settingsService.sshApprovalRequired() || container.getState() != ContainerState.RUNNING;
    }

    private void createPendingRequest(Container container, User requester, ContainerUserActionType type,
                                       String username, String password) {
        EncryptedValue encrypted = secretEncryptionService.encrypt(password);
        actionRequestRepository.save(new ContainerUserActionRequest(container, requester, type, username,
                encrypted.ciphertextBase64(), encrypted.ivBase64()));
    }

    private void executeAddUser(Container container, String username, String password, char[] sudoPasswordOverride) {
        CommandResult result = cliExecutor.runInMachine(container.getName(), container.getBackend(),
                List.of("useradd", "-m", "-s", "/bin/bash", username), COMMAND_TIMEOUT, sudoPasswordOverride);
        if (!result.success()) {
            throw new ContainerCliException("Failed to add user " + username + " in " + container.getName() + ": " + result.stderr());
        }
        setPassword(container, username, password, sudoPasswordOverride);
        refreshAndListLive(container);
    }

    private void executeChangePassword(Container container, String username, String password, char[] sudoPasswordOverride) {
        setPassword(container, username, password, sudoPasswordOverride);
        refreshAndListLive(container);
    }

    /** Password is fed over stdin to chpasswd, never interpolated into a shell string — it's owner-typed, unlike every other value runInMachine has ever handled. */
    private void setPassword(Container container, String username, String password, char[] sudoPasswordOverride) {
        CommandResult result = cliExecutor.runInMachine(container.getName(), container.getBackend(), List.of("chpasswd"), COMMAND_TIMEOUT,
                sudoPasswordOverride, username + ":" + password + "\n");
        if (!result.success()) {
            throw new ContainerCliException("Failed to set password for " + username + " in " + container.getName() + ": " + result.stderr());
        }
    }

    private List<String> refreshAndListLive(Container container) {
        CommandResult result = cliExecutor.listContainerUsers(container.getName());
        if (!result.success()) {
            throw new ContainerCliException("Failed to list users in " + container.getName() + ": " + result.stderr());
        }
        List<String> usernames = parsePasswd(result.stdout());
        containerUserRepository.deleteByContainer(container);
        // Without this, Hibernate's default flush ordering (grouped by action type — all inserts,
        // then all deletes — not call order) would queue the fresh rows' INSERTs ahead of this
        // DELETE, hitting the (container_id, username) unique constraint on any username that's
        // unchanged since the last refresh.
        containerUserRepository.flush();
        for (String username : usernames) {
            containerUserRepository.save(new ContainerUser(container, username));
        }
        return usernames;
    }

    private static List<String> parsePasswd(String stdout) {
        List<String> usernames = new ArrayList<>();
        for (String line : stdout.split("\n")) {
            String[] fields = line.split(":");
            if (fields.length < 3) {
                continue;
            }
            try {
                int uid = Integer.parseInt(fields[2].trim());
                if (uid >= MIN_HUMAN_UID && uid <= MAX_HUMAN_UID) {
                    usernames.add(fields[0].trim());
                }
            } catch (NumberFormatException ignored) {
                // Not a uid field we can parse — skip the line rather than fail the whole listing.
            }
        }
        return usernames;
    }

    private static void validateUsername(String username) {
        if (!USERNAME_PATTERN.matcher(username).matches()) {
            throw new IllegalArgumentException("Invalid username '" + username + "' — must match " + USERNAME_PATTERN.pattern());
        }
    }
}
