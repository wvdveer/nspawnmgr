package com.nspawnmgr.service;

import com.nspawnmgr.cli.ContainerCliExecutor;
import com.nspawnmgr.cli.ScriptRunResult;
import com.nspawnmgr.config.PamAuthProperties;
import com.nspawnmgr.crypto.SecretEncryptionService;
import com.nspawnmgr.domain.Container;
import com.nspawnmgr.domain.ContainerCredential;
import com.nspawnmgr.domain.ContainerOutboundAllowlistEntry;
import com.nspawnmgr.domain.ContainerPamService;
import com.nspawnmgr.domain.ContainerState;
import com.nspawnmgr.domain.CredentialType;
import com.nspawnmgr.domain.PamAuthSource;
import com.nspawnmgr.domain.PamServiceCatalog;
import com.nspawnmgr.domain.PortMappingProtocol;
import com.nspawnmgr.guacamole.GuacamoleAdminClient;
import com.nspawnmgr.repository.ContainerCredentialRepository;
import com.nspawnmgr.repository.ContainerOutboundAllowlistRepository;
import com.nspawnmgr.repository.ContainerPamServiceRepository;
import com.nspawnmgr.repository.ContainerRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class PamCredentialAuthServiceTest {

    private ContainerRepository containerRepository;
    private ContainerPamServiceRepository pamServiceRepository;
    private ContainerCredentialRepository credentialRepository;
    private ContainerOutboundAllowlistRepository outboundAllowlistRepository;
    private ContainerCliExecutor cliExecutor;
    private GuacamoleAdminClient guacamoleAdminClient;
    private SecretEncryptionService secretEncryptionService;
    private PamCredentialAuthService service;

    @BeforeEach
    void setUp() {
        containerRepository = mock(ContainerRepository.class);
        pamServiceRepository = mock(ContainerPamServiceRepository.class);
        credentialRepository = mock(ContainerCredentialRepository.class);
        outboundAllowlistRepository = mock(ContainerOutboundAllowlistRepository.class);
        cliExecutor = mock(ContainerCliExecutor.class);
        guacamoleAdminClient = mock(GuacamoleAdminClient.class);
        secretEncryptionService = mock(SecretEncryptionService.class);
        when(cliExecutor.runScript(anyString(), anyString(), any(Duration.class))).thenReturn(new ScriptRunResult(0, List.of()));
        when(outboundAllowlistRepository.findByContainer(any())).thenReturn(List.of());
        // "" (never null - see ContainerCliExecutor#getInternalAddress's own javadoc) means most
        // tests that don't care about RDP connection reconciliation get a harmless no-op there;
        // tests that DO care override this explicitly.
        when(cliExecutor.getInternalAddress(anyString())).thenReturn("");
        PamAuthProperties properties = new PamAuthProperties("http://10.100.0.1:8080/nspawnmgr");
        service = new PamCredentialAuthService(containerRepository, pamServiceRepository, credentialRepository,
                outboundAllowlistRepository, cliExecutor, guacamoleAdminClient, secretEncryptionService, properties);
    }

    private Container runningContainer() {
        Container container = new Container();
        container.setId(1L);
        container.setName("hand-built-1");
        container.setState(ContainerState.RUNNING);
        // applyToContainer re-fetches with an eagerly-joined template (see its own comment) - stub
        // the fetch to return the same instance, since these tests don't care about the template
        // itself.
        when(containerRepository.findByIdWithTemplate(1L)).thenReturn(Optional.of(container));
        return container;
    }

    @Test
    void updateSettingsPersistsAndAppliesLiveWhenRunning() {
        Container container = runningContainer();
        when(credentialRepository.findByContainerAndType(container, CredentialType.RDP_PASSWORD))
                .thenReturn(Optional.of(new ContainerCredential()));

        service.updateSettings(container, PamAuthSource.RDP_PASSWORD, Set.of(PamServiceCatalog.XRDP_SESMAN, PamServiceCatalog.SSHD));

        assertThat(container.getPamAuthSource()).isEqualTo(PamAuthSource.RDP_PASSWORD);
        verify(containerRepository, times(2)).save(container); // once for source, once to mint the token
        verify(pamServiceRepository).deleteByContainer(container);
        ArgumentCaptor<ContainerPamService> captor = ArgumentCaptor.forClass(ContainerPamService.class);
        verify(pamServiceRepository, times(2)).save(captor.capture());
        assertThat(captor.getAllValues().stream().map(ContainerPamService::getServiceName))
                .containsExactlyInAnyOrder(PamServiceCatalog.XRDP_SESMAN, PamServiceCatalog.SSHD);
        verify(cliExecutor).runScript(eq("hand-built-1"), anyString(), any(Duration.class));
    }

    @Test
    void updateSettingsFlushesTheDeleteBeforeInsertingReplacementRows() {
        // Confirmed live (fed2, 2026-08-13): without an explicit flush between the delete and the
        // insert loop, Hibernate's default flush order runs every pending INSERT before any
        // pending DELETE regardless of call order - re-saving a service name that was already
        // configured (the common case) tried to insert before the old row was actually gone,
        // hitting uq_container_pam_services. Locks in delete -> flush -> insert.
        Container container = runningContainer();
        when(credentialRepository.findByContainerAndType(container, CredentialType.RDP_PASSWORD))
                .thenReturn(Optional.of(new ContainerCredential()));

        service.updateSettings(container, PamAuthSource.RDP_PASSWORD, Set.of(PamServiceCatalog.XRDP_SESMAN));

        org.mockito.InOrder order = org.mockito.Mockito.inOrder(pamServiceRepository);
        order.verify(pamServiceRepository).deleteByContainer(container);
        order.verify(pamServiceRepository).flush();
        order.verify(pamServiceRepository).save(any());
    }

    @Test
    void updateSettingsDoesNotApplyLiveWhenNotRunning() {
        Container container = runningContainer();
        container.setState(ContainerState.STOPPED);
        when(credentialRepository.findByContainerAndType(container, CredentialType.RDP_PASSWORD))
                .thenReturn(Optional.of(new ContainerCredential()));

        service.updateSettings(container, PamAuthSource.RDP_PASSWORD, Set.of(PamServiceCatalog.XRDP_SESMAN));

        verify(cliExecutor, never()).runScript(any(), any(), any());
    }

    @Test
    void updateSettingsRejectsSourceWithoutMatchingCredential() {
        Container container = runningContainer();
        when(credentialRepository.findByContainerAndType(container, CredentialType.VNC_PASSWORD)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.updateSettings(container, PamAuthSource.VNC_PASSWORD, Set.of(PamServiceCatalog.XRDP_SESMAN)))
                .isInstanceOf(IllegalStateException.class);

        verify(containerRepository, never()).save(any());
        verify(pamServiceRepository, never()).deleteByContainer(any());
    }

    @Test
    void updateSettingsRejectsUnknownServiceName() {
        Container container = runningContainer();
        when(credentialRepository.findByContainerAndType(container, CredentialType.RDP_PASSWORD))
                .thenReturn(Optional.of(new ContainerCredential()));

        assertThatThrownBy(() -> service.updateSettings(container, PamAuthSource.RDP_PASSWORD, Set.of("not-a-real-service")))
                .isInstanceOf(IllegalArgumentException.class);

        verify(containerRepository, never()).save(any());
    }

    @Test
    void updateSettingsAcceptsBackendSourceWithNoCredentialPrerequisite() {
        Container container = runningContainer();

        service.updateSettings(container, PamAuthSource.NSPAWNMGR_AUTH_BACKEND, Set.of());

        assertThat(container.getPamAuthSource()).isEqualTo(PamAuthSource.NSPAWNMGR_AUTH_BACKEND);
        verify(pamServiceRepository).deleteByContainer(container);
        verify(pamServiceRepository, never()).save(any());
        // No services enabled - applies live (empty config is still a real state to push, e.g.
        // clearing a previous config) but never touches the outbound allowlist.
        verify(cliExecutor).runScript(any(), any(), any());
        verify(outboundAllowlistRepository, never()).save(any());
    }

    @Test
    void enableDefaultForFedoraRdpEnablesXrdpSesmanOnlyAndApplies() {
        Container container = runningContainer();

        service.enableDefaultForFedoraRdp(container);

        assertThat(container.getPamAuthSource()).isEqualTo(PamAuthSource.RDP_PASSWORD);
        ArgumentCaptor<ContainerPamService> captor = ArgumentCaptor.forClass(ContainerPamService.class);
        verify(pamServiceRepository).save(captor.capture());
        assertThat(captor.getValue().getServiceName()).isEqualTo(PamServiceCatalog.XRDP_SESMAN);
        verify(cliExecutor).runScript(eq("hand-built-1"), anyString(), any(Duration.class));
    }

    @Test
    void enableDefaultForFedoraRdpIsANoOpWhenOwnerAlreadyConfiguredPamAuth() {
        // Confirmed live (fed1, 2026-08-13): the container row is editable in the UI as soon as
        // it's created, well before background provisioning reaches this call - an owner saving
        // their own PAM settings (even a value-identical choice) while provisioning is still
        // running is a real race, not hypothetical. Reproduced here: findByContainer already
        // returns a row (as if the owner's own updateSettings call already ran), so provisioning's
        // own default-enable must defer to it instead of blindly inserting and colliding.
        Container container = runningContainer();
        container.setPamAuthSource(PamAuthSource.NSPAWNMGR_AUTH_BACKEND);
        when(pamServiceRepository.findByContainer(container))
                .thenReturn(List.of(new ContainerPamService(container, PamServiceCatalog.XRDP_SESMAN)));

        service.enableDefaultForFedoraRdp(container);

        assertThat(container.getPamAuthSource()).isEqualTo(PamAuthSource.NSPAWNMGR_AUTH_BACKEND);
        verify(pamServiceRepository, never()).save(any());
        verify(containerRepository, never()).save(any());
        verify(cliExecutor, never()).runScript(any(), any(), any());
    }

    @Test
    void applyMintsPamAuthTokenOnlyOnce() {
        Container container = runningContainer();
        assertThat(container.getPamAuthToken()).isNull();
        // First call is enableDefaultForFedoraRdp's own no-op guard (must see "nothing configured
        // yet" to proceed) - every call after that reflects the row it just inserted.
        when(pamServiceRepository.findByContainer(container))
                .thenReturn(List.of())
                .thenReturn(List.of(new ContainerPamService(container, PamServiceCatalog.XRDP_SESMAN)));

        service.enableDefaultForFedoraRdp(container);
        String mintedToken = container.getPamAuthToken();
        assertThat(mintedToken).isNotBlank();

        service.reapplyIfConfigured(container);

        assertThat(container.getPamAuthToken()).isEqualTo(mintedToken);
        // Only the first apply() should have minted a token - containerRepository.save() was
        // called for it exactly once (plus once for enableDefaultForFedoraRdp's own source
        // persist), not a second time on the reapply.
        verify(containerRepository, times(2)).save(container);
    }

    @Test
    void reapplyIfConfiguredSkipsWhenNoServicesConfigured() {
        Container container = runningContainer();
        when(pamServiceRepository.findByContainer(container)).thenReturn(List.of());

        service.reapplyIfConfigured(container);

        verify(cliExecutor, never()).runScript(any(), any(), any());
    }

    @Test
    void reapplyIfConfiguredAppliesWhenServicesConfigured() {
        Container container = runningContainer();
        when(pamServiceRepository.findByContainer(container))
                .thenReturn(List.of(new ContainerPamService(container, PamServiceCatalog.XRDP_SESMAN)));

        service.reapplyIfConfigured(container);

        verify(cliExecutor).runScript(eq("hand-built-1"), anyString(), any(Duration.class));
    }

    @Test
    void applyAddsOutboundAllowlistEntryWhenServicesEnabled() {
        Container container = runningContainer();
        when(pamServiceRepository.findByContainer(container))
                .thenReturn(List.of())
                .thenReturn(List.of(new ContainerPamService(container, PamServiceCatalog.XRDP_SESMAN)));

        service.enableDefaultForFedoraRdp(container);

        ArgumentCaptor<ContainerOutboundAllowlistEntry> captor = ArgumentCaptor.forClass(ContainerOutboundAllowlistEntry.class);
        verify(outboundAllowlistRepository).save(captor.capture());
        assertThat(captor.getValue().getDestinationHost()).isEqualTo("10.100.0.1");
        assertThat(captor.getValue().getDestinationPort()).isEqualTo(8080);
        assertThat(captor.getValue().getProtocol()).isEqualTo(PortMappingProtocol.TCP);
    }

    @Test
    void applyAlsoOverridesTheAccountPhaseWithPamPermit() {
        // Confirmed live (fed1, 2026-08-13): the distro's own pam_unix.so account still reads
        // /etc/shadow via the same broken helper as auth, and hits the identical EACCES
        // regardless of which user - "pam_acct_mgmt failed: Authentication service cannot
        // retrieve authentication info" appeared for both the pre-existing admin account and a
        // freshly-created local account alike. Bypassing only the auth phase was never enough.
        Container container = runningContainer();
        when(credentialRepository.findByContainerAndType(container, CredentialType.RDP_PASSWORD))
                .thenReturn(Optional.of(new ContainerCredential()));
        // applyToContainer re-reads the persisted rows rather than trusting the just-passed set -
        // stub the post-save state it'll see.
        when(pamServiceRepository.findByContainer(container))
                .thenReturn(List.of(new ContainerPamService(container, PamServiceCatalog.XRDP_SESMAN)));

        service.updateSettings(container, PamAuthSource.RDP_PASSWORD, Set.of(PamServiceCatalog.XRDP_SESMAN));

        ArgumentCaptor<String> scriptCaptor = ArgumentCaptor.forClass(String.class);
        verify(cliExecutor).runScript(eq("hand-built-1"), scriptCaptor.capture(), any(Duration.class));
        assertThat(scriptCaptor.getValue())
                .contains("account [success=done default=die] pam_permit.so")
                .contains("auth [success=done default=die] pam_exec.so");
    }

    @Test
    void switchingRdpCheckToAuthBackendPutsTheRdpConnectionIntoPromptCredentialsMode() {
        // Confirmed live (fed1, 2026-08-14): Guacamole's own RDP connection is created once, at
        // provisioning time, with a FIXED username/password baked in - there's no interactive
        // login form for a real-credential connection, so it kept auto-submitting the
        // RDP_PASSWORD credential even after the owner switched the check to
        // NSPAWNMGR_AUTH_BACKEND, which naturally rejected it. nspawnmgr's own "Connect" button
        // simply stopped working. The fix: reconcile the connection's own credential mode to
        // match whatever xrdp-sesman is actually configured to check.
        Container container = runningContainer();
        container.setGuacRdpConnectionId("conn-rdp");
        when(cliExecutor.getInternalAddress("hand-built-1")).thenReturn("10.100.0.42");
        // reconcileRdpConnectionCredentials re-reads the persisted rows rather than trusting the
        // just-passed set - stub the post-save state it'll see (same pattern as
        // applyAlsoOverridesTheAccountPhaseWithPamPermit above).
        when(pamServiceRepository.findByContainer(container))
                .thenReturn(List.of(new ContainerPamService(container, PamServiceCatalog.XRDP_SESMAN)));

        service.updateSettings(container, PamAuthSource.NSPAWNMGR_AUTH_BACKEND, Set.of(PamServiceCatalog.XRDP_SESMAN));

        verify(guacamoleAdminClient).updateRdpConnectionPromptCredentials(
                eq("conn-rdp"), eq("hand-built-1-rdp"), eq("10.100.0.42"), eq(3389), anyString());
        verify(guacamoleAdminClient, never()).updateRdpConnection(any(), any(), any(), anyInt(), any(), any(), any());
    }

    @Test
    void switchingRdpCheckToVncPasswordPointsTheConnectionAtTheVncCredential() {
        Container container = runningContainer();
        container.setGuacRdpConnectionId("conn-rdp");
        when(cliExecutor.getInternalAddress("hand-built-1")).thenReturn("10.100.0.42");
        when(pamServiceRepository.findByContainer(container))
                .thenReturn(List.of(new ContainerPamService(container, PamServiceCatalog.XRDP_SESMAN)));
        ContainerCredential vncCredential = new ContainerCredential();
        vncCredential.setAccountName("admin");
        when(credentialRepository.findByContainerAndType(container, CredentialType.VNC_PASSWORD))
                .thenReturn(Optional.of(vncCredential));
        when(secretEncryptionService.decrypt(any(), any())).thenReturn("vnc-secret");

        service.updateSettings(container, PamAuthSource.VNC_PASSWORD, Set.of(PamServiceCatalog.XRDP_SESMAN));

        verify(guacamoleAdminClient).updateRdpConnection(
                eq("conn-rdp"), eq("hand-built-1-rdp"), eq("10.100.0.42"), eq(3389), eq("admin"), eq("vnc-secret"), anyString());
    }

    @Test
    void turningXrdpSesmanOffRevertsTheConnectionToRdpPasswordRegardlessOfSource() {
        // The check no longer applies to RDP at all once xrdp-sesman is unticked, so the
        // connection must go back to the one credential that's always actually correct for a
        // plain local-shadow login: the container's own RDP_PASSWORD.
        Container container = runningContainer();
        container.setGuacRdpConnectionId("conn-rdp");
        container.setPamAuthSource(PamAuthSource.NSPAWNMGR_AUTH_BACKEND);
        when(cliExecutor.getInternalAddress("hand-built-1")).thenReturn("10.100.0.42");
        // Persisted state has sshd only - xrdp-sesman was just unticked. Stubbed explicitly (not
        // left as Mockito's empty-list default) so this test genuinely exercises the "off" branch
        // rather than passing by coincidence of an unstubbed call.
        when(pamServiceRepository.findByContainer(container))
                .thenReturn(List.of(new ContainerPamService(container, PamServiceCatalog.SSHD)));
        ContainerCredential rdpCredential = new ContainerCredential();
        rdpCredential.setAccountName("admin");
        when(credentialRepository.findByContainerAndType(container, CredentialType.RDP_PASSWORD))
                .thenReturn(Optional.of(rdpCredential));
        when(secretEncryptionService.decrypt(any(), any())).thenReturn("rdp-secret");

        service.updateSettings(container, PamAuthSource.NSPAWNMGR_AUTH_BACKEND, Set.of(PamServiceCatalog.SSHD));

        verify(guacamoleAdminClient).updateRdpConnection(
                eq("conn-rdp"), eq("hand-built-1-rdp"), eq("10.100.0.42"), eq(3389), eq("admin"), eq("rdp-secret"), anyString());
        verify(guacamoleAdminClient, never()).updateRdpConnectionPromptCredentials(any(), any(), any(), anyInt(), any());
    }

    @Test
    void skipsRdpConnectionReconciliationWhenNoInternalAddressYet() {
        Container container = runningContainer();
        container.setGuacRdpConnectionId("conn-rdp");
        when(cliExecutor.getInternalAddress("hand-built-1")).thenReturn("");
        when(credentialRepository.findByContainerAndType(container, CredentialType.RDP_PASSWORD))
                .thenReturn(Optional.of(new ContainerCredential()));

        service.updateSettings(container, PamAuthSource.RDP_PASSWORD, Set.of(PamServiceCatalog.XRDP_SESMAN));

        verify(guacamoleAdminClient, never()).updateRdpConnection(any(), any(), any(), anyInt(), any(), any(), any());
        verify(guacamoleAdminClient, never()).updateRdpConnectionPromptCredentials(any(), any(), any(), anyInt(), any());
    }

    @Test
    void skipsRdpConnectionReconciliationWhenContainerHasNoRdpConnection() {
        Container container = runningContainer();
        // No setGuacRdpConnectionId call - stays null, e.g. RDP was never enabled on this container.
        when(cliExecutor.getInternalAddress("hand-built-1")).thenReturn("10.100.0.42");
        when(credentialRepository.findByContainerAndType(container, CredentialType.RDP_PASSWORD))
                .thenReturn(Optional.of(new ContainerCredential()));

        service.updateSettings(container, PamAuthSource.RDP_PASSWORD, Set.of(PamServiceCatalog.XRDP_SESMAN));

        verify(guacamoleAdminClient, never()).updateRdpConnection(any(), any(), any(), anyInt(), any(), any(), any());
    }

    @Test
    void applySkipsDuplicateOutboundAllowlistEntry() {
        Container container = runningContainer();
        when(pamServiceRepository.findByContainer(container))
                .thenReturn(List.of())
                .thenReturn(List.of(new ContainerPamService(container, PamServiceCatalog.XRDP_SESMAN)));
        ContainerOutboundAllowlistEntry existing = new ContainerOutboundAllowlistEntry(
                container, "10.100.0.1", 8080, PortMappingProtocol.TCP);
        when(outboundAllowlistRepository.findByContainer(container)).thenReturn(List.of(existing));

        service.enableDefaultForFedoraRdp(container);

        verify(outboundAllowlistRepository, never()).save(any());
    }
}
