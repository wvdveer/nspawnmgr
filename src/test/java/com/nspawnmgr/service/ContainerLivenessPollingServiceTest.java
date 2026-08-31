package com.nspawnmgr.service;

import com.nspawnmgr.cli.ContainerCliExecutor;
import com.nspawnmgr.cli.MachineStatus;
import com.nspawnmgr.domain.Container;
import com.nspawnmgr.domain.ContainerBackend;
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
    void marksAPodStoppedWhenPodmanReportsNotFound() {
        Container container = container("fed1", ContainerBackend.PODMAN);
        when(containerRepository.findByBackendAndState(ContainerBackend.PODMAN, ContainerState.RUNNING))
                .thenReturn(List.of(container));
        when(cliExecutor.status(eq("fed1"), eq(ContainerBackend.PODMAN))).thenReturn(MachineStatus.NOT_FOUND);

        service.pollRunningContainers();

        assertThat(container.getState()).isEqualTo(ContainerState.STOPPED);
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
}
