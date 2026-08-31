package com.nspawnmgr.service;

import com.nspawnmgr.cli.ContainerCliExecutor;
import com.nspawnmgr.cli.MachineStatus;
import com.nspawnmgr.domain.Container;
import com.nspawnmgr.domain.ContainerBackend;
import com.nspawnmgr.domain.ContainerState;
import com.nspawnmgr.repository.ContainerRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * Podman pods and QEMU VMs can both exit/crash entirely on their own, independent of anything
 * nspawnmgr itself does - unlike SYSTEMD_NSPAWN containers, which stay RUNNING until nspawnmgr's
 * own Stop/Restart/Delete acts on them (not polled here, deliberately - out of scope, same as it
 * always was for the PODMAN-only version of this class).
 *
 * <p>PODMAN: a pod whose image's own default command is a bare interactive shell (see {@link
 * com.nspawnmgr.domain.Container#getPodCommand}'s own javadoc) exits within milliseconds of
 * {@code podman start} succeeding, landing on podman's own "Exited" state that nspawnmgr never
 * notices - confirmed live (yoga, 2026-08-16). A pod also skips {@link
 * ContainerReadinessPollingService} entirely (see {@link ContainerLifecycleService}'s own {@code
 * bootedState} javadoc), so nothing else in this app would ever notice a pod exiting on its own.
 *
 * <p>QEMU: the guest OS can crash or be killed from inside the VM without the host-side systemd
 * unit itself ever stopping - {@code systemctl start/stop/restart} against that unit already
 * tracks the unit's own lifecycle correctly (nspawnmgr always drives a VM through it, never
 * touches the QEMU process directly), but says nothing about whether the guest OS running inside
 * is actually still alive versus hung/crashed-and-respawned/etc. Closes the "One known gap" this
 * project's own docs/administrator-guide.md previously flagged for the QEMU backend.
 *
 * <p>Every 30s, checks each RUNNING pod/VM's real status (via the same {@link
 * ContainerCliExecutor#status} both backends already implement - {@code podman inspect} /
 * {@code systemctl is-active <unit>}) and marks it STOPPED the moment reality disagrees - no
 * attempt to distinguish "exited cleanly" from "crashed" (neither backend's own status
 * separates those either, and neither does STOPPED for a SYSTEMD_NSPAWN container), just
 * reconciling nspawnmgr's own belief with reality. Explicitly NOT detecting a guest-OS-only QEMU
 * crash where the process/unit itself stays alive ({@code systemctl is-active} still reports
 * active) - same scope limit this class always had for podman, just not solvable by this same
 * "ask the backend for its own status" technique for either backend.
 *
 * <p>PAUSED containers aren't polled - not requested, and both backends' own pause/unpause
 * already round-trip correctly since it's always nspawnmgr's own action.
 */
@Component
public class ContainerLivenessPollingService {

    private static final Logger log = LoggerFactory.getLogger(ContainerLivenessPollingService.class);

    private static final List<ContainerBackend> POLLED_BACKENDS = List.of(ContainerBackend.PODMAN, ContainerBackend.QEMU);

    private final ContainerRepository containerRepository;
    private final ContainerCliExecutor cliExecutor;

    public ContainerLivenessPollingService(ContainerRepository containerRepository, ContainerCliExecutor cliExecutor) {
        this.containerRepository = containerRepository;
        this.cliExecutor = cliExecutor;
    }

    @Scheduled(fixedDelay = 30000)
    public void pollRunningContainers() {
        for (ContainerBackend backend : POLLED_BACKENDS) {
            for (Container container : containerRepository.findByBackendAndState(backend, ContainerState.RUNNING)) {
                try {
                    checkOne(container, backend);
                } catch (Exception e) {
                    log.warn("Liveness check failed for {} {}: {}", backend, container.getName(), e.getMessage(), e);
                }
            }
        }
    }

    private void checkOne(Container container, ContainerBackend backend) {
        MachineStatus status = cliExecutor.status(container.getName(), backend);
        if (status != MachineStatus.RUNNING) {
            container.setState(ContainerState.STOPPED);
            container.touch();
            containerRepository.save(container);
            log.info("{} {} was RUNNING but real status is {} - marked STOPPED", backend, container.getName(), status);
        }
    }
}
