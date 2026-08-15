package com.nspawnmgr.web;

import com.nspawnmgr.crypto.SecretEncryptionService;
import com.nspawnmgr.domain.Container;
import com.nspawnmgr.domain.PamAuthSource;
import com.nspawnmgr.domain.User;
import com.nspawnmgr.repository.ContainerCredentialRepository;
import com.nspawnmgr.repository.ContainerRepository;
import com.nspawnmgr.repository.ContainerShareRepository;
import com.nspawnmgr.repository.UserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Covers {@link PamAuthVerifyController#isAuthorizedFor} - the check added 2026-08-13 after a
 * live finding on fed1: NSPAWNMGR_AUTH_BACKEND's SMB/org credential check alone let anyone with a
 * valid org login (and a matching local account on the container) straight into the container's
 * OS-level RDP, regardless of whether nspawnmgr's own owner/sharing model had granted them
 * anything, and entirely outside nspawnmgr's own web app/audit log.
 */
class PamAuthVerifyControllerTest {

    private UserRepository userRepository;
    private ContainerShareRepository containerShareRepository;
    private PamAuthVerifyController controller;

    @BeforeEach
    void setUp() {
        ContainerRepository containerRepository = mock(ContainerRepository.class);
        ContainerCredentialRepository credentialRepository = mock(ContainerCredentialRepository.class);
        userRepository = mock(UserRepository.class);
        containerShareRepository = mock(ContainerShareRepository.class);
        SecretEncryptionService secretEncryptionService = mock(SecretEncryptionService.class);
        controller = new PamAuthVerifyController(containerRepository, credentialRepository,
                userRepository, containerShareRepository, secretEncryptionService);
    }

    private Container containerWithOwner(User owner) {
        Container container = new Container();
        container.setId(1L);
        container.setName("fed1");
        container.setPamAuthSource(PamAuthSource.NSPAWNMGR_AUTH_BACKEND);
        container.setOwner(owner);
        return container;
    }

    private User user(long id, String username) {
        User user = new User("external-" + id);
        user.setId(id);
        user.setUsername(username);
        return user;
    }

    @Test
    void ownerIsAuthorized() {
        User admin = user(1L, "admin");
        Container container = containerWithOwner(admin);
        when(userRepository.findByUsernameIgnoreCase("admin")).thenReturn(Optional.of(admin));

        assertThat(controller.isAuthorizedFor(container, "admin")).isTrue();
    }

    @Test
    void userWithAnExistingShareGrantIsAuthorized() {
        User admin = user(1L, "admin");
        User ward = user(2L, "ward");
        Container container = containerWithOwner(admin);
        when(userRepository.findByUsernameIgnoreCase("ward")).thenReturn(Optional.of(ward));
        when(containerShareRepository.existsByContainerAndUser(container, ward)).thenReturn(true);

        assertThat(controller.isAuthorizedFor(container, "ward")).isTrue();
    }

    @Test
    void authenticatedUserWithNoGrantIsDenied() {
        // The exact bug found live on fed1 (2026-08-13): "ward" had a valid org credential (and,
        // by that point, even a real local account on the container) but the container's owner
        // never shared it with them - must still be denied.
        User admin = user(1L, "admin");
        User ward = user(2L, "ward");
        Container container = containerWithOwner(admin);
        when(userRepository.findByUsernameIgnoreCase("ward")).thenReturn(Optional.of(ward));
        when(containerShareRepository.existsByContainerAndUser(container, ward)).thenReturn(false);

        assertThat(controller.isAuthorizedFor(container, "ward")).isFalse();
    }

    @Test
    void unknownNspawnmgrUsernameIsDenied() {
        User admin = user(1L, "admin");
        Container container = containerWithOwner(admin);
        when(userRepository.findByUsernameIgnoreCase("someone-else")).thenReturn(Optional.empty());

        assertThat(controller.isAuthorizedFor(container, "someone-else")).isFalse();
        // Doesn't even bother checking for a share grant - there's no User to check it against.
        org.mockito.Mockito.verify(containerShareRepository, org.mockito.Mockito.never())
                .existsByContainerAndUser(any(), any());
    }
}
