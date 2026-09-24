package com.nspawnmgr.service;

import com.nspawnmgr.cli.ContainerCliExecutor;
import com.nspawnmgr.cli.ContainerFilesystemProvisioner;
import com.nspawnmgr.cli.ContainerOutboundAccessManager;
import com.nspawnmgr.crypto.EncryptedValue;
import com.nspawnmgr.crypto.SecretEncryptionService;
import com.nspawnmgr.crypto.SshKeyPairGenerator;
import com.nspawnmgr.domain.Container;
import com.nspawnmgr.domain.ContainerBackend;
import com.nspawnmgr.domain.ContainerCredential;
import com.nspawnmgr.domain.CredentialType;
import com.nspawnmgr.domain.DesktopManager;
import com.nspawnmgr.guacamole.GuacamoleAdminClient;
import com.nspawnmgr.repository.ContainerCredentialRepository;
import com.nspawnmgr.repository.ContainerRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** Focused on {@link ProvisioningService#syncRdpCredentialAfterPasswordChange} - a hand-changed
 *  Linux password must reach the stored RDP credential, or Guacamole keeps auto-submitting the old one. */
class ProvisioningServiceSyncRdpCredentialTest {

    private ContainerCredentialRepository credentialRepository;
    private ContainerCliExecutor cliExecutor;
    private PamCredentialAuthService pamCredentialAuthService;
    private ProvisioningService service;
    private Container container;
    private ContainerCredential rdpCredential;

    @BeforeEach
    void setUp() {
        credentialRepository = mock(ContainerCredentialRepository.class);
        cliExecutor = mock(ContainerCliExecutor.class);
        pamCredentialAuthService = mock(PamCredentialAuthService.class);
        SecretEncryptionService encryption = mock(SecretEncryptionService.class);
        when(encryption.encrypt(any())).thenReturn(new EncryptedValue("ct", "iv"));
        service = new ProvisioningService(mock(ContainerRepository.class), credentialRepository, cliExecutor,
                mock(ContainerFilesystemProvisioner.class), mock(ContainerOutboundAccessManager.class), mock(TemplateService.class),
                mock(GuacamoleAdminClient.class), mock(ShareService.class), encryption,
                mock(SshKeyPairGenerator.class), mock(SettingsService.class), mock(PackageCacheService.class),
                pamCredentialAuthService, new ProvisioningCommands(), TestUserMessages.create());

        container = new Container();
        container.setId(1L);
        container.setName("deb1");
        container.setBackend(ContainerBackend.SYSTEMD_NSPAWN);
        container.setRdpEnabled(true);
        container.setGuacRdpConnectionId("7");
        container.setDesktopManager(DesktopManager.XFCE4_X11);
        rdpCredential = new ContainerCredential();
        rdpCredential.setType(CredentialType.RDP_PASSWORD);
        rdpCredential.setAccountName("alice");
        when(credentialRepository.findByContainerAndType(container, CredentialType.RDP_PASSWORD))
                .thenReturn(Optional.of(rdpCredential));
        when(cliExecutor.getInternalAddress("deb1", ContainerBackend.SYSTEMD_NSPAWN)).thenReturn("10.100.0.5");
    }

    @Test
    void savesNewPasswordAndReconcilesGuacamoleForTheRdpAccount() {
        service.syncRdpCredentialAfterPasswordChange(container, "alice", "new-pw");

        verify(credentialRepository).save(rdpCredential);
        assertThat(rdpCredential.getSecretCiphertext()).isEqualTo("ct");
        verify(pamCredentialAuthService).reconcileRdpConnectionCredentials(container, "10.100.0.5");
    }

    @Test
    void ignoresAnAccountThatIsNotTheRdpCredentialsAccount() {
        service.syncRdpCredentialAfterPasswordChange(container, "bob", "new-pw");

        verify(credentialRepository, never()).save(any());
        verify(pamCredentialAuthService, never()).reconcileRdpConnectionCredentials(any(), any());
    }

    @Test
    void ignoresGnomeContainers() {
        container.setDesktopManager(DesktopManager.GNOME);

        service.syncRdpCredentialAfterPasswordChange(container, "alice", "new-pw");

        verify(credentialRepository, never()).save(any());
    }

    @Test
    void ignoresContainersWithoutAnRdpConnection() {
        container.setGuacRdpConnectionId(null);

        service.syncRdpCredentialAfterPasswordChange(container, "alice", "new-pw");

        verify(credentialRepository, never()).save(any());
    }
}
