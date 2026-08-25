package com.nspawnmgr.service;

import com.nspawnmgr.cli.ContainerCliExecutor;
import com.nspawnmgr.cli.ContainerFilesystemProvisioner;
import com.nspawnmgr.cli.ContainerOutboundAccessManager;
import com.nspawnmgr.crypto.SecretEncryptionService;
import com.nspawnmgr.crypto.SshKeyPairGenerator;
import com.nspawnmgr.domain.Container;
import com.nspawnmgr.domain.ContainerBackend;
import com.nspawnmgr.domain.ContainerState;
import com.nspawnmgr.domain.Template;
import com.nspawnmgr.domain.User;
import com.nspawnmgr.guacamole.GuacamoleAdminClient;
import com.nspawnmgr.repository.ContainerCredentialRepository;
import com.nspawnmgr.repository.ContainerRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** Focused on {@link ProvisioningService#updatePodCommand} - the STOPPED-or-ERROR gate specifically,
 *  since a pod stuck in ERROR (never managed to start at all) being permanently unrecoverable
 *  through the UI was a real gap caught only via live testing (see
 *  project_pod_running_but_podman_exit_state memory). */
class ProvisioningServiceUpdatePodCommandTest {

    private ContainerRepository containerRepository;
    private ContainerCliExecutor cliExecutor;
    private ContainerFilesystemProvisioner filesystemProvisioner;
    private ProvisioningService service;

    @BeforeEach
    void setUp() {
        containerRepository = mock(ContainerRepository.class);
        cliExecutor = mock(ContainerCliExecutor.class);
        filesystemProvisioner = mock(ContainerFilesystemProvisioner.class);
        service = new ProvisioningService(containerRepository, mock(ContainerCredentialRepository.class), cliExecutor,
                filesystemProvisioner, mock(ContainerOutboundAccessManager.class), mock(TemplateService.class),
                mock(GuacamoleAdminClient.class), mock(ShareService.class), mock(SecretEncryptionService.class),
                mock(SshKeyPairGenerator.class), mock(SettingsService.class), mock(PackageCacheService.class),
                mock(PamCredentialAuthService.class));
    }

    private Container pod(ContainerState state) {
        Container container = new Container();
        container.setId(1L);
        container.setName("fmp1");
        container.setBackend(ContainerBackend.PODMAN);
        container.setState(state);
        Template template = new Template();
        template.setSourcePath("some-template");
        container.setTemplate(template);
        when(containerRepository.findByIdWithTemplate(1L)).thenReturn(Optional.of(container));
        return container;
    }

    @Test
    void allowsChangingTheCommandWhileStopped() {
        Container container = pod(ContainerState.STOPPED);

        service.updatePodCommand(1L, "sleep infinity", null);

        assertThat(container.getPodCommand()).isEqualTo("sleep infinity");
        assertThat(container.getState()).isEqualTo(ContainerState.STOPPED);
        verify(cliExecutor).remove(eq("fmp1"), eq(ContainerBackend.PODMAN));
        verify(filesystemProvisioner).createPodmanContainer(eq("some-template"), eq("fmp1"), eq("sleep infinity"), any());
    }

    @Test
    void allowsChangingTheCommandWhileInErrorAndClearsIt() {
        Container container = pod(ContainerState.ERROR);
        container.setErrorMessage("crun: cannot find `` in $PATH");

        service.updatePodCommand(1L, "sleep infinity", null);

        assertThat(container.getPodCommand()).isEqualTo("sleep infinity");
        assertThat(container.getState()).isEqualTo(ContainerState.STOPPED);
        assertThat(container.getErrorMessage()).isNull();
    }

    @Test
    void rejectsChangingTheCommandWhileRunning() {
        pod(ContainerState.RUNNING);

        assertThatThrownBy(() -> service.updatePodCommand(1L, "sleep infinity", null))
                .isInstanceOf(IllegalStateException.class);
        verify(cliExecutor, never()).remove(any(), any());
        verify(filesystemProvisioner, never()).createPodmanContainer(any(), any(), any(), any());
    }

    @Test
    void rejectsForANonPodmanContainer() {
        Container container = pod(ContainerState.STOPPED);
        container.setBackend(ContainerBackend.SYSTEMD_NSPAWN);

        assertThatThrownBy(() -> service.updatePodCommand(1L, "sleep infinity", null))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
