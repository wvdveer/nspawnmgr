package com.nspawnmgr.service;

import com.nspawnmgr.domain.Container;
import com.nspawnmgr.domain.ContainerKind;
import com.nspawnmgr.domain.User;
import com.nspawnmgr.guacamole.GuacamoleAdminClient;
import com.nspawnmgr.repository.ContainerRepository;
import com.nspawnmgr.repository.UserRepository;
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

class HostServiceTest {

    private ContainerRepository containerRepository;
    private UserRepository userRepository;
    private GuacamoleAdminClient guacamoleAdminClient;
    private ShareService shareService;
    private HostService service;

    @BeforeEach
    void setUp() {
        containerRepository = mock(ContainerRepository.class);
        userRepository = mock(UserRepository.class);
        guacamoleAdminClient = mock(GuacamoleAdminClient.class);
        shareService = mock(ShareService.class);
        service = new HostService(containerRepository, userRepository, guacamoleAdminClient, shareService, TestUserMessages.create());
        when(containerRepository.save(any())).thenAnswer(invocation -> invocation.getArgument(0));
    }

    private User owner() {
        User user = new User("ext-id");
        user.setId(1L);
        user.setUsername("erin");
        return user;
    }

    @Test
    void createWithAllProtocolsEnabledCreatesThreeConnections() {
        when(containerRepository.findByName("h1")).thenReturn(Optional.empty());
        when(userRepository.findByUsernameIgnoreCase("erin")).thenReturn(Optional.of(owner()));
        when(guacamoleAdminClient.createSshConnectionPromptCredentials(anyString(), anyString(), anyInt())).thenReturn("ssh-1");
        when(guacamoleAdminClient.createRdpConnectionPromptCredentials(anyString(), anyString(), anyInt(), anyString())).thenReturn("rdp-1");
        when(guacamoleAdminClient.createVncConnectionPromptCredentials(anyString(), anyString(), anyInt())).thenReturn("vnc-1");

        Container host = service.create("h1", "10.0.0.5", "erin", true, 22, true, 3389, true, 5900);

        assertThat(host.getKind()).isEqualTo(ContainerKind.EXTERNAL);
        assertThat(host.getGuacSshConnectionId()).isEqualTo("ssh-1");
        assertThat(host.getGuacRdpConnectionId()).isEqualTo("rdp-1");
        assertThat(host.getGuacVncConnectionId()).isEqualTo("vnc-1");
        assertThat(host.getExternalSshPort()).isEqualTo(22);
        assertThat(host.getExternalRdpPort()).isEqualTo(3389);
        assertThat(host.getExternalVncPort()).isEqualTo(5900);
        assertThat(host.isRdpEnabled()).isTrue();
        assertThat(host.isVncEnabled()).isTrue();
        verify(guacamoleAdminClient).createSshConnectionPromptCredentials(eq("h1-ssh"), eq("10.0.0.5"), eq(22));
        verify(guacamoleAdminClient).createRdpConnectionPromptCredentials(eq("h1-rdp"), eq("10.0.0.5"), eq(3389), eq("any"));
        verify(guacamoleAdminClient).createVncConnectionPromptCredentials(eq("h1-vnc"), eq("10.0.0.5"), eq(5900));
        verify(shareService).grantAccess(eq(host), any(User.class));
    }

    @Test
    void createWithNoProtocolsEnabledCreatesNoConnections() {
        when(containerRepository.findByName("h1")).thenReturn(Optional.empty());
        when(userRepository.findByUsernameIgnoreCase("erin")).thenReturn(Optional.of(owner()));

        Container host = service.create("h1", "10.0.0.5", "erin", false, 22, false, 3389, false, 5900);

        assertThat(host.getGuacSshConnectionId()).isNull();
        assertThat(host.getExternalSshPort()).isNull();
        assertThat(host.getExternalRdpPort()).isNull();
        assertThat(host.getExternalVncPort()).isNull();
        verify(guacamoleAdminClient, never()).createSshConnectionPromptCredentials(any(), any(), anyInt());
        verify(guacamoleAdminClient, never()).createRdpConnectionPromptCredentials(any(), any(), anyInt(), any());
        verify(guacamoleAdminClient, never()).createVncConnectionPromptCredentials(any(), any(), anyInt());
    }

