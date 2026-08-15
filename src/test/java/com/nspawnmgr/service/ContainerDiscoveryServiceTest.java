package com.nspawnmgr.service;

import com.nspawnmgr.cli.ContainerCliExecutor;
import com.nspawnmgr.cli.MachineBootSettings;
import com.nspawnmgr.cli.MachineStatus;
import com.nspawnmgr.domain.Container;
import com.nspawnmgr.domain.ContainerKind;
import com.nspawnmgr.domain.ContainerState;
import com.nspawnmgr.domain.CredentialType;
import com.nspawnmgr.domain.Template;
import com.nspawnmgr.domain.User;
import com.nspawnmgr.repository.ContainerCredentialRepository;
import com.nspawnmgr.repository.ContainerRepository;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Regression coverage for the "Discover machines" admin action: it must only register images
 * machinectl reports that aren't already tracked in the DB (by name), must correctly map real
 * machinectl state into the new Container row, and must be safe to run repeatedly.
 */
class ContainerDiscoveryServiceTest {

    @Test
    void onlyRegistersUntrackedImagesWithCorrectStateAndOwner() {
        ContainerRepository containerRepository = mock(ContainerRepository.class);
        ContainerCredentialRepository containerCredentialRepository = mock(ContainerCredentialRepository.class);
        ContainerCliExecutor cliExecutor = mock(ContainerCliExecutor.class);
        ContainerAccessService accessService = mock(ContainerAccessService.class);
        TemplateService templateService = mock(TemplateService.class);
        ProvisioningService provisioningService = mock(ProvisioningService.class);

        Container alreadyKnown = new Container();
        alreadyKnown.setName("already-known");
        when(containerRepository.findAll()).thenReturn(List.of(alreadyKnown));
        when(containerRepository.save(any())).thenAnswer(invocation -> invocation.getArgument(0));
        when(templateService.registerExistingMinimal(any())).thenReturn(Optional.empty());

        when(cliExecutor.listMachineImageNames()).thenReturn(List.of("already-known", "hand-built-running", "hand-built-stopped"));
        when(cliExecutor.status("hand-built-running")).thenReturn(MachineStatus.RUNNING);
        when(cliExecutor.status("hand-built-stopped")).thenReturn(MachineStatus.STOPPED);
        when(cliExecutor.getInternalAddress("hand-built-running")).thenReturn("10.0.3.5");

        User admin = new User("admin-external-id");
        admin.setId(1L);

        ContainerDiscoveryService service = new ContainerDiscoveryService(containerRepository, containerCredentialRepository,
                cliExecutor, accessService, templateService, provisioningService, new SelfHostedBootstrapStatus(), "");
        List<Container> discovered = service.discover(admin);

        assertThat(discovered).extracting(Container::getName)
                .containsExactlyInAnyOrder("hand-built-running", "hand-built-stopped");
        assertThat(discovered).allSatisfy(c -> {
            assertThat(c.getKind()).isEqualTo(ContainerKind.MANAGED);
            assertThat(c.getTemplate()).isNull();
            assertThat(c.getOwner()).isEqualTo(admin);
            assertThat(c.isRdpEnabled()).isFalse();
        });

        Container running = discovered.stream().filter(c -> c.getName().equals("hand-built-running")).findFirst().orElseThrow();
        assertThat(running.getState()).isEqualTo(ContainerState.RUNNING);
        assertThat(running.getInternalAddress()).isEqualTo("10.0.3.5");

        Container stopped = discovered.stream().filter(c -> c.getName().equals("hand-built-stopped")).findFirst().orElseThrow();
        assertThat(stopped.getState()).isEqualTo(ContainerState.STOPPED);
        assertThat(stopped.getInternalAddress()).isNull();
    }

