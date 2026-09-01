package com.nspawnmgr.service;

import com.nspawnmgr.cli.ContainerPortProbe;
import com.nspawnmgr.crypto.SecretEncryptionService;
import com.nspawnmgr.domain.Container;
import com.nspawnmgr.domain.ContainerCredential;
import com.nspawnmgr.domain.CredentialType;
import com.nspawnmgr.domain.RdpSecurityMode;
import com.nspawnmgr.guacamole.GuacamoleAdminClient;
import com.nspawnmgr.repository.ContainerCredentialRepository;
import com.nspawnmgr.repository.ContainerRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ContainerAccessServiceTest {

    private ContainerRepository containerRepository;
    private ContainerCredentialRepository credentialRepository;
    private ContainerPortProbe portProbe;
    private GuacamoleAdminClient guacamoleAdminClient;
    private SecretEncryptionService secretEncryptionService;
    private ContainerAccessService service;

    @BeforeEach
    void setUp() {
        containerRepository = mock(ContainerRepository.class);
        credentialRepository = mock(ContainerCredentialRepository.class);
        portProbe = mock(ContainerPortProbe.class);
        guacamoleAdminClient = mock(GuacamoleAdminClient.class);
        secretEncryptionService = mock(SecretEncryptionService.class);
        service = new ContainerAccessService(containerRepository, credentialRepository, portProbe, guacamoleAdminClient, secretEncryptionService,
                TestUserMessages.create());
    }

    private Container containerWithAddress(String address) {
        Container container = new Container();
        container.setName("hand-built-1");
        container.setInternalAddress(address);
        return container;
    }

    @Test
    void enableSshWiresPromptCredentialsConnectionWhenPortOpenAndNoCredential() {
        Container container = containerWithAddress("10.0.3.5");
        when(credentialRepository.findByContainerAndType(container, CredentialType.SSH_KEY)).thenReturn(Optional.empty());
        when(portProbe.isOpen("10.0.3.5", 22)).thenReturn(true);
        when(guacamoleAdminClient.createSshConnectionPromptCredentials(anyString(), anyString(), anyInt())).thenReturn("conn-1");

        service.enableSsh(container);

        assertThat(container.getGuacSshConnectionId()).isEqualTo("conn-1");
        verify(containerRepository).save(container);
    }

    @Test
    void enableSshFailsWhenPortClosed() {
        Container container = containerWithAddress("10.0.3.5");
        when(credentialRepository.findByContainerAndType(container, CredentialType.SSH_KEY)).thenReturn(Optional.empty());
        when(portProbe.isOpen("10.0.3.5", 22)).thenReturn(false);

        assertThatThrownBy(() -> service.enableSsh(container)).isInstanceOf(IllegalStateException.class);

        assertThat(container.getGuacSshConnectionId()).isNull();
        verify(containerRepository, never()).save(any());
    }

    @Test
    void enableSshFailsWhenCredentialAlreadyExists() {
        Container container = containerWithAddress("10.0.3.5");
        when(credentialRepository.findByContainerAndType(container, CredentialType.SSH_KEY))
                .thenReturn(Optional.of(new ContainerCredential()));

        assertThatThrownBy(() -> service.enableSsh(container)).isInstanceOf(IllegalStateException.class);

        verify(portProbe, never()).isOpen(any(), anyInt());
        verify(containerRepository, never()).save(any());
    }

    @Test
    void enableRdpSetsRdpEnabledFlagAlongsideConnectionId() {
        Container container = containerWithAddress("10.0.3.5");
        when(credentialRepository.findByContainerAndType(container, CredentialType.RDP_PASSWORD)).thenReturn(Optional.empty());
        when(portProbe.isOpen("10.0.3.5", 3389)).thenReturn(true);
        when(guacamoleAdminClient.createRdpConnectionPromptCredentials(anyString(), anyString(), anyInt(), anyString())).thenReturn("conn-2");

        service.enableRdp(container);

        assertThat(container.getGuacRdpConnectionId()).isEqualTo("conn-2");
        assertThat(container.isRdpEnabled()).isTrue();
    }

    @Test
    void setRdpSecurityPersistsAndRePushesRealCredentialConnection() {
        Container container = containerWithAddress("10.0.3.5");
        container.setGuacRdpConnectionId("conn-rdp");
        ContainerCredential credential = new ContainerCredential();
        credential.setAccountName("admin");
        credential.setSecretCiphertext("ciphertext");
        credential.setIv("iv");
        when(credentialRepository.findByContainerAndType(container, CredentialType.RDP_PASSWORD))
                .thenReturn(Optional.of(credential));
        when(secretEncryptionService.decrypt("ciphertext", "iv")).thenReturn("plaintext-password");

        service.setRdpSecurity(container, RdpSecurityMode.RDP);

        assertThat(container.getRdpSecurity()).isEqualTo(RdpSecurityMode.RDP);
        verify(containerRepository).save(container);
        verify(guacamoleAdminClient).updateRdpConnection("conn-rdp", "hand-built-1-rdp", "10.0.3.5", 3389,
                "admin", "plaintext-password", "rdp");
    }

    @Test
    void setRdpSecurityRePushesPromptCredentialsConnectionWhenNoStoredCredential() {
        Container container = containerWithAddress("10.0.3.5");
        container.setGuacRdpConnectionId("conn-rdp");
        when(credentialRepository.findByContainerAndType(container, CredentialType.RDP_PASSWORD)).thenReturn(Optional.empty());

        service.setRdpSecurity(container, RdpSecurityMode.TLS);

        verify(guacamoleAdminClient).updateRdpConnectionPromptCredentials("conn-rdp", "hand-built-1-rdp", "10.0.3.5", 3389, "tls");
    }

    @Test
    void setRdpSecurityJustPersistsWhenNoConnectionExistsYet() {
        Container container = containerWithAddress("10.0.3.5");

        service.setRdpSecurity(container, RdpSecurityMode.NLA);

        assertThat(container.getRdpSecurity()).isEqualTo(RdpSecurityMode.NLA);
        verify(containerRepository).save(container);
        verify(guacamoleAdminClient, never()).updateRdpConnection(any(), any(), any(), anyInt(), any(), any(), any());
        verify(guacamoleAdminClient, never()).updateRdpConnectionPromptCredentials(any(), any(), any(), anyInt(), any());
    }

    @Test
    void enableVncSetsVncEnabledFlagAlongsideConnectionId() {
        Container container = containerWithAddress("10.0.3.5");
        when(credentialRepository.findByContainerAndType(container, CredentialType.VNC_PASSWORD)).thenReturn(Optional.empty());
        when(portProbe.isOpen("10.0.3.5", 5900)).thenReturn(true);
        when(guacamoleAdminClient.createVncConnectionPromptCredentials(anyString(), anyString(), anyInt())).thenReturn("conn-3");

        service.enableVnc(container);

        assertThat(container.getGuacVncConnectionId()).isEqualTo("conn-3");
        assertThat(container.isVncEnabled()).isTrue();
    }

    @Test
    void enableVncFailsWhenPortClosed() {
        Container container = containerWithAddress("10.0.3.5");
        when(credentialRepository.findByContainerAndType(container, CredentialType.VNC_PASSWORD)).thenReturn(Optional.empty());
        when(portProbe.isOpen("10.0.3.5", 5900)).thenReturn(false);

        assertThatThrownBy(() -> service.enableVnc(container)).isInstanceOf(IllegalStateException.class);

        assertThat(container.getGuacVncConnectionId()).isNull();
        verify(containerRepository, never()).save(any());
    }

    @Test
    void enableVncFailsWhenCredentialAlreadyExists() {
        Container container = containerWithAddress("10.0.3.5");
        when(credentialRepository.findByContainerAndType(container, CredentialType.VNC_PASSWORD))
                .thenReturn(Optional.of(new ContainerCredential()));

        assertThatThrownBy(() -> service.enableVnc(container)).isInstanceOf(IllegalStateException.class);

        verify(portProbe, never()).isOpen(any(), anyInt());
        verify(containerRepository, never()).save(any());
    }

    @Test
    void disableVncRemovesConnectionAndClearsFlag() {
        Container container = containerWithAddress("10.0.3.5");
        container.setGuacVncConnectionId("conn-3");
        container.setVncEnabled(true);

        service.disableVnc(container);

        verify(guacamoleAdminClient).deleteConnection("conn-3");
        assertThat(container.getGuacVncConnectionId()).isNull();
        assertThat(container.isVncEnabled()).isFalse();
        verify(containerRepository).save(container);
    }

    @Test
    void disableSshRemovesConnectionAndClearsId() {
        Container container = containerWithAddress("10.0.3.5");
        container.setGuacSshConnectionId("conn-1");

        service.disableSsh(container);

        verify(guacamoleAdminClient).deleteConnection("conn-1");
        assertThat(container.getGuacSshConnectionId()).isNull();
        verify(containerRepository).save(container);
    }

    @Test
    void disableSshIsNoOpWhenNoConnectionExists() {
        Container container = containerWithAddress("10.0.3.5");

        service.disableSsh(container);

        verify(guacamoleAdminClient, never()).deleteConnection(any());
        verify(containerRepository, never()).save(any());
    }

    @Test
    void tryAutoEnableSwallowsPortNotOpenFailure() {
        Container container = containerWithAddress("10.0.3.5");
        when(credentialRepository.findByContainerAndType(eq(container), any())).thenReturn(Optional.empty());
        when(portProbe.isOpen(any(), anyInt())).thenReturn(false);

        service.tryAutoEnable(container);

        assertThat(container.getGuacSshConnectionId()).isNull();
        assertThat(container.getGuacRdpConnectionId()).isNull();
        assertThat(container.getGuacVncConnectionId()).isNull();
    }

    @Test
    void tryAutoEnableWiresAllThreeProtocolsWhenAllPortsOpen() {
        Container container = containerWithAddress("10.0.3.5");
        when(credentialRepository.findByContainerAndType(eq(container), any())).thenReturn(Optional.empty());
        when(portProbe.isOpen(any(), anyInt())).thenReturn(true);
        when(guacamoleAdminClient.createSshConnectionPromptCredentials(anyString(), anyString(), anyInt())).thenReturn("conn-ssh");
        when(guacamoleAdminClient.createRdpConnectionPromptCredentials(anyString(), anyString(), anyInt(), anyString())).thenReturn("conn-rdp");
        when(guacamoleAdminClient.createVncConnectionPromptCredentials(anyString(), anyString(), anyInt())).thenReturn("conn-vnc");

        service.tryAutoEnable(container);

        assertThat(container.getGuacSshConnectionId()).isEqualTo("conn-ssh");
        assertThat(container.getGuacRdpConnectionId()).isEqualTo("conn-rdp");
        assertThat(container.getGuacVncConnectionId()).isEqualTo("conn-vnc");
    }
}
