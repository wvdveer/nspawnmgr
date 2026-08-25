package com.nspawnmgr.service;

import com.nspawnmgr.cli.ContainerCliExecutor;
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
 * A QEMU VM reaches RUNNING the instant its systemd unit launches (see ProvisioningService#provisionQemu
 * - like a pod, there's no auto-provisioned SSH credential to gate a BOOTING->RUNNING transition on,
 * so ContainerReadinessPollingService's own SSH-reachability check doesn't apply here at all), but its
 * guest OS may not have an internal address for a long time afterward - possibly never, during the
 * from-scratch ISO-installer phase before an OS is even installed. Every 30s, retries resolving one for
 * any RUNNING QEMU VM that doesn't have one yet, so SSH/RDP (opt-in, reachability-gated - see
 * ContainerAccessService) becomes usable as soon as the guest actually gets a DHCP lease, without a
 * human having to do anything to trigger that resolution.
 */
@Component
public class QemuAddressPollingService {

    private static final Logger log = LoggerFactory.getLogger(QemuAddressPollingService.class);

    private final ContainerRepository containerRepository;
    private final ContainerCliExecutor cliExecutor;

    public QemuAddressPollingService(ContainerRepository containerRepository, ContainerCliExecutor cliExecutor) {
        this.containerRepository = containerRepository;
        this.cliExecutor = cliExecutor;
    }

    @Scheduled(fixedDelay = 30000)
    public void pollRunningVms() {
        List<Container> running = containerRepository.findByBackendAndState(ContainerBackend.QEMU, ContainerState.RUNNING);
        for (Container container : running) {
            if (container.getInternalAddress() != null && !container.getInternalAddress().isBlank()) {
                continue;
            }
            try {
                checkOne(container);
            } catch (Exception e) {
                log.warn("Address resolution failed for QEMU VM {}: {}", container.getName(), e.getMessage(), e);
            }
        }
    }

    private void checkOne(Container container) {
        String address = cliExecutor.getInternalAddress(container.getName(), ContainerBackend.QEMU);
        if (!address.isBlank()) {
            container.setInternalAddress(address);
            container.touch();
            containerRepository.save(container);
            log.info("QEMU VM {} resolved internal address {}", container.getName(), address);
        }
    }
}