    @Test
    void createRejectsDuplicateName() {
        Container existing = new Container();
        existing.setId(5L);
        when(containerRepository.findByName("h1")).thenReturn(Optional.of(existing));

        assertThatThrownBy(() -> service.create("h1", "10.0.0.5", "erin", false, 22, false, 3389, false, 5900))
                .isInstanceOf(IllegalArgumentException.class);
        verify(userRepository, never()).findByUsernameIgnoreCase(any());
    }

    @Test
    void createRejectsUnknownOwner() {
        when(containerRepository.findByName("h1")).thenReturn(Optional.empty());
        when(userRepository.findByUsernameIgnoreCase("nobody")).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.create("h1", "10.0.0.5", "nobody", false, 22, false, 3389, false, 5900))
                .isInstanceOf(IllegalArgumentException.class);
    }

    private Container existingHost() {
        Container host = Container.external("h1", owner(), "10.0.0.5");
        host.setId(9L);
        host.setGuacSshConnectionId("ssh-1");
        when(containerRepository.findByIdWithTemplate(9L)).thenReturn(Optional.of(host));
        when(containerRepository.findByName("h1")).thenReturn(Optional.of(host));
        return host;
    }

    @Test
    void updateTogglingProtocolOffDeletesConnection() {
        Container host = existingHost();
        when(userRepository.findByUsernameIgnoreCase("erin")).thenReturn(Optional.of(owner()));

        Container updated = service.update(9L, "h1", "10.0.0.5", "erin", false, 22, false, 3389, false, 5900);

        assertThat(updated.getGuacSshConnectionId()).isNull();
        verify(guacamoleAdminClient).deleteConnection("ssh-1");
    }

    @Test
    void updateTogglingProtocolOnCreatesConnection() {
        Container host = existingHost();
        when(userRepository.findByUsernameIgnoreCase("erin")).thenReturn(Optional.of(owner()));
        when(guacamoleAdminClient.createRdpConnectionPromptCredentials(anyString(), anyString(), anyInt(), anyString())).thenReturn("rdp-new");

        Container updated = service.update(9L, "h1", "10.0.0.5", "erin", true, 22, true, 3389, false, 5900);

        assertThat(updated.getGuacRdpConnectionId()).isEqualTo("rdp-new");
        verify(guacamoleAdminClient).createRdpConnectionPromptCredentials(eq("h1-rdp"), eq("10.0.0.5"), eq(3389), eq("any"));
    }

    @Test
    void updateExistingEnabledProtocolCallsUpdateNotCreate() {
        Container host = existingHost();
        when(userRepository.findByUsernameIgnoreCase("erin")).thenReturn(Optional.of(owner()));

        service.update(9L, "h1", "10.0.0.9", "erin", true, 2222, false, 3389, false, 5900);

        verify(guacamoleAdminClient).updateSshConnectionPromptCredentials("ssh-1", "h1-ssh", "10.0.0.9", 2222);
        verify(guacamoleAdminClient, never()).createSshConnectionPromptCredentials(any(), any(), anyInt());
    }

    @Test
    void deleteRemovesAllConnectionsSharesAndRow() {
        Container host = Container.external("h1", owner(), "10.0.0.5");
        host.setId(9L);
        host.setGuacSshConnectionId("ssh-1");
        host.setGuacRdpConnectionId("rdp-1");
        host.setGuacVncConnectionId("vnc-1");
        when(containerRepository.findByIdWithTemplate(9L)).thenReturn(Optional.of(host));

        service.delete(9L);

        verify(guacamoleAdminClient).deleteConnection("ssh-1");
        verify(guacamoleAdminClient).deleteConnection("rdp-1");
        verify(guacamoleAdminClient).deleteConnection("vnc-1");
        verify(shareService).revokeAllForContainer(host);
        verify(containerRepository).delete(host);
    }
}
