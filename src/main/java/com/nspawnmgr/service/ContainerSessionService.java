package com.nspawnmgr.service;

import com.nspawnmgr.cli.ContainerCliExecutor;
import com.nspawnmgr.domain.Container;
import com.nspawnmgr.domain.ContainerKind;
import com.nspawnmgr.domain.CredentialType;
import com.nspawnmgr.domain.User;
import com.nspawnmgr.guacamole.GuacamoleAdminClient;
import com.nspawnmgr.guacamole.GuacamoleSessionService;
import com.nspawnmgr.repository.ContainerCredentialRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.client.HttpClientErrorException;

/**
 * Orchestrates a "Connect" click: lazily grant Guacamole access if needed, mint an SSO URL.
 *
 * <p>For an EXTERNAL host, also re-resolves {@code container.hostname} to a real address and pushes
 * it into the relevant Guacamole connection on every single connect (see {@link
 * ContainerCliExecutor#resolveHostname}'s own javadoc for why this can't just be done once at Host
 * create/update time): Guacamole's own SSH/RDP/VNC client runs inside the self-hosted {@code
 * nspawnmgr} container, whose only DNS path is nspawnmgr's own dnsmasq (container names + public
 * upstream only) - it has no visibility into a LAN-local hostname the admin might have typed into
 * that field, and no way to resolve it itself. Resolving on the HOST (where the admin's own
 * hostname genuinely does resolve) and handing Guacamole a raw address instead sidesteps the
 * problem entirely, and doing it fresh on every connect (rather than once, cached) picks up a
 * DHCP-reassigned address automatically instead of needing an admin to notice and re-save the host.
 */
@Service
public class ContainerSessionService {

    private static final Logger log = LoggerFactory.getLogger(ContainerSessionService.class);

    private final ShareService shareService;
    private final GuacamoleSessionService guacamoleSessionService;
    private final GuacamoleAdminClient guacamoleAdminClient;
    private final ContainerCliExecutor cliExecutor;
    private final ContainerCredentialRepository credentialRepository;
    private final ProvisioningService provisioningService;
    private final UserMessages messages;

    public ContainerSessionService(ShareService shareService, GuacamoleSessionService guacamoleSessionService,
                                    GuacamoleAdminClient guacamoleAdminClient, ContainerCliExecutor cliExecutor,
                                    ContainerCredentialRepository credentialRepository, ProvisioningService provisioningService,
                                    UserMessages messages) {
        this.shareService = shareService;
        this.guacamoleSessionService = guacamoleSessionService;
        this.guacamoleAdminClient = guacamoleAdminClient;
        this.cliExecutor = cliExecutor;
        this.credentialRepository = credentialRepository;
        this.provisioningService = provisioningService;
        this.messages = messages;
    }

    @Transactional
    public String startSshSession(Container container, User user, String browserOrigin) {
        if (container.getGuacSshConnectionId() == null) {
            throw new IllegalStateException(messages.get("error.session.noManagedSshAccess", container.getName()));
        }
        if (container.getKind() == ContainerKind.EXTERNAL) {
            String address = cliExecutor.resolveHostname(container.getHostname());
            guacamoleAdminClient.updateSshConnectionPromptCredentials(container.getGuacSshConnectionId(),
                    container.getName() + "-ssh", address, container.getExternalSshPort());
        }
        return start(container, user, container.getGuacSshConnectionId(), browserOrigin);
    }

