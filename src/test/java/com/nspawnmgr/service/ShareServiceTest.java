package com.nspawnmgr.service;

import com.nspawnmgr.crypto.EncryptedValue;
import com.nspawnmgr.crypto.SecretEncryptionService;
import com.nspawnmgr.domain.Container;
import com.nspawnmgr.domain.GuacamoleUserSecret;
import com.nspawnmgr.domain.User;
import com.nspawnmgr.guacamole.GuacamoleAdminClient;
import com.nspawnmgr.repository.ContainerShareRepository;
import com.nspawnmgr.repository.GuacamoleUserSecretRepository;
import com.nspawnmgr.repository.UserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ShareServiceTest {

    private ContainerShareRepository containerShareRepository;
    private UserRepository userRepository;
    private GuacamoleUserSecretRepository guacamoleUserSecretRepository;
    private GuacamoleAdminClient guacamoleAdminClient;
    private GuacamoleUserSecretWriter guacamoleUserSecretWriter;
    private ShareService service;

    @BeforeEach
    void setUp() {
        containerShareRepository = mock(ContainerShareRepository.class);
        userRepository = mock(UserRepository.class);
        guacamoleUserSecretRepository = mock(GuacamoleUserSecretRepository.class);
        guacamoleAdminClient = mock(GuacamoleAdminClient.class);
        guacamoleUserSecretWriter = mock(GuacamoleUserSecretWriter.class);
        SecretEncryptionService secretEncryptionService = mock(SecretEncryptionService.class);
        when(secretEncryptionService.encrypt(anyString())).thenReturn(new EncryptedValue("cipher", "iv"));
        when(userRepository.save(any())).thenAnswer(invocation -> invocation.getArgument(0));

        service = new ShareService(containerShareRepository, userRepository, guacamoleUserSecretRepository,
                guacamoleAdminClient, secretEncryptionService, guacamoleUserSecretWriter);
    }

    private User user(Long id, String username, String existingGuacUsername) {
        User user = new User("ext-" + id);
        user.setId(id);
        user.setUsername(username);
        user.setGuacamoleUsername(existingGuacUsername);
        return user;
    }

    private Container container(Long id) {
        Container container = new Container();
        container.setId(id);
        return container;
    }

    @Test
    void grantAccessSkipsReInsertingTheShareRowWhenAlreadyShared() {
        Container container = container(1L);
        User owner = user(10L, "ward", "ward");
        when(containerShareRepository.existsByContainerAndUser(container, owner)).thenReturn(true);
        when(userRepository.findByIdForUpdate(10L)).thenReturn(Optional.of(owner));

        service.grantAccess(container, owner);

        verify(containerShareRepository, never()).save(any());
    }

    @Test
    void grantAccessStillRegrantsPermissionsWhenAlreadySharedSoALaterlyEnabledVncIsntMissed() {
        // Confirmed live: a container whose SSH access was already shared (creating the
        // ContainerShare row) never granted a VNC permission on the user's first VNC connect,
        // because the old implementation treated "share row exists" as "nothing left to grant" -
        // which stops being true once a new access type gets enabled on the container after that
        // row was created. Guacamole's own grant is an idempotent JSON-Patch "add", so re-running
        // it every call is safe.
        Container container = container(1L);
        container.setGuacSshConnectionId("ssh-conn");
        container.setVncEnabled(true);
        container.setGuacVncConnectionId("vnc-conn");
        User owner = user(10L, "ward", "ward");
        when(containerShareRepository.existsByContainerAndUser(container, owner)).thenReturn(true);
        when(userRepository.findByIdForUpdate(10L)).thenReturn(Optional.of(owner));

        service.grantAccess(container, owner);

        verify(guacamoleAdminClient).grantConnectionPermission("ward", "ssh-conn");
        verify(guacamoleAdminClient).grantConnectionPermission("ward", "vnc-conn");
    }

    @Test
    void grantAccessNeverGrantsVncPermissionWhenNotEnabled() {
        Container container = container(1L);
        container.setVncEnabled(false);
        container.setGuacVncConnectionId("vnc-conn");
        User owner = user(10L, "ward", "ward");
        when(containerShareRepository.existsByContainerAndUser(container, owner)).thenReturn(false);
        when(userRepository.findByIdForUpdate(10L)).thenReturn(Optional.of(owner));

        service.grantAccess(container, owner);

        verify(guacamoleAdminClient, never()).grantConnectionPermission(anyString(), eq("vnc-conn"));
    }

    @Test
    void revokeAccessRevokesVncPermissionWhenConnectionIdIsPresent() {
        Container container = container(1L);
        container.setGuacVncConnectionId("vnc-conn");
        User owner = user(10L, "ward", "ward");

        service.revokeAccess(container, owner);

        verify(guacamoleAdminClient).revokeConnectionPermission("ward", "vnc-conn");
    }

    @Test
    void grantAccessReusesAnAlreadyProvisionedGuacamoleAccountWithoutRecreatingIt() {
        // Row-locked read finds a username already committed by a concurrent grantAccess call for
        // the same owner (e.g. two containers created together) - must not race a second Guacamole
        // account/secret into existence for it.
        Container container = container(1L);
        User owner = user(10L, "ward", null);
        User lockedWithUsernameAlreadySet = user(10L, "ward", "ward");
        when(containerShareRepository.existsByContainerAndUser(container, owner)).thenReturn(false);
        when(userRepository.findByIdForUpdate(10L)).thenReturn(Optional.of(lockedWithUsernameAlreadySet));

        service.grantAccess(container, owner);

        verify(guacamoleAdminClient, never()).createOrGetUser(anyString(), anyString());
        verify(guacamoleUserSecretRepository, never()).save(any());
    }

    @Test
    void grantAccessProvisionsAFreshGuacamoleAccountWhenNoneExistsYet() {
        Container container = container(1L);
        User owner = user(10L, "ward", null);
        when(containerShareRepository.existsByContainerAndUser(container, owner)).thenReturn(false);
        when(userRepository.findByIdForUpdate(10L)).thenReturn(Optional.of(owner));

        service.grantAccess(container, owner);

        verify(guacamoleAdminClient).createOrGetUser(eq("ward"), anyString());
        verify(guacamoleUserSecretRepository).save(any(GuacamoleUserSecret.class));
        assertThat(owner.getGuacamoleUsername()).isEqualTo("ward");
    }

    @Test
    void grantAccessReturnsTheResolvedUsernameEvenWhenTheCallersOwnUserReferenceStaysStale() {
        // Confirmed live (2026-08-14): findByIdForUpdate returns a genuinely SEPARATE re-fetched
        // Java object in production (same DB row, different instance) - ensureGuacamoleUser
        // deliberately mutates *that* copy, not the caller's own `user` reference (see its own
        // javadoc on the race this avoids). Every earlier test in this file stubs
        // findByIdForUpdate to return the exact same object passed in as `owner`, which
        // accidentally aliases the two and would never have caught this: the caller's own
        // reference (`owner` here) must stay null even after a successful grant, and the resolved
        // username must come back via the return value instead - this is exactly what
        // ContainerSessionService#start needed and wasn't getting, NPEing inside
        // GuacamoleSessionService's token cache for every user's first-ever session.
        Container container = container(1L);
        User owner = user(10L, "ward", null);
        User separatelyFetchedLockedCopy = user(10L, "ward", null);
        when(containerShareRepository.existsByContainerAndUser(container, owner)).thenReturn(false);
        when(userRepository.findByIdForUpdate(10L)).thenReturn(Optional.of(separatelyFetchedLockedCopy));

        String result = service.grantAccess(container, owner);

        assertThat(result).isEqualTo("ward");
        assertThat(owner.getGuacamoleUsername()).isNull();
        assertThat(separatelyFetchedLockedCopy.getGuacamoleUsername()).isEqualTo("ward");
    }

    @Test
    void resetGuacamoleAccountInsertsANewSecretWhenTheWriterSucceeds() {
        User owner = user(10L, "admin", "admin");
        when(userRepository.findByIdForUpdate(10L)).thenReturn(Optional.of(owner));

        service.resetGuacamoleAccount(owner);

        verify(guacamoleUserSecretWriter).insert(eq(owner), anyString(), anyString());
        verify(guacamoleUserSecretWriter, never()).update(any(), any(), any());
    }

    @Test
    void resetGuacamoleAccountFallsBackToUpdateWhenInsertHitsADuplicateKey() {
        // Confirmed live (arch-kde, 2026-08-14): a plain findById-then-branch occasionally still
        // found no row for a user whose secret genuinely already existed (this table has no row
        // lock protecting it the way the owning User row does), so the old "not present" branch's
        // save() hit guacamole_user_secrets_pkey and the whole session-start request 500'd. The
        // insert is now tried first and a duplicate-key conflict falls back to an explicit update,
        // rather than trusting the existence check.
        User owner = user(10L, "admin", "admin");
        when(userRepository.findByIdForUpdate(10L)).thenReturn(Optional.of(owner));
        org.mockito.Mockito.doThrow(new org.springframework.dao.DataIntegrityViolationException("duplicate key"))
                .when(guacamoleUserSecretWriter).insert(eq(owner), anyString(), anyString());

        service.resetGuacamoleAccount(owner);

        verify(guacamoleUserSecretWriter).update(eq(10L), anyString(), anyString());
    }
}