    @Test
    void skipsImagesThatVanishBetweenListingAndStatusCheck() {
        ContainerRepository containerRepository = mock(ContainerRepository.class);
        ContainerCredentialRepository containerCredentialRepository = mock(ContainerCredentialRepository.class);
        ContainerCliExecutor cliExecutor = mock(ContainerCliExecutor.class);
        ContainerAccessService accessService = mock(ContainerAccessService.class);
        TemplateService templateService = mock(TemplateService.class);
        ProvisioningService provisioningService = mock(ProvisioningService.class);

        when(containerRepository.findAll()).thenReturn(List.of());
        when(templateService.registerExistingMinimal(any())).thenReturn(Optional.empty());
        when(cliExecutor.listMachineImageNames()).thenReturn(List.of("gone-already"));
        when(cliExecutor.status("gone-already")).thenReturn(MachineStatus.NOT_FOUND);

        ContainerDiscoveryService service = new ContainerDiscoveryService(containerRepository, containerCredentialRepository,
                cliExecutor, accessService, templateService, provisioningService, new SelfHostedBootstrapStatus(), "");
        List<Container> discovered = service.discover(new User("admin-external-id"));

        assertThat(discovered).isEmpty();
    }

    /**
     * Regression test for a real bug: the self-hosted "nspawnmgr" app machine, already known as a
     * Container row (e.g. from an earlier discover() call, template already linked) but still
     * missing its managed SSH credential (e.g. that earlier attempt failed), must still be retried
     * on every subsequent discover() call - it's not part of the "newly discovered" loop at all
     * (already known, so machinectl-listing it is a no-op there), so only the reconciliation pass
     * ever gets another chance to provision it. It must also never fall through to
     * ContainerAccessService's generic prompt-credentials fallback - confirmed live, that fallback
     * firing even once left a "Login as:"-prompting connection wired up with no way to self-correct.
     */
    @Test
    void retriesManagedSshForAnAlreadyKnownSelfHostedInfrastructureContainer() {
        ContainerRepository containerRepository = mock(ContainerRepository.class);
        ContainerCredentialRepository containerCredentialRepository = mock(ContainerCredentialRepository.class);
        ContainerCliExecutor cliExecutor = mock(ContainerCliExecutor.class);
        ContainerAccessService accessService = mock(ContainerAccessService.class);
        TemplateService templateService = mock(TemplateService.class);
        ProvisioningService provisioningService = mock(ProvisioningService.class);

        Template debianMinimal = new Template();
        debianMinimal.setName("debian-minimal");

        Container nspawnmgrMachine = new Container();
        nspawnmgrMachine.setName("nspawnmgr");
        nspawnmgrMachine.setState(ContainerState.RUNNING);
        nspawnmgrMachine.setTemplate(debianMinimal);

        when(containerRepository.findAll()).thenReturn(List.of(nspawnmgrMachine));
        when(containerRepository.findAllWithTemplate()).thenReturn(List.of(nspawnmgrMachine));
        when(templateService.registerExistingMinimal(any())).thenReturn(Optional.of(debianMinimal));
        when(containerCredentialRepository.findByContainerAndType(nspawnmgrMachine, CredentialType.SSH_KEY))
                .thenReturn(Optional.empty());
        when(cliExecutor.listMachineImageNames()).thenReturn(List.of("nspawnmgr"));

        ContainerDiscoveryService service = new ContainerDiscoveryService(containerRepository, containerCredentialRepository,
                cliExecutor, accessService, templateService, provisioningService, new SelfHostedBootstrapStatus(), "");
        service.discover(new User("admin-external-id"));

        verify(provisioningService).provisionSshForExistingContainer(nspawnmgrMachine, null);
        verify(accessService, never()).tryAutoEnable(any());
        // Regression check for a real bug: a plain findAll() here returns containers with a lazy
        // template proxy that throws "could not initialize proxy - no Session" the moment
        // provisionSshForExistingContainer's own separate REQUIRES_NEW sub-transaction tries to
        // read template.isSshPreinstalled(), since findAll()'s own loading session has already
        // closed by then (confirmed live) - the reconciliation pass must fetch template eagerly.
        verify(containerRepository).findAllWithTemplate();
    }

