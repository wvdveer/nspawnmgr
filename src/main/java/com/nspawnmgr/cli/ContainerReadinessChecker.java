package com.nspawnmgr.cli;

import com.nspawnmgr.domain.Container;

/**
 * Checks whether a BOOTING container's SSH (and, if requested, RDP) service is actually reachable
 * yet — used by ContainerReadinessPollingService to decide when to flip BOOTING to RUNNING. Unlike
 * {@link com.nspawnmgr.cli.real.SshRemoteExecutor}, which always targets the host's own sudo
 * account, this dials the container's own forwarded SSH port using its own provisioned credential.
 */
public interface ContainerReadinessChecker {

    /**
     * @param privateKeyPem the container's decrypted SSH private key (PEM), from its {@code SSH_KEY}
     *                       {@code ContainerCredential}
     * @param accountName    the Linux account inside the container that key authenticates as
     * @param checkRdp       whether to also check RDP (xrdp) readiness over that same SSH session
     */
    Readiness check(Container container, String privateKeyPem, String accountName, boolean checkRdp);

    /** @param internalAddress the container's own veth IP this check dialed, or null if it couldn't be resolved (still booting). */
    record Readiness(boolean sshReady, boolean rdpReady, String internalAddress) {
    }
}
