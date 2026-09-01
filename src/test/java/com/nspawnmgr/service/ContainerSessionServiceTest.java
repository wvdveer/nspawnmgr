package com.nspawnmgr.service;

import com.nspawnmgr.cli.ContainerCliExecutor;
import com.nspawnmgr.domain.Container;
import com.nspawnmgr.domain.ContainerCredential;
import com.nspawnmgr.domain.CredentialType;
import com.nspawnmgr.domain.User;
import com.nspawnmgr.guacamole.GuacamoleAdminClient;
import com.nspawnmgr.guacamole.GuacamoleSessionService;
import com.nspawnmgr.repository.ContainerCredentialRepository;
import org.junit.jupiter.api.Test;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Regression coverage for connecting to an EXTERNAL host by hostname: since Guacamole's own
 * SSH/RDP/VNC client runs inside the self-hosted nspawnmgr container (no visibility into a
 * LAN-local hostname's own resolution), ContainerSessionService must re-resolve the host's own
 * hostname on the HOST (via ContainerCliExecutor.resolveHostname) and push the resolved address
 * into the Guacamole connection before minting a session URL - every connect, not just once.
 */
class ContainerSessionServiceTest {

    private ShareService shareService;
    private GuacamoleSessionService guacamoleSessionService;
    private GuacamoleAdminClient guacamoleAdminClient;
    private ContainerCliExecutor cliExecutor;
    private ContainerCredentialRepository credentialRepository;
    private ProvisioningService provisioningService;
    private ContainerSessionService service;

    private void setUp() {
        shareService = mock(ShareService.class);
        guacamoleSessionService = mock(GuacamoleSessionService.class);
        guacamoleAdminClient = mock(GuacamoleAdminClient.class);
        cliExecutor = mock(ContainerCliExecutor.class);
        credentialRepository = mock(ContainerCredentialRepository.class);
        provisioningService = mock(ProvisioningService.class);
        service = new ContainerSessionService(shareService, guacamoleSessionService, guacamoleAdminClient, cliExecutor,
                credentialRepository, provisioningService, TestUserMessages.create());

        User user = new User("external-id");
        user.setGuacamoleUsername("someuser");
        when(shareService.guacamolePassword(any())).thenReturn("pw");
        when(guacamoleSessionService.buildSessionUrl(anyString(), anyString(), anyString(), anyString()))
                .thenReturn("https://example/session");
    }

    @Test
    void resolvesHostnameAndUpdatesConnectionForAnExternalHost() {
        setUp();
        User owner = new User("external-id");
        owner.setGuacamoleUsername("someuser");
        Container host = Container.external("yoga", owner, "yoga");
        host.setGuacSshConnectionId("conn-1");
        host.setExternalSshPort(22);
        when(cliExecutor.resolveHostname("yoga")).thenReturn("192.168.1.50");

        String url = service.startSshSession(host, owner, "https://nspawnmgr.example");

        assertThat(url).isEqualTo("https://example/session");
        verify(cliExecutor).resolveHostname("yoga");
        verify(guacamoleAdminClient).updateSshConnectionPromptCredentials("conn-1", "yoga-ssh", "192.168.1.50", 22);
    }

    @Test
    void neverResolvesHostnameForAManagedContainer() {
        setUp();
        User owner = new User("external-id");
        owner.setGuacamoleUsername("someuser");
        Container container = new Container();
        container.setName("b1");
        container.setOwner(owner);
        container.setGuacSshConnectionId("conn-2");

        service.startSshSession(container, owner, "https://nspawnmgr.example");

        verify(cliExecutor, never()).resolveHostname(anyString());
        verify(guacamoleAdminClient, never()).updateSshConnectionPromptCredentials(anyString(), anyString(), anyString(), anyInt());
    }

    @Test
    void usesGrantAccessesReturnedUsernameForAUsersVeryFirstSession() {
        // Confirmed live (2026-08-14): ShareService.ensureGuacamoleUser mutates a separately
        // re-fetched, row-locked User copy for a brand-new account, never the caller's own `user`
        // reference - reading user.getGuacamoleUsername() straight back off that still-null
        // reference NPE'd inside GuacamoleSessionService's token cache
        // (ConcurrentHashMap.compute(null, ...)) for every user's first-ever session. This
        // reproduces that exact starting state: `owner` has no guacamoleUsername at all, and only
        // grantAccess's own return value carries the resolved one.
        setUp();
        User owner = new User("external-id");
        assertThat(owner.getGuacamoleUsername()).isNull();
        Container container = new Container();
        container.setName("b1");
        container.setOwner(owner);
        container.setGuacSshConnectionId("conn-2");
        when(shareService.grantAccess(container, owner)).thenReturn("someuser");

        service.startSshSession(container, owner, "https://nspawnmgr.example");

        verify(guacamoleSessionService).buildSessionUrl(
                org.mockito.ArgumentMatchers.eq("someuser"), anyString(), anyString(), anyString());
    }

    @Test
    void rdpAndVncAlsoResolveForAnExternalHost() {
        setUp();
        User owner = new User("external-id");
        owner.setGuacamoleUsername("someuser");
        Container host = Container.external("yoga", owner, "yoga");
        host.setRdpEnabled(true);
        host.setGuacRdpConnectionId("conn-rdp");
        host.setExternalRdpPort(3389);
        host.setVncEnabled(true);
        host.setGuacVncConnectionId("conn-vnc");
        host.setExternalVncPort(5900);
        when(cliExecutor.resolveHostname("yoga")).thenReturn("192.168.1.50");

        service.startRdpSession(host, owner, "https://nspawnmgr.example");
        service.startVncSession(host, owner, "https://nspawnmgr.example");

        verify(guacamoleAdminClient).updateRdpConnectionPromptCredentials("conn-rdp", "yoga-rdp", "192.168.1.50", 3389, "any");
        verify(guacamoleAdminClient).updateVncConnectionPromptCredentials("conn-vnc", "yoga-vnc", "192.168.1.50", 5900);
        verify(provisioningService, never()).ensureVncServerRunning(any());
        verify(provisioningService, never()).ensureLingerEnabled(any());
    }

    @Test
    void ensuresLingerEnabledForAManagedContainerNspawnmgrProvisionedRdpOn() {
        setUp();
        User owner = new User("external-id");
        owner.setGuacamoleUsername("someuser");
        Container container = new Container();
        container.setName("b1");
        container.setOwner(owner);
        container.setRdpEnabled(true);
        container.setGuacRdpConnectionId("conn-rdp");
        when(credentialRepository.findByContainerAndType(container, CredentialType.RDP_PASSWORD))
                .thenReturn(Optional.of(new ContainerCredential()));

        service.startRdpSession(container, owner, "https://nspawnmgr.example");

        verify(provisioningService).ensureLingerEnabled(container);
    }

    @Test
    void doesNotEnsureLingerEnabledForAPromptCredentialsRdpConnection() {
        setUp();
        User owner = new User("external-id");
        owner.setGuacamoleUsername("someuser");
        Container container = new Container();
        container.setName("b1");
        container.setOwner(owner);
        container.setRdpEnabled(true);
        container.setGuacRdpConnectionId("conn-rdp");
        when(credentialRepository.findByContainerAndType(container, CredentialType.RDP_PASSWORD))
                .thenReturn(Optional.empty());

        service.startRdpSession(container, owner, "https://nspawnmgr.example");

        verify(provisioningService, never()).ensureLingerEnabled(any());
    }

    @Test
    void ensuresVncServerRunningForAManagedContainerNspawnmgrProvisionedVncOn() {
        setUp();
        User owner = new User("external-id");
        owner.setGuacamoleUsername("someuser");
        Container container = new Container();
        container.setName("b1");
        container.setOwner(owner);
        container.setVncEnabled(true);
        container.setGuacVncConnectionId("conn-vnc");
        when(credentialRepository.findByContainerAndType(container, CredentialType.VNC_PASSWORD))
                .thenReturn(Optional.of(new ContainerCredential()));

        service.startVncSession(container, owner, "https://nspawnmgr.example");

        verify(provisioningService).ensureVncServerRunning(container);
    }

    @Test
    void doesNotEnsureVncServerRunningForAPromptCredentialsConnection() {
        // nspawnmgr never installed VNC on this container (e.g. a discovered machine's pre-existing
        // service, wired up via ContainerAccessService.enableVnc) - no ContainerCredential, so it
        // has no business restarting a VNC server it doesn't control/know the shape of.
        setUp();
        User owner = new User("external-id");
        owner.setGuacamoleUsername("someuser");
        Container container = new Container();
        container.setName("b1");
        container.setOwner(owner);
        container.setVncEnabled(true);
        container.setGuacVncConnectionId("conn-vnc");
        when(credentialRepository.findByContainerAndType(container, CredentialType.VNC_PASSWORD))
                .thenReturn(Optional.empty());

        service.startVncSession(container, owner, "https://nspawnmgr.example");

        verify(provisioningService, never()).ensureVncServerRunning(any());
    }
}