    /**
     * Regression test for the boot-settings feature: the self-hosted "nspawnmgr" app machine and its
     * database machine must both be set to auto-start when the host boots, and "nspawnmgr" must be
     * set to require the database machine already started - all read/written via a live host query
     * (never the database), and only when the host doesn't already report the setting.
     */
    @Test
    void enablesAutoStartAndRequiresDatabaseMachineForSelfHostedInfrastructure() {
        ContainerRepository containerRepository = mock(ContainerRepository.class);
        ContainerCredentialRepository containerCredentialRepository = mock(ContainerCredentialRepository.class);
        ContainerCliExecutor cliExecutor = mock(ContainerCliExecutor.class);
        ContainerAccessService accessService = mock(ContainerAccessService.class);
        TemplateService templateService = mock(TemplateService.class);
        ProvisioningService provisioningService = mock(ProvisioningService.class);

        Template debianMinimal = new Template();
        debianMinimal.setName("debian-minimal");

        Container nspawnmgrMachine = new Container();
        nspawnmgrMachine.setName("nspawnmgr");
        nspawnmgrMachine.setState(ContainerState.RUNNING);
        nspawnmgrMachine.setTemplate(debianMinimal);

        Container dbMachine = new Container();
        dbMachine.setName("postgresdb");
        dbMachine.setState(ContainerState.RUNNING);
        dbMachine.setTemplate(debianMinimal);
        dbMachine.setInternalAddress("10.0.3.9");

        when(containerRepository.findAll()).thenReturn(List.of(nspawnmgrMachine, dbMachine));
        when(containerRepository.findAllWithTemplate()).thenReturn(List.of(nspawnmgrMachine, dbMachine));
        when(templateService.registerExistingMinimal(any())).thenReturn(Optional.of(debianMinimal));
        when(containerCredentialRepository.findByContainerAndType(any(), any())).thenReturn(Optional.empty());
        when(cliExecutor.listMachineImageNames()).thenReturn(List.of("nspawnmgr", "postgresdb"));
        when(cliExecutor.getBootSettings(any())).thenReturn(new MachineBootSettings(false, null));

        ContainerDiscoveryService service = new ContainerDiscoveryService(containerRepository, containerCredentialRepository,
                cliExecutor, accessService, templateService, provisioningService, new SelfHostedBootstrapStatus(),
                "jdbc:postgresql://10.0.3.9:5432/nspawnmgr");
        service.discover(new User("admin-external-id"));

        verify(cliExecutor).setAutoStart("nspawnmgr", true);
        verify(cliExecutor).setAutoStart("postgresdb", true);
        verify(cliExecutor).setRequiresMachine("nspawnmgr", "postgresdb");
    }

    /**
     * Regression test for the "register the machines eagerly from the DB setup wizard" feature:
     * {@code reconcileSelfHostedInfrastructureNow()} (the new @Scheduled entry point, run without
     * any acting admin since the wizard now creates the owning User/Container rows directly) must
     * latch SelfHostedBootstrapStatus done once a pass finds both self-hosted machines already
     * fully set up - this is what lets the wizard's own progress page stop polling and redirect.
     */
    @Test
    void marksBootstrapStatusDoneOnceBothMachinesAreFullySettled() {
        ContainerRepository containerRepository = mock(ContainerRepository.class);
        ContainerCredentialRepository containerCredentialRepository = mock(ContainerCredentialRepository.class);
        ContainerCliExecutor cliExecutor = mock(ContainerCliExecutor.class);
        ContainerAccessService accessService = mock(ContainerAccessService.class);
        TemplateService templateService = mock(TemplateService.class);
        ProvisioningService provisioningService = mock(ProvisioningService.class);
        SelfHostedBootstrapStatus bootstrapStatus = new SelfHostedBootstrapStatus();

        Template debianMinimal = new Template();
        debianMinimal.setName("debian-minimal");

        Container nspawnmgrMachine = new Container();
        nspawnmgrMachine.setName("nspawnmgr");
        nspawnmgrMachine.setState(ContainerState.RUNNING);
        nspawnmgrMachine.setTemplate(debianMinimal);

        Container dbMachine = new Container();
        dbMachine.setName("postgresdb");
        dbMachine.setState(ContainerState.RUNNING);
        dbMachine.setTemplate(debianMinimal);
        dbMachine.setInternalAddress("10.0.3.9");

        when(containerRepository.findAllWithTemplate()).thenReturn(List.of(nspawnmgrMachine, dbMachine));
        when(templateService.registerExistingMinimal(any())).thenReturn(Optional.of(debianMinimal));
        when(containerCredentialRepository.findByContainerAndType(any(), any())).thenReturn(Optional.of(new com.nspawnmgr.domain.ContainerCredential()));
        when(cliExecutor.getBootSettings("nspawnmgr")).thenReturn(new MachineBootSettings(true, "postgresdb"));
        when(cliExecutor.getBootSettings("postgresdb")).thenReturn(new MachineBootSettings(true, null));

        ContainerDiscoveryService service = new ContainerDiscoveryService(containerRepository, containerCredentialRepository,
                cliExecutor, accessService, templateService, provisioningService, bootstrapStatus,
                "jdbc:postgresql://10.0.3.9:5432/nspawnmgr");
        service.reconcileSelfHostedInfrastructureNow();

        assertThat(bootstrapStatus.isDone()).isTrue();
        assertThat(bootstrapStatus.log()).contains("Self-hosted machine setup complete.");
        verify(cliExecutor, never()).setAutoStart(any(), anyBoolean());
        verify(cliExecutor, never()).setRequiresMachine(any(), any());
    }

