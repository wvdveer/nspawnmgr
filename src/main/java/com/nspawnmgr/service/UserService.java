package com.nspawnmgr.service;

import com.nspawnmgr.domain.Role;
import com.nspawnmgr.domain.User;
import com.nspawnmgr.repository.UserRepository;
import com.nspawnmgr.security.ExternalIdentity;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.util.List;

@Service
public class UserService {

    private final UserRepository userRepository;
    private final SettingsService settingsService;
    private final ContainerDiscoveryService containerDiscoveryService;
    private final TranslationService translationService;
    private final UserMessages messages;

    public UserService(UserRepository userRepository, SettingsService settingsService,
                        ContainerDiscoveryService containerDiscoveryService, TranslationService translationService,
                        UserMessages messages) {
        this.userRepository = userRepository;
        this.settingsService = settingsService;
        this.containerDiscoveryService = containerDiscoveryService;
        this.translationService = translationService;
        this.messages = messages;
    }

    /**
     * Role handling has two mutually exclusive modes, selected by whether
     * {@code nspawnmgr.auth.user-is-admin-json} is configured:
     * <ul>
     *     <li>External-managed: role is recomputed from the identity JSON's admin flag on every
     *     login (promote and demote), including for the very first user ever. Manual grant/revoke
     *     through the app is rejected in this mode (see AdminUserApiController).</li>
     *     <li>App-managed (default, JsonPath blank): role is never touched here after initial
     *     creation. The first user ever created is auto-promoted to ADMIN; everyone else defaults
     *     to USER and is managed manually by an existing admin afterward.</li>
     * </ul>
     *
     * <p>The very first user is also the moment nspawnmgr's own self-hosted machines (the
     * {@code nspawnmgr} app machine and its database machine — see
     * {@code nspawnmgr-bootstrap-app-machine.sh}/{@code nspawnmgr-bootstrap-db-machine.sh}) first
     * get registered as ordinary, visible {@code Container} rows, via the same
     * {@link ContainerDiscoveryService} an admin would otherwise trigger by hand from "Discover
     * machines" — they were created before the app's own database (and therefore any {@code User}
     * row to own them) even existed, so there was no earlier point ownership could have been
     * assigned.
     *
     * <p>That {@code discover()} call is deliberately deferred until AFTER this method's own
     * transaction commits (via {@link TransactionSynchronizationManager}), not called inline -
     * confirmed live: {@code discover()} is deliberately not {@code @Transactional} itself, so each
     * container it registers gets its own immediately-committing transaction ONLY when discover()
     * runs with no ambient transaction already open (true for the "Discover machines" button, a
     * plain REST call) - called inline from here, everything discover() does would instead silently
     * join THIS method's own still-open transaction, and ProvisioningService
     * .provisionSshForExistingContainer's own REQUIRES_NEW sub-transaction (see its own javadoc)
     * would then be unable to see the not-yet-committed container row it needs to attach an SSH
     * credential to, failing every managed-SSH attempt on the very first install with a foreign-key
     * violation - the self-hosted nspawnmgr machine ending up with neither managed SSH nor the
     * prompt-credentials fallback (which ContainerDiscoveryService also deliberately skips for it).
     */
    @Transactional
    public User upsert(ExternalIdentity identity) {
        boolean externalManaged = isExternalRoleManaged();
        boolean[] isFirstUser = {false};
        User user = userRepository.findByExternalUserId(identity.externalId())
                .orElseGet(() -> {
                    User created = new User(identity.externalId());
                    if (!externalManaged && userRepository.count() == 0) {
                        created.setRole(Role.ADMIN);
                        isFirstUser[0] = true;
                    }
                    return created;
                });
        user.setUsername(identity.username());
        user.setEmail(identity.email());
        user.setFullName(identity.fullName());
        if (externalManaged) {
            user.setRole(identity.adminFlag() ? Role.ADMIN : Role.USER);
        }
        user.touch();
        user = userRepository.save(user);
        if (isFirstUser[0]) {
            User firstAdmin = user;
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    containerDiscoveryService.discover(firstAdmin);
                }
            });
        }
        return user;
    }

    public boolean isExternalRoleManaged() {
        String path = settingsService.authUserIsAdminJson();
        return path != null && !path.isBlank();
    }

    public List<User> listAll() {
        return userRepository.findAll();
    }

    /** Manual role grant/revoke — only available in app-managed mode (see class javadoc). */
    @Transactional
    public User setRole(Long targetUserId, Role newRole, User actingAdmin) {
        if (isExternalRoleManaged()) {
            throw new IllegalStateException(messages.get("error.user.roleExternallyManaged"));
        }
        User target = userRepository.findById(targetUserId)
                .orElseThrow(() -> new IllegalArgumentException(messages.get("error.common.noSuchUser", targetUserId)));
        if (target.getId().equals(actingAdmin.getId()) && newRole != Role.ADMIN) {
            throw new IllegalStateException(messages.get("error.user.cannotRemoveOwnAdmin"));
        }
        if (target.getRole() == Role.ADMIN && newRole == Role.USER && userRepository.countByRole(Role.ADMIN) <= 1) {
            throw new IllegalStateException(messages.get("error.user.cannotRemoveLastAdmin"));
        }
        target.setRole(newRole);
        target.touch();
        return userRepository.save(target);
    }

    /** {@code language}: a 2-letter code matching one of {@link TranslationService#availableLocales()},
     *  or blank/null to clear the override and go back to auto-detecting from the browser's own
     *  Accept-Language header on every request - see LocaleResolutionService. */
    @Transactional
    public User updatePreferredLanguage(User user, String language) {
        String normalized = (language == null || language.isBlank()) ? null : language.toLowerCase();
        if (normalized != null && !translationService.isAvailable(normalized)) {
            throw new IllegalArgumentException(messages.get("error.user.languageNotAvailable", language));
        }
        user.setPreferredLanguage(normalized);
        user.touch();
        return userRepository.save(user);
    }
}
