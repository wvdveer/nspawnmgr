package com.nspawnmgr.service;

import com.nspawnmgr.cli.ContainerCliExecutor;
import com.nspawnmgr.cli.ContainerFilesystemProvisioner;
import com.nspawnmgr.cli.ContainerIsoMounter;
import com.nspawnmgr.cli.ContainerOutboundAccessManager;
import com.nspawnmgr.cli.MachineStatus;
import com.nspawnmgr.crypto.SecretEncryptionService;
import com.nspawnmgr.domain.CachedPackage;
import com.nspawnmgr.domain.Container;
import com.nspawnmgr.domain.ContainerBackend;
import com.nspawnmgr.domain.ContainerKind;
import com.nspawnmgr.domain.ContainerState;
import com.nspawnmgr.domain.PackageManager;
import com.nspawnmgr.domain.User;
import com.nspawnmgr.guacamole.GuacamoleAdminClient;
import com.nspawnmgr.repository.ContainerCredentialRepository;
import com.nspawnmgr.repository.ContainerOutboundAllowlistRepository;
import com.nspawnmgr.repository.ContainerPortMappingRepository;
import com.nspawnmgr.repository.ContainerRepository;
import com.nspawnmgr.repository.UserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ContainerLifecycleServiceTest {

    private ContainerRepository containerRepository;
    private ContainerPortMappingRepository portMappingRepository;
    private ContainerFilesystemProvisioner filesystemProvisioner;
    private ContainerIsoMounter isoMounter;
    private PackageCacheService packageCacheService;
    private ContainerCliExecutor cliExecutor;
    private GuacamoleAdminClient guacamoleAdminClient;
    private ShareService shareService;
    private ContainerLifecycleService service;

    @BeforeEach
    void setUp() {
        containerRepository = mock(ContainerRepository.class);
        portMappingRepository = mock(ContainerPortMappingRepository.class);
        filesystemProvisioner = mock(ContainerFilesystemProvisioner.class);
        isoMounter = mock(ContainerIsoMounter.class);
        packageCacheService = mock(PackageCacheService.class);
        cliExecutor = mock(ContainerCliExecutor.class);
        guacamoleAdminClient = mock(GuacamoleAdminClient.class);
        shareService = mock(ShareService.class);
        when(containerRepository.save(any())).thenAnswer(invocation -> invocation.getArgument(0));
        when(portMappingRepository.findByContainer(any())).thenReturn(List.of());

        service = new ContainerLifecycleService(containerRepository,
                mock(ContainerCredentialRepository.class),
                mock(ContainerOutboundAllowlistRepository.class),
                portMappingRepository,
                mock(UserRepository.class),
                cliExecutor,
                filesystemProvisioner,
                mock(ContainerOutboundAccessManager.class),
                isoMounter,
                packageCacheService,
                guacamoleAdminClient,
                shareService,
                mock(SecretEncryptionService.class));
    }

    private Container managedContainer(ContainerState state) {
        Container container = new Container();
        container.setId(1L);
        container.setKind(ContainerKind.MANAGED);
        container.setName("b1");
        container.setState(state);
        // rewriteSettings re-fetches with an eagerly-joined template (see its own comment) - stub
        // the fetch to return the same instance, since these tests don't care about the template
        // itself.
        when(containerRepository.findByIdWithTemplate(1L)).thenReturn(java.util.Optional.of(container));
        return container;
    }

    private CachedPackage iso() {
        CachedPackage iso = new CachedPackage(PackageManager.ISO, "image.iso", "1_image.iso", null, new User("ext-id"), 10);
        when(packageCacheService.hostPath(iso)).thenReturn("/var/cache/nspawnmgr/packages/iso/uploaded/1_image.iso");
        return iso;
    }

    @Test
    void mountIsoWorksEvenWhenStopped() {
        Container container = managedContainer(ContainerState.STOPPED);
        CachedPackage iso = iso();

        service.mountIso(container, iso);

        verify(isoMounter).mount("b1", ContainerBackend.SYSTEMD_NSPAWN, "/var/cache/nspawnmgr/packages/iso/uploaded/1_image.iso");
        assertThat(container.getMountedIso()).isEqualTo(iso);
        verify(filesystemProvisioner).writeNspawnSettings(eq(container), any());
    }

    @Test
    void mountIsoAutoEjectsWhateverIsAlreadyConfigured() {
        Container container = managedContainer(ContainerState.RUNNING);
        CachedPackage oldIso = iso();
        container.setMountedIso(oldIso);
        CachedPackage newIso = iso();

        service.mountIso(container, newIso);

        verify(isoMounter).unmount("b1", ContainerBackend.SYSTEMD_NSPAWN);
        verify(isoMounter).mount(eq("b1"), eq(ContainerBackend.SYSTEMD_NSPAWN), anyString());
        assertThat(container.getMountedIso()).isEqualTo(newIso);
    }

    @Test
    void ejectIsoIsNoOpWhenNothingConfigured() {
        Container container = managedContainer(ContainerState.STOPPED);

        service.ejectIso(container);

        verify(isoMounter, never()).unmount(anyString(), any());
        verify(filesystemProvisioner, never()).writeNspawnSettings(any(), any());
    }

    @Test
    void ejectIsoUnmountsClearsAndRewritesSettings() {
        Container container = managedContainer(ContainerState.RUNNING);
        container.setMountedIso(iso());

        service.ejectIso(container);

        verify(isoMounter).unmount("b1", ContainerBackend.SYSTEMD_NSPAWN);
        assertThat(container.getMountedIso()).isNull();
        verify(filesystemProvisioner).writeNspawnSettings(eq(container), any());
    }

    @Test
    void deleteCleansUpAllThreeGuacamoleConnectionsIncludingVnc() {
        // Confirmed live: delete() cleaned up SSH/RDP connections but left a VNC connection
        // orphaned in Guacamole forever - the same class of gap ShareService had for granting
        // permissions, just on the deletion side instead.
        Container container = managedContainer(ContainerState.STOPPED);
        container.setGuacSshConnectionId("ssh-conn");
        container.setGuacRdpConnectionId("rdp-conn");
        container.setGuacVncConnectionId("vnc-conn");
        when(cliExecutor.status("b1", ContainerBackend.SYSTEMD_NSPAWN)).thenReturn(MachineStatus.NOT_FOUND);

        service.delete(container);

        verify(guacamoleAdminClient).deleteConnection("ssh-conn");
        verify(guacamoleAdminClient).deleteConnection("rdp-conn");
        verify(guacamoleAdminClient).deleteConnection("vnc-conn");
        verify(shareService).revokeAllForContainer(container);
    }

    @Test
    void deleteRunsGuacamoleCleanupBeforeTheIrreversibleHostSideRemoval() {
        // Confirmed live (arch-xfce, 2026-08-14): the old ordering ran cliExecutor.remove()/
        // deleteMachineFiles() (irreversible - a real machinectl remove and rootfs delete) BEFORE
        // the Guacamole/credential cleanup below, inside one @Transactional method. A failure in
        // that later cleanup (e.g. Guacamole unreachable) rolled back the whole transaction -
        // including the final containerRepository.delete() - but couldn't undo the already-executed
        // host-side removal. The machine was genuinely gone from the OS while its DB row stayed
        // behind looking untouched. Locks in the fix: everything that can still fail runs first.
        Container container = managedContainer(ContainerState.STOPPED);
        container.setGuacSshConnectionId("ssh-conn");
        when(cliExecutor.status("b1", ContainerBackend.SYSTEMD_NSPAWN)).thenReturn(MachineStatus.STOPPED);

        service.delete(container);

        org.mockito.InOrder order = org.mockito.Mockito.inOrder(guacamoleAdminClient, shareService, cliExecutor, filesystemProvisioner, containerRepository);
        order.verify(shareService).revokeAllForContainer(container);
        order.verify(guacamoleAdminClient).deleteConnection("ssh-conn");
        order.verify(cliExecutor).remove("b1", ContainerBackend.SYSTEMD_NSPAWN);
        order.verify(filesystemProvisioner).deleteMachineFiles("b1");
        order.verify(containerRepository).delete(container);
    }

    @Test
    void deleteSkipsMachinectlRemoveWhenTheMachineIsAlreadyGone() {
        // Confirmed live (arch-xfce, 2026-08-14): a prior delete attempt already ran
        // cliExecutor.remove() successfully (the machine genuinely no longer existed on the host)
        // before failing at a later step and stranding the DB row - see this test class's own
        // "RunsGuacamoleCleanupBeforeTheIrreversibleHostSideRemoval" test for that fix. Retrying
        // delete() against that same row must not call machinectl remove again - it fails hard for
        // an unregistered machine (not the transient "Device or resource busy" case
        // RealContainerCliExecutor.remove() already retries) - which would strand the row a second
        // time. NOT_FOUND means nothing left to remove; deleteMachineFiles (rm -rf-based, already
        // idempotent) still runs to clean up any leftover files.
        Container container = managedContainer(ContainerState.STOPPED);
        when(cliExecutor.status("b1", ContainerBackend.SYSTEMD_NSPAWN)).thenReturn(MachineStatus.NOT_FOUND);

        service.delete(container);

        verify(cliExecutor, never()).remove(any(), any());
        verify(cliExecutor, never()).stopForce(any(), any());
        verify(filesystemProvisioner).deleteMachineFiles("b1");
        verify(containerRepository).delete(container);
    }

    @Test
    void deleteNeverTouchesTheHostWhenGuacamoleCleanupFailsFirst() {
        Container container = managedContainer(ContainerState.STOPPED);
        container.setGuacSshConnectionId("ssh-conn");
        org.mockito.Mockito.doThrow(new RuntimeException("Guacamole unreachable"))
                .when(guacamoleAdminClient).deleteConnection("ssh-conn");

        assertThatThrownBy(() -> service.delete(container)).isInstanceOf(RuntimeException.class);

        verify(cliExecutor, never()).remove(any(), any());
        verify(filesystemProvisioner, never()).deleteMachineFiles(any());
        verify(containerRepository, never()).delete(any());
    }

    @Test
    void pauseFreezesARunningContainer() {
        Container container = managedContainer(ContainerState.RUNNING);

        service.pause(container);

        verify(cliExecutor).pause("b1", ContainerBackend.SYSTEMD_NSPAWN);
        assertThat(container.getState()).isEqualTo(ContainerState.PAUSED);
    }

    @Test
    void pauseRejectsAnythingOtherThanRunning() {
        Container container = managedContainer(ContainerState.STOPPED);

        assertThatThrownBy(() -> service.pause(container)).isInstanceOf(IllegalStateException.class);

        verify(cliExecutor, never()).pause(any(), any());
    }

    @Test
    void resumeThawsAPausedContainer() {
        Container container = managedContainer(ContainerState.PAUSED);

        service.resume(container);

        verify(cliExecutor).resume("b1", ContainerBackend.SYSTEMD_NSPAWN);
        assertThat(container.getState()).isEqualTo(ContainerState.RUNNING);
    }

    @Test
    void resumeRejectsAnythingOtherThanPaused() {
        Container container = managedContainer(ContainerState.RUNNING);

        assertThatThrownBy(() -> service.resume(container)).isInstanceOf(IllegalStateException.class);

        verify(cliExecutor, never()).resume(any(), any());
    }

    @Test
    void restartRebootsARunningContainer() {
        Container container = managedContainer(ContainerState.RUNNING);

        service.restart(container);

        verify(cliExecutor).restart("b1", ContainerBackend.SYSTEMD_NSPAWN);
        assertThat(container.getState()).isEqualTo(ContainerState.BOOTING);
    }

    @Test
    void restartRejectsAnythingOtherThanRunning() {
        Container container = managedContainer(ContainerState.STOPPED);

        assertThatThrownBy(() -> service.restart(container)).isInstanceOf(IllegalStateException.class);

        verify(cliExecutor, never()).restart(any(), any());
    }

    @Test
    void stoppingDoesNotTouchMountedIso() {
        Container container = managedContainer(ContainerState.RUNNING);
        CachedPackage iso = iso();
        container.setMountedIso(iso);

        service.stopGraceful(container);

        verify(isoMounter, never()).unmount(anyString(), any());
        assertThat(container.getMountedIso()).isEqualTo(iso);
        assertThat(container.getState()).isEqualTo(ContainerState.STOPPED);
    }
}
