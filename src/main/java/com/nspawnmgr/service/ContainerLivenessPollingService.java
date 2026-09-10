package com.nspawnmgr.service;

import com.nspawnmgr.cli.ContainerCliExecutor;
import com.nspawnmgr.cli.MachineStatus;
import com.nspawnmgr.domain.Container;
import com.nspawnmgr.domain.ContainerBackend;
import com.nspawnmgr.domain.ContainerKind;
import com.nspawnmgr.domain.ContainerState;
import com.nspawnmgr.repository.ContainerRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
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
 *
 * <p>{@link #reconcile()} is a separate check covering every backend including SYSTEMD_NSPAWN -
 * see its own javadoc for why the "stays RUNNING until nspawnmgr's own action" assumption above
 * doesn't hold across an app restart. Runs automatically once at startup ({@link
 * #reconcileOnStartup()}) and can also be triggered on demand (see ContainerApiController's own
 * {@code /api/containers/reconcile} endpoint).
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

    /**
     * Checks every MANAGED container nspawnmgr currently believes is RUNNING, across every
     * backend including SYSTEMD_NSPAWN, against reality - unlike {@link #pollRunningContainers()}
     * above (deliberately PODMAN/QEMU-only). A nspawn container genuinely doesn't crash/exit on
     * its own while nspawnmgr keeps running, but nspawnmgr's own restart is often caused by (or
     * coincides with) a host reboot, and any container without "start automatically when host
     * boots" enabled won't have come back - yet nspawnmgr's own DB still shows its last-known
     * RUNNING state forever, since nothing else in this app ever reconciles a SYSTEMD_NSPAWN
     * container's state against reality. Confirmed live (acer, 2026-09-09): three CI runner
     * containers stayed shown as RUNNING with their last-known address for hours after a host
     * reboot silently left them stopped. Scoped to MANAGED containers only - see
     * ContainerRepository#findByKindAndState's own javadoc for why EXTERNAL hosts don't need
     * this. Safe to call anytime, by anyone - purely corrective/idempotent, never destructive.
     *
     * @return the containers whose stored RUNNING state didn't match reality and were corrected
     *         to STOPPED.
     */
    public List<Container> reconcile() {
        List<Container> corrected = new ArrayList<>();
        for (Container container : containerRepository.findByKindAndState(ContainerKind.MANAGED, ContainerState.RUNNING)) {
            try {
                if (checkOne(container, container.getBackend())) {
                    corrected.add(container);
                }
            } catch (Exception e) {
                log.warn("Liveness reconciliation failed for {} {}: {}", container.getBackend(), container.getName(), e.getMessage(), e);
            }
        }
        return corrected;
    }

    /** Runs {@link #reconcile()} once automatically right after nspawnmgr itself finishes
     *  starting up - see that method's own javadoc for why this matters. */
    @EventListener(ApplicationReadyEvent.class)
    public void reconcileOnStartup() {
        reconcile();
    }

    /** @return true if the container's stored state didn't match reality and was corrected. */
    private boolean checkOne(Container container, ContainerBackend backend) {
        MachineStatus status = cliExecutor.status(container.getName(), backend);
        if (status == MachineStatus.RUNNING) {
            return false;
        }
        // NOT_FOUND means the backend has no record of this machine/pod/VM at all - a genuinely
        // different situation from a normal STOPPED (e.g. a SYSTEMD_NSPAWN container whose rootfs
        // still exists but just isn't booted, see RealContainerCliExecutor#status's own comment):
        // something deleted or otherwise lost the underlying object outside of nspawnmgr, which
        // Start can't simply fix, so it's surfaced as ERROR rather than the routine STOPPED - with
        // an explanatory errorMessage (this field isn't translated anywhere else in the codebase
        // either - see ProvisioningService's own identical, English-only usage), since an admin
        // landing on ERROR with no message has no way to tell this apart from a real provisioning
        // failure.
        ContainerState corrected;
        String errorMessage;
        if (status == MachineStatus.NOT_FOUND) {
            corrected = ContainerState.ERROR;
            errorMessage = "Machine not found on reconciliation: " + backend
                    + " has no record of it at all (a host reboot may have left it stopped with no"
                    + " autostart configured, or it was removed outside of nspawnmgr).";
        } else {
            corrected = ContainerState.STOPPED;
            errorMessage = null;
        }
        container.setState(corrected);
        container.setErrorMessage(errorMessage);
        container.touch();
        containerRepository.save(container);
        log.info("{} {} was RUNNING but real status is {} - marked {}", backend, container.getName(), status, corrected);
        return true;
    }
}
