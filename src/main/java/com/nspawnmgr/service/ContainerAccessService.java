package com.nspawnmgr.service;

import com.nspawnmgr.cli.ContainerPortProbe;
import com.nspawnmgr.crypto.SecretEncryptionService;
import com.nspawnmgr.domain.Container;
import com.nspawnmgr.domain.ContainerCredential;
import com.nspawnmgr.domain.CredentialType;
import com.nspawnmgr.domain.RdpSecurityMode;
import com.nspawnmgr.guacamole.GuacamoleAdminClient;
import com.nspawnmgr.repository.ContainerCredentialRepository;
import com.nspawnmgr.repository.ContainerRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Optional;

/**
 * Enables/disables Guacamole Connect access for a protocol nspawnmgr has no generated credential
 * for on a given container - a discovered machine, or a normal one where RDP was declined at
 * creation time. Wires up a prompt-credentials connection (same mechanism EXTERNAL hosts already
 * use: {@link GuacamoleAdminClient#createSshConnectionPromptCredentials}/
 * {@link GuacamoleAdminClient#createRdpConnectionPromptCredentials}) only after confirming the port
 * is actually reachable - nspawnmgr never assumes an account/password for a service it didn't set
 * up itself. Deliberately never touches a container that already has a matching
 * {@link com.nspawnmgr.domain.ContainerCredential} row - that's a normally-provisioned connection
 * this feature must not silently replace.
 */
@Service
public class ContainerAccessService {

    private static final Logger log = LoggerFactory.getLogger(ContainerAccessService.class);

    private static final int SSH_PORT = 22;
    private static final int RDP_PORT = 3389;
    private static final int VNC_PORT = 5900;

    private final ContainerRepository containerRepository;
    private final ContainerCredentialRepository credentialRepository;
    private final ContainerPortProbe portProbe;
    private final GuacamoleAdminClient guacamoleAdminClient;
    private final SecretEncryptionService secretEncryptionService;

    public ContainerAccessService(ContainerRepository containerRepository,
                                   ContainerCredentialRepository credentialRepository,
                                   ContainerPortProbe portProbe,
                                   GuacamoleAdminClient guacamoleAdminClient,
                                   SecretEncryptionService secretEncryptionService) {
        this.containerRepository = containerRepository;
        this.credentialRepository = credentialRepository;
        this.portProbe = portProbe;
        this.guacamoleAdminClient = guacamoleAdminClient;
        this.secretEncryptionService = secretEncryptionService;
    }

    @Transactional
    public void enableSsh(Container container) {
        requireNoCredential(container, CredentialType.SSH_KEY);
        requireReachable(container, SSH_PORT, "SSH");
        String id = guacamoleAdminClient.createSshConnectionPromptCredentials(
                container.getName() + "-ssh", container.getInternalAddress(), SSH_PORT);
        container.setGuacSshConnectionId(id);
        container.touch();
        containerRepository.save(container);
    }

    @Transactional
    public void disableSsh(Container container) {
        if (container.getGuacSshConnectionId() != null) {
            guacamoleAdminClient.deleteConnection(container.getGuacSshConnectionId());
            container.setGuacSshConnectionId(null);
            container.touch();
            containerRepository.save(container);
        }
    }

    @Transactional
    public void enableRdp(Container container) {
        requireNoCredential(container, CredentialType.RDP_PASSWORD);
        requireReachable(container, RDP_PORT, "RDP");
        String id = guacamoleAdminClient.createRdpConnectionPromptCredentials(
                container.getName() + "-rdp", container.getInternalAddress(), RDP_PORT, container.getRdpSecurity().guacamoleValue());
        container.setGuacRdpConnectionId(id);
        container.setRdpEnabled(true);
        container.touch();
        containerRepository.save(container);
    }

