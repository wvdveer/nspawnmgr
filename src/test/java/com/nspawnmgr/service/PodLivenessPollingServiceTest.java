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

class PodLivenessPollingServiceTest {

    private ContainerRepository containerRepository;
    private ContainerCliExecutor cliExecutor;
    private PodLivenessPollingService service;

    @BeforeEach
    void setUp() {
        containerRepository = mock(ContainerRepository.class);
        cliExecutor = mock(ContainerCliExecutor.class);
        service = new PodLivenessPollingService(containerRepository, cliExecutor);
    }

    private Container pod(String name) {
        Container container = new Container();
        container.setName(name);
        container.setBackend(ContainerBackend.PODMAN);
        container.setState(ContainerState.RUNNING);
        return container;
    }

    @Test
    void marksAPodStoppedWhenPodmanNoLongerAgrees() {
        Container container = pod("fed1");
        when(containerRepository.findByBackendAndState(ContainerBackend.PODMAN, ContainerState.RUNNING))
                .thenReturn(List.of(container));
        when(cliExecutor.status(eq("fed1"), eq(ContainerBackend.PODMAN))).thenReturn(MachineStatus.STOPPED);

        service.pollRunningPods();

        assertThat(container.getState()).isEqualTo(ContainerState.STOPPED);
        verify(containerRepository).save(container);
    }

    @Test
    void marksAPodStoppedWhenPodmanReportsNotFound() {
        Container container = pod("fed1");
        when(containerRepository.findByBackendAndState(ContainerBackend.PODMAN, ContainerState.RUNNING))
                .thenReturn(List.of(container));
        when(cliExecutor.status(eq("fed1"), eq(ContainerBackend.PODMAN))).thenReturn(MachineStatus.NOT_FOUND);

        service.pollRunningPods();

        assertThat(container.getState()).isEqualTo(ContainerState.STOPPED);
    }

    @Test
    void leavesAGenuinelyRunningPodAlone() {
        Container container = pod("fed1");
        when(containerRepository.findByBackendAndState(ContainerBackend.PODMAN, ContainerState.RUNNING))
                .thenReturn(List.of(container));
        when(cliExecutor.status(eq("fed1"), eq(ContainerBackend.PODMAN))).thenReturn(MachineStatus.RUNNING);

        service.pollRunningPods();

        assertThat(container.getState()).isEqualTo(ContainerState.RUNNING);
        verify(containerRepository, never()).save(container);
    }

    @Test
    void oneFailingCheckDoesNotStopTheOthersFromBeingPolled() {
        Container broken = pod("broken-pod");
        Container fine = pod("fine-pod");
        when(containerRepository.findByBackendAndState(ContainerBackend.PODMAN, ContainerState.RUNNING))
                .thenReturn(List.of(broken, fine));
        when(cliExecutor.status(eq("broken-pod"), eq(ContainerBackend.PODMAN))).thenThrow(new RuntimeException("SSH hiccup"));
        when(cliExecutor.status(eq("fine-pod"), eq(ContainerBackend.PODMAN))).thenReturn(MachineStatus.STOPPED);

        service.pollRunningPods();

        assertThat(broken.getState()).isEqualTo(ContainerState.RUNNING);
        assertThat(fine.getState()).isEqualTo(ContainerState.STOPPED);
    }
}
