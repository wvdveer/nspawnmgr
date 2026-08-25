package com.nspawnmgr.service;

import com.nspawnmgr.domain.Container;
import com.nspawnmgr.domain.ContainerKind;
import com.nspawnmgr.domain.User;
import com.nspawnmgr.guacamole.GuacamoleAdminClient;
import com.nspawnmgr.repository.ContainerRepository;
import com.nspawnmgr.repository.UserRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

/**
 * Admin-managed external machines (kind = EXTERNAL) — arbitrary hosts on the network, not
 * nspawnmgr-managed containers, reachable via SSH/RDP/VNC through Guacamole's prompt-credentials
 * connections (nspawnmgr never stores a password for these). Replaces the old
 * ExternalHostSyncService/nspawnmgr.external-hosts YAML mechanism (config-only, reconciled once at
 * every startup, which would silently clobber admin-UI edits to the same rows) — this service is
 * the only way these rows get created/changed now, and the DB is the sole source of truth.
 */
@Service
public class HostService {

    private final ContainerRepository containerRepository;
    private final UserRepository userRepository;
    private final GuacamoleAdminClient guacamoleAdminClient;
    private final ShareService shareService;

    public HostService(ContainerRepository containerRepository, UserRepository userRepository,
                        GuacamoleAdminClient guacamoleAdminClient, ShareService shareService) {
        this.containerRepository = containerRepository;
        this.userRepository = userRepository;
        this.guacamoleAdminClient = guacamoleAdminClient;
        this.shareService = shareService;
    }

    public List<Container> listAll() {
        return containerRepository.findByKindOrderByName(ContainerKind.EXTERNAL);
    }

    /**
     * Fetches owner eagerly (via the same query ProvisioningService's own template-loading needs
     * this for), not a plain findById - confirmed live, AdminHostPageController.editForm's own call
     * here isn't @Transactional, and this app runs with open-in-view: false, so the Hibernate
     * session backing a plain lazy owner proxy is already closed by the time
     * admin/host-form.html tries to render host.owner.username, throwing
     * LazyInitializationException (a bare 500, no useful message) mid-render. template/mountedIso
     * also come along in the same query - always null for an EXTERNAL row, harmless extra joins.
     */
    public Container getById(Long id) {
        Container container = containerRepository.findByIdWithTemplate(id)
                .orElseThrow(() -> new IllegalArgumentException("No such host: " + id));
        if (container.getKind() != ContainerKind.EXTERNAL) {
            throw new IllegalArgumentException("No such host: " + id);
        }
        return container;
    }

    /** As {@link #getById}, keyed by name - used by HostPageController's session route, addressed
     *  by name rather than numeric ID since that URL gets shared/pasted around directly. */
    public Container getByName(String name) {
        Container container = containerRepository.findByNameWithTemplate(name)
                .orElseThrow(() -> new IllegalArgumentException("No such host: " + name));
        if (container.getKind() != ContainerKind.EXTERNAL) {
            throw new IllegalArgumentException("No such host: " + name);
        }
        return container;
    }

    @Transactional
    public Container create(String name, String hostname, String ownerUsername,
                             boolean sshEnabled, int sshPort, boolean rdpEnabled, int rdpPort,
                             boolean vncEnabled, int vncPort) {
        requireNameAvailable(name, null);
        User owner = requireUser(ownerUsername);
        Container container = Container.external(name, owner, hostname);
        applyPortsAndSync(container, sshEnabled, sshPort, rdpEnabled, rdpPort, vncEnabled, vncPort);
        Container saved = containerRepository.save(container);
        shareService.grantAccess(saved, owner);
        return saved;
    }

    @Transactional
    public Container update(Long id, String name, String hostname, String ownerUsername,
                             boolean sshEnabled, int sshPort, boolean rdpEnabled, int rdpPort,
                             boolean vncEnabled, int vncPort) {
        Container container = getById(id);
        requireNameAvailable(name, id);
        User owner = requireUser(ownerUsername);
        container.setName(name);
        container.setHostname(hostname);
        container.setOwner(owner);
        applyPortsAndSync(container, sshEnabled, sshPort, rdpEnabled, rdpPort, vncEnabled, vncPort);
        container.touch();
        Container saved = containerRepository.save(container);
        shareService.grantAccess(saved, owner);
        return saved;
    }

