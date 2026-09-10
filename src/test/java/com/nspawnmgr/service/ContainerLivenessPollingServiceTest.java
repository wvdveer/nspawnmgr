package com.nspawnmgr.service;

import com.nspawnmgr.cli.ContainerCliExecutor;
import com.nspawnmgr.cli.MachineStatus;
import com.nspawnmgr.domain.Container;
import com.nspawnmgr.domain.ContainerBackend;
import com.nspawnmgr.domain.ContainerKind;
import com.nspawnmgr.domain.ContainerState;
import com.nspawnmgr.repository.ContainerRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ContainerLivenessPollingServiceTest {

    private ContainerRepository containerRepository;
    private ContainerCliExecutor cliExecutor;
    private ContainerLivenessPollingService service;

    @BeforeEach
    void setUp() {
        containerRepository = mock(ContainerRepository.class);
        cliExecutor = mock(ContainerCliExecutor.class);
        service = new ContainerLivenessPollingService(containerRepository, cliExecutor);
    }

    private Container container(String name, ContainerBackend backend) {
        Container container = new Container();
        container.setName(name);
        container.setBackend(backend);
        container.setState(ContainerState.RUNNING);
        return container;
    }

    @Test
    void marksAPodStoppedWhenPodmanNoLongerAgrees() {
        Container container = container("fed1", ContainerBackend.PODMAN);
        when(containerRepository.findByBackendAndState(ContainerBackend.PODMAN, ContainerState.RUNNING))
                .thenReturn(List.of(container));
        when(cliExecutor.status(eq("fed1"), eq(ContainerBackend.PODMAN))).thenReturn(MachineStatus.STOPPED);

        service.pollRunningContainers();

        assertThat(container.getState()).isEqualTo(ContainerState.STOPPED);
        verify(containerRepository).save(container);
    }

    @Test
    void marksAPodErrorWhenPodmanReportsNotFound() {
        // NOT_FOUND means podman has no record of this pod at all - genuinely different from a
        // routine STOPPED, and Start can't simply fix it, so this is surfaced as ERROR.
        Container container = container("fed1", ContainerBackend.PODMAN);
        when(containerRepository.findByBackendAndState(ContainerBackend.PODMAN, ContainerState.RUNNING))
                .thenReturn(List.of(container));
        when(cliExecutor.status(eq("fed1"), eq(ContainerBackend.PODMAN))).thenReturn(MachineStatus.NOT_FOUND);

        service.pollRunningContainers();

        assertThat(container.getState()).isEqualTo(ContainerState.ERROR);
        assertThat(container.getErrorMessage()).contains("Machine not found on reconciliation");
    }

    @Test
    void clearsAnyStaleErrorMessageWhenMarkingARoutineStop() {
        Container container = container("fed1", ContainerBackend.PODMAN);
        container.setErrorMessage("some stale message from an earlier failure");
        when(containerRepository.findByBackendAndState(ContainerBackend.PODMAN, ContainerState.RUNNING))
                .thenReturn(List.of(container));
        when(cliExecutor.status(eq("fed1"), eq(ContainerBackend.PODMAN))).thenReturn(MachineStatus.STOPPED);

        service.pollRunningContainers();

        assertThat(container.getState()).isEqualTo(ContainerState.STOPPED);
        assertThat(container.getErrorMessage()).isNull();
    }

    @Test
    void leavesAGenuinelyRunningPodAlone() {
        Container container = container("fed1", ContainerBackend.PODMAN);
        when(containerRepository.findByBackendAndState(ContainerBackend.PODMAN, ContainerState.RUNNING))
                .thenReturn(List.of(container));
        when(cliExecutor.status(eq("fed1"), eq(ContainerBackend.PODMAN))).thenReturn(MachineStatus.RUNNING);

        service.pollRunningContainers();

        assertThat(container.getState()).isEqualTo(ContainerState.RUNNING);
        verify(containerRepository, never()).save(container);
    }

    @Test
    void oneFailingCheckDoesNotStopTheOthersFromBeingPolled() {
        Container broken = container("broken-pod", ContainerBackend.PODMAN);
        Container fine = container("fine-pod", ContainerBackend.PODMAN);
        when(containerRepository.findByBackendAndState(ContainerBackend.PODMAN, ContainerState.RUNNING))
                .thenReturn(List.of(broken, fine));
        when(cliExecutor.status(eq("broken-pod"), eq(ContainerBackend.PODMAN))).thenThrow(new RuntimeException("SSH hiccup"));
        when(cliExecutor.status(eq("fine-pod"), eq(ContainerBackend.PODMAN))).thenReturn(MachineStatus.STOPPED);

        service.pollRunningContainers();

        assertThat(broken.getState()).isEqualTo(ContainerState.RUNNING);
        assertThat(fine.getState()).isEqualTo(ContainerState.STOPPED);
    }

    @Test
    void marksAQemuVmStoppedWhenTheGuestCrashedOutFromUnderItsOwnUnit() {
        Container container = container("win11", ContainerBackend.QEMU);
        when(containerRepository.findByBackendAndState(ContainerBackend.QEMU, ContainerState.RUNNING))
                .thenReturn(List.of(container));
        when(cliExecutor.status(eq("win11"), eq(ContainerBackend.QEMU))).thenReturn(MachineStatus.STOPPED);

        service.pollRunningContainers();

        assertThat(container.getState()).isEqualTo(ContainerState.STOPPED);
        verify(containerRepository).save(container);
    }

    @Test
    void leavesAGenuinelyRunningQemuVmAlone() {
        Container container = container("win11", ContainerBackend.QEMU);
        when(containerRepository.findByBackendAndState(ContainerBackend.QEMU, ContainerState.RUNNING))
                .thenReturn(List.of(container));
        when(cliExecutor.status(eq("win11"), eq(ContainerBackend.QEMU))).thenReturn(MachineStatus.RUNNING);

        service.pollRunningContainers();

        assertThat(container.getState()).isEqualTo(ContainerState.RUNNING);
        verify(containerRepository, never()).save(container);
    }

    @Test
    void pollsBothBackendsInOnePass() {
        Container pod = container("fed1", ContainerBackend.PODMAN);
        Container vm = container("win11", ContainerBackend.QEMU);
        when(containerRepository.findByBackendAndState(ContainerBackend.PODMAN, ContainerState.RUNNING))
                .thenReturn(List.of(pod));
        when(containerRepository.findByBackendAndState(ContainerBackend.QEMU, ContainerState.RUNNING))
                .thenReturn(List.of(vm));
        when(cliExecutor.status(eq("fed1"), eq(ContainerBackend.PODMAN))).thenReturn(MachineStatus.STOPPED);
        when(cliExecutor.status(eq("win11"), eq(ContainerBackend.QEMU))).thenReturn(MachineStatus.STOPPED);

        service.pollRunningContainers();

        assertThat(pod.getState()).isEqualTo(ContainerState.STOPPED);
        assertThat(vm.getState()).isEqualTo(ContainerState.STOPPED);
    }

    @Test
    void reconcileMarksANspawnContainerErrorWhenMachinectlHasNoImageForItAtAll() {
        // Exactly the acer 2026-09-09 scenario, refined: NOT_FOUND means the machine doesn't
        // appear in `machinectl list-images` at all (see RealContainerCliExecutor#status's own
        // comment) - a genuinely missing rootfs, not the routine "stopped but still provisioned"
        // case, so this is surfaced as ERROR rather than STOPPED. pollRunningContainers() above
        // never notices this at all either way, since it deliberately never polls this backend.
        Container container = container("arch-runner", ContainerBackend.SYSTEMD_NSPAWN);
        when(containerRepository.findByKindAndState(ContainerKind.MANAGED, ContainerState.RUNNING))
                .thenReturn(List.of(container));
        when(cliExecutor.status(eq("arch-runner"), eq(ContainerBackend.SYSTEMD_NSPAWN))).thenReturn(MachineStatus.NOT_FOUND);

        List<Container> corrected = service.reconcile();

        assertThat(container.getState()).isEqualTo(ContainerState.ERROR);
        assertThat(container.getErrorMessage()).contains("Machine not found on reconciliation");
        assertThat(corrected).containsExactly(container);
        verify(containerRepository).save(container);
    }

    @Test
    void reconcileMarksANspawnContainerStoppedWhenItsImageExistsButIsntBooted() {
        // The routine case: STOPPED means the machine appears in `machinectl list-images` (the
        // rootfs is still there) but not `machinectl list` (not currently running) - a completely
        // normal stop, not an error.
        Container container = container("arch-runner", ContainerBackend.SYSTEMD_NSPAWN);
        when(containerRepository.findByKindAndState(ContainerKind.MANAGED, ContainerState.RUNNING))
                .thenReturn(List.of(container));
        when(cliExecutor.status(eq("arch-runner"), eq(ContainerBackend.SYSTEMD_NSPAWN))).thenReturn(MachineStatus.STOPPED);

        List<Container> corrected = service.reconcile();

        assertThat(container.getState()).isEqualTo(ContainerState.STOPPED);
        assertThat(corrected).containsExactly(container);
    }

    @Test
    void reconcileLeavesAGenuinelyRunningNspawnContainerAlone() {
        Container container = container("nspawnmgr", ContainerBackend.SYSTEMD_NSPAWN);
        when(containerRepository.findByKindAndState(ContainerKind.MANAGED, ContainerState.RUNNING))
                .thenReturn(List.of(container));
        when(cliExecutor.status(eq("nspawnmgr"), eq(ContainerBackend.SYSTEMD_NSPAWN))).thenReturn(MachineStatus.RUNNING);

        List<Container> corrected = service.reconcile();

        assertThat(container.getState()).isEqualTo(ContainerState.RUNNING);
        assertThat(corrected).isEmpty();
        verify(containerRepository, never()).save(container);
    }

    @Test
    void reconcileOneFailingCheckDoesNotStopTheOthersFromBeingChecked() {
        Container broken = container("broken", ContainerBackend.SYSTEMD_NSPAWN);
        Container fine = container("fine", ContainerBackend.SYSTEMD_NSPAWN);
        when(containerRepository.findByKindAndState(ContainerKind.MANAGED, ContainerState.RUNNING))
                .thenReturn(List.of(broken, fine));
        when(cliExecutor.status(eq("broken"), eq(ContainerBackend.SYSTEMD_NSPAWN))).thenThrow(new RuntimeException("SSH hiccup"));
        when(cliExecutor.status(eq("fine"), eq(ContainerBackend.SYSTEMD_NSPAWN))).thenReturn(MachineStatus.NOT_FOUND);

        List<Container> corrected = service.reconcile();

        assertThat(broken.getState()).isEqualTo(ContainerState.RUNNING);
        assertThat(fine.getState()).isEqualTo(ContainerState.ERROR);
        assertThat(corrected).containsExactly(fine);
    }

    @Test
    void reconcileOnStartupDelegatesToReconcile() {
        Container container = container("arch-runner", ContainerBackend.SYSTEMD_NSPAWN);
        when(containerRepository.findByKindAndState(ContainerKind.MANAGED, ContainerState.RUNNING))
                .thenReturn(List.of(container));
        when(cliExecutor.status(eq("arch-runner"), eq(ContainerBackend.SYSTEMD_NSPAWN))).thenReturn(MachineStatus.NOT_FOUND);

        service.reconcileOnStartup();

        assertThat(container.getState()).isEqualTo(ContainerState.ERROR);
        verify(containerRepository).save(container);
    }
}