    @Transactional
    public String startRdpSession(Container container, User user, String browserOrigin) {
        if (!container.isRdpEnabled() || container.getGuacRdpConnectionId() == null) {
            throw new IllegalStateException(messages.get("error.session.rdpNotEnabled", container.getName()));
        }
        if (container.getKind() == ContainerKind.EXTERNAL) {
            String address = cliExecutor.resolveHostname(container.getHostname());
            guacamoleAdminClient.updateRdpConnectionPromptCredentials(container.getGuacRdpConnectionId(),
                    container.getName() + "-rdp", address, container.getExternalRdpPort(), "any");
        } else if (credentialRepository.findByContainerAndType(container, CredentialType.RDP_PASSWORD).isPresent()) {
            // Only for a container nspawnmgr actually provisioned RDP on itself - see
            // ProvisioningService#ensureLingerEnabled's own javadoc for the XDG_RUNTIME_DIR race
            // this works around.
            try {
                provisioningService.ensureLingerEnabled(container);
            } catch (Exception e) {
                log.warn("Could not ensure linger enabled for container {}: {}", container.getName(), e.getMessage());
            }
        }
        return start(container, user, container.getGuacRdpConnectionId(), browserOrigin);
    }

    @Transactional
    public String startVncSession(Container container, User user, String browserOrigin) {
        if (!container.isVncEnabled() || container.getGuacVncConnectionId() == null) {
            throw new IllegalStateException(messages.get("error.session.vncNotEnabled", container.getName()));
        }
        if (container.getKind() == ContainerKind.EXTERNAL) {
            String address = cliExecutor.resolveHostname(container.getHostname());
            guacamoleAdminClient.updateVncConnectionPromptCredentials(container.getGuacVncConnectionId(),
                    container.getName() + "-vnc", address, container.getExternalVncPort());
        } else if (credentialRepository.findByContainerAndType(container, CredentialType.VNC_PASSWORD).isPresent()) {
            // Only for a container nspawnmgr actually provisioned VNC on itself (a real generated
            // credential, not a prompt-credentials connection to some pre-existing VNC service it
            // never installed/controls) - see ProvisioningService#ensureVncServerRunning's own
            // javadoc for why this is what makes logging out of the desktop not a dead end.
            try {
                provisioningService.ensureVncServerRunning(container);
            } catch (Exception e) {
                log.warn("Could not ensure VNC server running for container {}: {}", container.getName(), e.getMessage());
            }
        }
        return start(container, user, container.getGuacVncConnectionId(), browserOrigin);
    }

    private String start(Container container, User user, String connectionId, String browserOrigin) {
        // grantAccess's own return value is used rather than re-reading user.getGuacamoleUsername()
        // afterward - confirmed live: for a user's very first-ever Guacamole-requiring action,
        // grantAccess's ensureGuacamoleUser mutates a separately re-fetched, row-locked User copy
        // (deliberately, see its own javadoc), never this `user` reference - reading the field back
        // off `user` here stayed null and NPE'd inside GuacamoleSessionService's token cache.
        // syncGuacamoleUsername *does* mutate `user` directly, so its own result (a rename, the only
        // thing it ever changes) still wins by preferring user.getGuacamoleUsername() when present.
        String guacUsername = shareService.grantAccess(container, user);
        shareService.syncGuacamoleUsername(user);
        if (user.getGuacamoleUsername() != null) {
            guacUsername = user.getGuacamoleUsername();
        }
        String guacPassword = shareService.guacamolePassword(user);
        try {
            return guacamoleSessionService.buildSessionUrl(guacUsername, guacPassword, connectionId, browserOrigin);
        } catch (HttpClientErrorException.Forbidden e) {
            // Guacamole rejected the login outright ("Invalid login") - nspawnmgr's own stored
            // guacamoleUsername/encrypted password no longer matches what Guacamole itself actually
            // has for this account (confirmed live: the two can drift out of sync, e.g. Guacamole's
            // own database got reprovisioned independently of nspawnmgr's own). Reset once and retry
            // - see ShareService.resetGuacamoleAccount's own javadoc. A second failure here is a
            // genuine problem (not this stale-link scenario) and is allowed to propagate normally.
            log.warn("Guacamole login failed for user '{}' - resetting its Guacamole account and retrying once: {}",
                    guacUsername, e.getMessage());
            String freshPassword = shareService.resetGuacamoleAccount(user);
            return guacamoleSessionService.buildSessionUrl(guacUsername, freshPassword, connectionId, browserOrigin);
        }
    }
}