    @Transactional
    public void delete(Long id) {
        Container container = getById(id);
        deleteConnection(container.getGuacSshConnectionId());
        deleteConnection(container.getGuacRdpConnectionId());
        deleteConnection(container.getGuacVncConnectionId());
        shareService.revokeAllForContainer(container);
        containerRepository.delete(container);
    }

    private void applyPortsAndSync(Container container, boolean sshEnabled, int sshPort,
                                    boolean rdpEnabled, int rdpPort, boolean vncEnabled, int vncPort) {
        container.setExternalSshPort(sshEnabled ? sshPort : null);
        container.setExternalRdpPort(rdpEnabled ? rdpPort : null);
        container.setRdpEnabled(rdpEnabled);
        container.setExternalVncPort(vncEnabled ? vncPort : null);
        container.setVncEnabled(vncEnabled);

        String sshId = syncConnection(container.getGuacSshConnectionId(), container.getName() + "-ssh",
                sshEnabled, container.getHostname(), sshPort, Protocol.SSH);
        container.setGuacSshConnectionId(sshId);
        String rdpId = syncConnection(container.getGuacRdpConnectionId(), container.getName() + "-rdp",
                rdpEnabled, container.getHostname(), rdpPort, Protocol.RDP);
        container.setGuacRdpConnectionId(rdpId);
        String vncId = syncConnection(container.getGuacVncConnectionId(), container.getName() + "-vnc",
                vncEnabled, container.getHostname(), vncPort, Protocol.VNC);
        container.setGuacVncConnectionId(vncId);
    }

    private enum Protocol { SSH, RDP, VNC }

    /**
     * Creates, updates, or deletes the Guacamole connection for one protocol, per {@code enabled}.
     * RDP always uses {@code security=any} here - the per-container {@code rdpSecurity} setting
     * (see {@link com.nspawnmgr.domain.RdpSecurityMode}) only applies to MANAGED containers; an
     * EXTERNAL host isn't backed by nspawnmgr's own provisioning and has no such field to consult.
     */
    private String syncConnection(String existingId, String connectionName, boolean enabled,
                                   String hostname, int port, Protocol protocol) {
        if (!enabled) {
            deleteConnection(existingId);
            return null;
        }
        if (existingId == null) {
            return switch (protocol) {
                case SSH -> guacamoleAdminClient.createSshConnectionPromptCredentials(connectionName, hostname, port);
                case RDP -> guacamoleAdminClient.createRdpConnectionPromptCredentials(connectionName, hostname, port, "any");
                case VNC -> guacamoleAdminClient.createVncConnectionPromptCredentials(connectionName, hostname, port);
            };
        }
        switch (protocol) {
            case SSH -> guacamoleAdminClient.updateSshConnectionPromptCredentials(existingId, connectionName, hostname, port);
            case RDP -> guacamoleAdminClient.updateRdpConnectionPromptCredentials(existingId, connectionName, hostname, port, "any");
            case VNC -> guacamoleAdminClient.updateVncConnectionPromptCredentials(existingId, connectionName, hostname, port);
        }
        return existingId;
    }

    private void deleteConnection(String connectionId) {
        if (connectionId != null) {
            guacamoleAdminClient.deleteConnection(connectionId);
        }
    }

    private void requireNameAvailable(String name, Long excludingId) {
        containerRepository.findByName(name).ifPresent(existing -> {
            if (excludingId == null || !existing.getId().equals(excludingId)) {
                throw new IllegalArgumentException("A container or host named '" + name + "' already exists");
            }
        });
    }

    private User requireUser(String username) {
        return userRepository.findByUsernameIgnoreCase(username)
                .orElseThrow(() -> new IllegalArgumentException(
                        "No such user: '" + username + "' — they must have logged in at least once before being set as a host's owner"));
    }
}
