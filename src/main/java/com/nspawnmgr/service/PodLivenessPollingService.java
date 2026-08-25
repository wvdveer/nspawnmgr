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
 * Podman containers can exit entirely on their own (a bad/no keep-alive command - see {@link
 * com.nspawnmgr.domain.Container#getPodCommand}'s own javadoc), independent of anything nspawnmgr
 * itself does - unlike SYSTEMD_NSPAWN containers, which stay RUNNING until nspawnmgr's own Stop/
 * Restart/Delete acts on them, or genuinely crash. A pod also skips
 * {@link ContainerReadinessPollingService} entirely (see {@link ContainerLifecycleService}'s own
 * {@code bootedState} javadoc), so nothing else in this app would ever notice a pod exiting on its
 * own - confirmed live (yoga, 2026-08-16): a pod whose image's default command was a bare
 * interactive shell exited within milliseconds of {@code podman start} succeeding, and nspawnmgr's
 * own state stayed RUNNING forever, its Scripts feature failing with podman's own confusing
 * "container state improper" the moment anyone tried to use it.
 *
 * <p>Every 30s, checks each RUNNING pod's real podman status and marks it STOPPED the moment podman
 * itself disagrees - no attempt to distinguish "exited cleanly" from "crashed" (podman's own status
 * doesn't separate those either, and neither does STOPPED for a SYSTEMD_NSPAWN container), just
 * reconciling nspawnmgr's own belief with reality. PAUSED pods aren't polled - not requested, and
 * podman's own pause/unpause already round-trips correctly since it's always nspawnmgr's own action.
 */
@Component
public class PodLivenessPollingService {

    private static final Logger log = LoggerFactory.getLogger(PodLivenessPollingService.class);

    private final ContainerRepository containerRepository;
    private final ContainerCliExecutor cliExecutor;

    public PodLivenessPollingService(ContainerRepository containerRepository, ContainerCliExecutor cliExecutor) {
        this.containerRepository = containerRepository;
        this.cliExecutor = cliExecutor;
    }

    @Scheduled(fixedDelay = 30000)
    public void pollRunningPods() {
        List<Container> running = containerRepository.findByBackendAndState(ContainerBackend.PODMAN, ContainerState.RUNNING);
        for (Container container : running) {
            try {
                checkOne(container);
            } catch (Exception e) {
                log.warn("Liveness check failed for pod {}: {}", container.getName(), e.getMessage(), e);
            }
        }
    }

    private void checkOne(Container container) {
        MachineStatus status = cliExecutor.status(container.getName(), ContainerBackend.PODMAN);
        if (status != MachineStatus.RUNNING) {
            container.setState(ContainerState.STOPPED);
            container.touch();
            containerRepository.save(container);
            log.info("Pod {} was RUNNING but podman reports {} - marked STOPPED", container.getName(), status);
        }
    }
}