    @Transactional
    public void disableRdp(Container container) {
        if (container.getGuacRdpConnectionId() != null) {
            guacamoleAdminClient.deleteConnection(container.getGuacRdpConnectionId());
            container.setGuacRdpConnectionId(null);
            container.setRdpEnabled(false);
            container.touch();
            containerRepository.save(container);
        }
    }

    /**
     * Persists the new RDP security mode and, if a Guacamole RDP connection already exists for
     * this container, re-pushes it immediately (real generated-credential connection or
     * prompt-credentials one, whichever this container actually has) so the change takes effect
     * on the next connection attempt without needing a container restart. A no-op push (no
     * connection configured yet) is fine - the new value still applies whenever RDP is next
     * provisioned/enabled.
     */
    @Transactional
    public void setRdpSecurity(Container container, RdpSecurityMode mode) {
        container.setRdpSecurity(mode);
        container.touch();
        containerRepository.save(container);
        if (container.getGuacRdpConnectionId() == null) {
            return;
        }
        Optional<ContainerCredential> credential = credentialRepository.findByContainerAndType(container, CredentialType.RDP_PASSWORD);
        if (credential.isPresent()) {
            String password = secretEncryptionService.decrypt(credential.get().getSecretCiphertext(), credential.get().getIv());
            guacamoleAdminClient.updateRdpConnection(container.getGuacRdpConnectionId(), container.getName() + "-rdp",
                    container.getInternalAddress(), RDP_PORT, credential.get().getAccountName(), password, mode.guacamoleValue());
        } else {
            guacamoleAdminClient.updateRdpConnectionPromptCredentials(container.getGuacRdpConnectionId(),
                    container.getName() + "-rdp", container.getInternalAddress(), RDP_PORT, mode.guacamoleValue());
        }
    }

    @Transactional
    public void enableVnc(Container container) {
        requireNoCredential(container, CredentialType.VNC_PASSWORD);
        requireReachable(container, VNC_PORT, "VNC");
        String id = guacamoleAdminClient.createVncConnectionPromptCredentials(
                container.getName() + "-vnc", container.getInternalAddress(), VNC_PORT);
        container.setGuacVncConnectionId(id);
        container.setVncEnabled(true);
        container.touch();
        containerRepository.save(container);
    }

    @Transactional
    public void disableVnc(Container container) {
        if (container.getGuacVncConnectionId() != null) {
            guacamoleAdminClient.deleteConnection(container.getGuacVncConnectionId());
            container.setGuacVncConnectionId(null);
            container.setVncEnabled(false);
            container.touch();
            containerRepository.save(container);
        }
    }

    /**
     * Best-effort - swallows both "port not open yet" and any Guacamole-call failure - used right
     * after discovery so the common "already has sshd running" case gets wired up with zero extra
     * clicks, without failing the whole discover() call over one machine's hiccup.
     */
    public void tryAutoEnable(Container container) {
        tryEnable(() -> enableSsh(container), container, "SSH");
        tryEnable(() -> enableRdp(container), container, "RDP");
        tryEnable(() -> enableVnc(container), container, "VNC");
    }

    private void tryEnable(Runnable action, Container container, String label) {
        try {
            action.run();
        } catch (Exception e) {
            log.debug("Auto-enable {} access skipped for discovered container '{}': {}",
                    label, container.getName(), e.getMessage());
        }
    }

    private void requireNoCredential(Container container, CredentialType type) {
        if (credentialRepository.findByContainerAndType(container, type).isPresent()) {
            throw new IllegalStateException(
                    "nspawnmgr already manages " + type + " access for " + container.getName());
        }
    }

    private void requireReachable(Container container, int port, String label) {
        String address = container.getInternalAddress();
        if (address == null || address.isEmpty()) {
            throw new IllegalStateException("No internal address known yet for " + container.getName());
        }
        if (!portProbe.isOpen(address, port)) {
            throw new IllegalStateException(
                    label + " port " + port + " isn't reachable on " + container.getName() + " right now");
        }
    }
}