    @Test
    void doesNotMarkBootstrapStatusDoneWhileSshProvisioningIsStillMissing() {
        ContainerRepository containerRepository = mock(ContainerRepository.class);
        ContainerCredentialRepository containerCredentialRepository = mock(ContainerCredentialRepository.class);
        ContainerCliExecutor cliExecutor = mock(ContainerCliExecutor.class);
        ContainerAccessService accessService = mock(ContainerAccessService.class);
        TemplateService templateService = mock(TemplateService.class);
        ProvisioningService provisioningService = mock(ProvisioningService.class);
        SelfHostedBootstrapStatus bootstrapStatus = new SelfHostedBootstrapStatus();

        Template debianMinimal = new Template();
        debianMinimal.setName("debian-minimal");

        Container nspawnmgrMachine = new Container();
        nspawnmgrMachine.setName("nspawnmgr");
        nspawnmgrMachine.setState(ContainerState.RUNNING);
        nspawnmgrMachine.setTemplate(debianMinimal);

        when(containerRepository.findAllWithTemplate()).thenReturn(List.of(nspawnmgrMachine));
        when(templateService.registerExistingMinimal(any())).thenReturn(Optional.of(debianMinimal));
        when(containerCredentialRepository.findByContainerAndType(any(), any())).thenReturn(Optional.empty());
        doThrow(new RuntimeException("SSH hiccup")).when(provisioningService).provisionSshForExistingContainer(any(), any());

        ContainerDiscoveryService service = new ContainerDiscoveryService(containerRepository, containerCredentialRepository,
                cliExecutor, accessService, templateService, provisioningService, bootstrapStatus, "");
        service.reconcileSelfHostedInfrastructureNow();

        assertThat(bootstrapStatus.isDone()).isFalse();
    }

