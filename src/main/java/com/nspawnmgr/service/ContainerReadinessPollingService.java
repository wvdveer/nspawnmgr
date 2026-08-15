package com.nspawnmgr.service;

import com.nspawnmgr.cli.ContainerReadinessChecker;
import com.nspawnmgr.crypto.SecretEncryptionService;
import com.nspawnmgr.domain.Container;
import com.nspawnmgr.domain.ContainerCredential;
import com.nspawnmgr.domain.ContainerState;
import com.nspawnmgr.domain.CredentialType;
import com.nspawnmgr.guacamole.GuacamoleAdminClient;
import com.nspawnmgr.repository.ContainerCredentialRepository;
import com.nspawnmgr.repository.ContainerRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Optional;

/**
 * The first scheduled/periodic job in this codebase: every 10s, checks each BOOTING container's own
 * SSH (and RDP, if enabled) reachability and flips it to RUNNING once ready. Fixed-delay rather than
 * fixed-rate so a slow pass across many BOOTING containers can't overlap with the next tick.
 *
 * <p>Deliberately no timeout/ERROR transition for a container stuck in BOOTING forever (bad
 * template, crashed sshd) — not requested; it just stays BOOTING, visible as such in the UI.
 */
@Component
public class ContainerReadinessPollingService {

    private static final Logger log = LoggerFactory.getLogger(ContainerReadinessPollingService.class);

    private static final int SSH_PORT = 22;
    private static final int RDP_PORT = 3389;
    private static final int VNC_PORT = 5900;

    private final ContainerRepository containerRepository;
    private final ContainerCredentialRepository containerCredentialRepository;
    private final ContainerReadinessChecker readinessChecker;
    private final SecretEncryptionService secretEncryptionService;
    private final GuacamoleAdminClient guacamoleAdminClient;
    private final PamCredentialAuthService pamCredentialAuthService;

    public ContainerReadinessPollingService(ContainerRepository containerRepository,
                                             ContainerCredentialRepository containerCredentialRepository,
                                             ContainerReadinessChecker readinessChecker,
                                             SecretEncryptionService secretEncryptionService,
                                             GuacamoleAdminClient guacamoleAdminClient,
                                             PamCredentialAuthService pamCredentialAuthService) {
        this.containerRepository = containerRepository;
        this.containerCredentialRepository = containerCredentialRepository;
        this.readinessChecker = readinessChecker;
        this.secretEncryptionService = secretEncryptionService;
        this.guacamoleAdminClient = guacamoleAdminClient;
        this.pamCredentialAuthService = pamCredentialAuthService;
    }

    @Scheduled(fixedDelay = 10000)
    public void pollBootingContainers() {
        List<Container> booting = containerRepository.findByStateWithOwnerAndTemplate(ContainerState.BOOTING);
        for (Container container : booting) {
            try {
                checkOne(container);
            } catch (Exception e) {
                log.warn("Readiness check failed for container {}: {}", container.getName(), e.getMessage(), e);
            }
        }
    }

    private void checkOne(Container container) {
        Optional<ContainerCredential> credential = containerCredentialRepository.findByContainerAndType(container, CredentialType.SSH_KEY);
        if (credential.isEmpty()) {
            // Shouldn't happen by construction — provisionSsh() always persists this credential
            // before a container ever reaches BOOTING — but skip rather than throw if it's somehow
            // missing, so one odd container can't spam the log every 10s forever.
            log.warn("Container {} is BOOTING but has no SSH_KEY credential — skipping readiness check", container.getName());
            return;
        }

        String privateKeyPem = secretEncryptionService.decrypt(credential.get().getSecretCiphertext(), credential.get().getIv());
        ContainerReadinessChecker.Readiness readiness = readinessChecker.check(
                container, privateKeyPem, credential.get().getAccountName(), container.isRdpEnabled());

        if (readiness.sshReady() && (!container.isRdpEnabled() || readiness.rdpReady())) {
            container.setState(ContainerState.RUNNING);
            container.setInternalAddress(readiness.internalAddress());
            container.touch();
            containerRepository.save(container);
            log.info("Container {} reached RUNNING (SSH{} ready) at internal address {}", container.getName(),
                    container.isRdpEnabled() ? "+RDP" : "", readiness.internalAddress());
            updateGuacamoleConnections(container, readiness.internalAddress(), credential.get());
            reapplyPamAuthSettings(container);
        }
    }

    /**
     * Best-effort: keeps each Guacamole connection's hostname pointed at whatever internal address
     * the container actually has this boot (it can change across restarts). Failures here are
     * logged and swallowed rather than undoing the RUNNING transition — the container really is up;
     * a Guacamole hiccup shouldn't roll that back, and there's no retry until the container's next
     * restart (same posture as this codebase's other best-effort post-RUNNING sync steps).
     */
    private void updateGuacamoleConnections(Container container, String internalAddress, ContainerCredential sshCredential) {
        if (internalAddress == null) {
            return;
        }
        try {
            if (container.getGuacSshConnectionId() != null) {
                String privateKeyPem = secretEncryptionService.decrypt(sshCredential.getSecretCiphertext(), sshCredential.getIv());
                guacamoleAdminClient.updateSshConnection(container.getGuacSshConnectionId(), container.getName() + "-ssh",
                        internalAddress, SSH_PORT, sshCredential.getAccountName(), privateKeyPem);
            }
            if (container.isRdpEnabled() && container.getGuacRdpConnectionId() != null) {
                // Delegates to PamCredentialAuthService rather than always re-pointing this at the
                // RDP_PASSWORD credential: an owner may have switched pam_nspawnmgr's RDP check to
                // VNC_PASSWORD or NSPAWNMGR_AUTH_BACKEND, and this poll tick runs on every restart -
                // the old hardcoded-RDP_PASSWORD version here would have silently undone that
                // reconciliation the moment the container next rebooted.
                pamCredentialAuthService.reconcileRdpConnectionCredentials(container, internalAddress);
            }
            if (container.isVncEnabled() && container.getGuacVncConnectionId() != null) {
                containerCredentialRepository.findByContainerAndType(container, CredentialType.VNC_PASSWORD)
                        .ifPresent(vncCredential -> {
                            String password = secretEncryptionService.decrypt(vncCredential.getSecretCiphertext(), vncCredential.getIv());
                            guacamoleAdminClient.updateVncConnection(container.getGuacVncConnectionId(), container.getName() + "-vnc",
                                    internalAddress, VNC_PORT, password);
                        });
            }
        } catch (Exception e) {
            log.warn("Failed to sync Guacamole connection(s) for container {} at internal address {}: {}",
                    container.getName(), internalAddress, e.getMessage(), e);
        }
    }

    /**
     * Best-effort, same posture as {@link #updateGuacamoleConnections}: covers the case where a
     * pam_nspawnmgr setting changed (or was set for the first time, at provisioning) while this
     * container was stopped or still booting - see PamCredentialAuthService#reapplyIfConfigured.
     */
    private void reapplyPamAuthSettings(Container container) {
        try {
            pamCredentialAuthService.reapplyIfConfigured(container);
        } catch (Exception e) {
            log.warn("Failed to reapply pam_nspawnmgr settings for container {}: {}",
                    container.getName(), e.getMessage(), e);
        }
    }
}