    /**
     * A newly-discovered self-hosted machine (e.g. a pre-existing install upgraded to a version
     * that predates DatabaseSetupWizardServlet setting this directly) must get its container list
     * "Description" set the moment discover()'s own main loop registers it, same as the template
     * link right next to it.
     */
    @Test
    void setsDescriptionForANewlyDiscoveredSelfHostedMachine() {
        ContainerRepository containerRepository = mock(ContainerRepository.class);
        ContainerCredentialRepository containerCredentialRepository = mock(ContainerCredentialRepository.class);
        ContainerCliExecutor cliExecutor = mock(ContainerCliExecutor.class);
        ContainerAccessService accessService = mock(ContainerAccessService.class);
        TemplateService templateService = mock(TemplateService.class);
        ProvisioningService provisioningService = mock(ProvisioningService.class);

        when(containerRepository.findAll()).thenReturn(List.of());
        when(containerRepository.save(any())).thenAnswer(invocation -> invocation.getArgument(0));
        when(templateService.registerExistingMinimal(any())).thenReturn(Optional.empty());
        when(cliExecutor.listMachineImageNames()).thenReturn(List.of("nspawnmgr", "postgresdb"));
        when(cliExecutor.status(any())).thenReturn(MachineStatus.STOPPED);

        ContainerDiscoveryService service = new ContainerDiscoveryService(containerRepository, containerCredentialRepository,
                cliExecutor, accessService, templateService, provisioningService, new SelfHostedBootstrapStatus(),
                "jdbc:postgresql://10.0.3.9:5432/nspawnmgr");
        List<Container> discovered = service.discover(new User("admin-external-id"));

        Container nspawnmgrMachine = discovered.stream().filter(c -> c.getName().equals("nspawnmgr")).findFirst().orElseThrow();
        assertThat(nspawnmgrMachine.getDescription()).isEqualTo("Virtual machine management");
        // postgresdb is STOPPED here, so its internal address (what isSelfHostedInfrastructure
        // matches the database machine by) never gets resolved - only nspawnmgr is identified by
        // its own fixed name regardless of state, so only its description gets set in this pass.
        Container dbMachine = discovered.stream().filter(c -> c.getName().equals("postgresdb")).findFirst().orElseThrow();
        assertThat(dbMachine.getDescription()).isNull();
    }

    /**
     * Regression coverage for backfilling both self-hosted machines' descriptions on an install
     * that already had them registered before this feature existed (e.g. via the DB setup wizard's
     * own direct registration, predating this change) - must self-heal within a normal
     * reconciliation pass, no reinstall/manual fix needed.
     */
    @Test
    void backfillsMissingDescriptionsForAlreadyRegisteredSelfHostedMachines() {
        ContainerRepository containerRepository = mock(ContainerRepository.class);
        ContainerCredentialRepository containerCredentialRepository = mock(ContainerCredentialRepository.class);
        ContainerCliExecutor cliExecutor = mock(ContainerCliExecutor.class);
        ContainerAccessService accessService = mock(ContainerAccessService.class);
        TemplateService templateService = mock(TemplateService.class);
        ProvisioningService provisioningService = mock(ProvisioningService.class);

        Template debianMinimal = new Template();
        debianMinimal.setName("debian-minimal");

        Container nspawnmgrMachine = new Container();
        nspawnmgrMachine.setName("nspawnmgr");
        nspawnmgrMachine.setState(ContainerState.RUNNING);
        nspawnmgrMachine.setTemplate(debianMinimal);

        Container dbMachine = new Container();
        dbMachine.setName("postgresdb");
        dbMachine.setState(ContainerState.RUNNING);
        dbMachine.setTemplate(debianMinimal);
        dbMachine.setInternalAddress("10.0.3.9");

        when(containerRepository.save(any())).thenAnswer(invocation -> invocation.getArgument(0));
        when(containerRepository.findAllWithTemplate()).thenReturn(List.of(nspawnmgrMachine, dbMachine));
        when(templateService.registerExistingMinimal(any())).thenReturn(Optional.of(debianMinimal));
        when(containerCredentialRepository.findByContainerAndType(any(), any())).thenReturn(Optional.empty());
        when(cliExecutor.getBootSettings(any())).thenReturn(new MachineBootSettings(true, "postgresdb"));

        ContainerDiscoveryService service = new ContainerDiscoveryService(containerRepository, containerCredentialRepository,
                cliExecutor, accessService, templateService, provisioningService, new SelfHostedBootstrapStatus(),
                "jdbc:postgresql://10.0.3.9:5432/nspawnmgr");
        service.reconcileSelfHostedInfrastructureNow();

        assertThat(nspawnmgrMachine.getDescription()).isEqualTo("Virtual machine management");
        assertThat(dbMachine.getDescription()).isEqualTo("Database server");
    }
}
