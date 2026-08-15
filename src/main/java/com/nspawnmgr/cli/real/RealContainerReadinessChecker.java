package com.nspawnmgr.cli.real;

import com.nspawnmgr.cli.ContainerCliExecutor;
import com.nspawnmgr.cli.ContainerReadinessChecker;
import com.nspawnmgr.domain.Container;
import net.schmizz.sshj.SSHClient;
import net.schmizz.sshj.connection.channel.direct.Session;
import net.schmizz.sshj.transport.verification.PromiscuousVerifier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.util.concurrent.TimeUnit;

/**
 * Dials a BOOTING container's own internal veth IP directly on port 22 (unlike
 * {@link SshRemoteExecutor}, which always targets the host's own sudo account) using its
 * provisioned credential. A connection refused/timeout/auth failure is the expected, common state
 * while a container is still booting — reported as "not ready yet" for the next poll tick to
 * retry, but still logged at WARN (rather than swallowed entirely) since a container that never
 * becomes ready otherwise leaves zero trace of why. An unresolved internal address (still booting,
 * no host0 IP assigned yet) is also "not ready yet", but is the normal case, not a warning.
 *
 * <p>Host key checking is off, same rationale as {@link SshRemoteExecutor}: every container this
 * targets is freshly provisioned by this same install, there's no pre-existing known_hosts entry to
 * check against.
 */
@Component
@Profile("!dev")
public class RealContainerReadinessChecker implements ContainerReadinessChecker {

    private static final Logger log = LoggerFactory.getLogger(RealContainerReadinessChecker.class);

    private static final int SSH_PORT = 22;
    private static final int CONNECT_TIMEOUT_MS = 5000;
    private static final int RDP_CHECK_TIMEOUT_SECONDS = 10;

    private final ContainerCliExecutor cliExecutor;

    public RealContainerReadinessChecker(ContainerCliExecutor cliExecutor) {
        this.cliExecutor = cliExecutor;
    }

    @Override
    public Readiness check(Container container, String privateKeyPem, String accountName, boolean checkRdp) {
        String internalAddress = cliExecutor.getInternalAddress(container.getName());
        if (internalAddress.isEmpty()) {
            return new Readiness(false, false, null);
        }
        SSHClient ssh = new SSHClient();
        try {
            ssh.addHostKeyVerifier(new PromiscuousVerifier());
            ssh.setConnectTimeout(CONNECT_TIMEOUT_MS);
            ssh.connect(internalAddress, SSH_PORT);
            ssh.authPublickey(accountName, ssh.loadKeys(privateKeyPem, null, null));
            boolean rdpReady = !checkRdp || isServiceActive(ssh);
            return new Readiness(true, rdpReady, internalAddress);
        } catch (IOException e) {
            log.warn("Readiness check failed for container '{}' at {}:{} (account={}): {}: {}",
                    container.getName(), internalAddress, SSH_PORT, accountName, e.getClass().getSimpleName(), e.getMessage());
            return new Readiness(false, false, internalAddress);
        } finally {
            closeQuietly(ssh);
        }
    }

    private boolean isServiceActive(SSHClient ssh) throws IOException {
        try (Session session = ssh.startSession()) {
            try (Session.Command cmd = session.exec("systemctl is-active xrdp")) {
                cmd.join(RDP_CHECK_TIMEOUT_SECONDS, TimeUnit.SECONDS);
                Integer exitStatus = cmd.getExitStatus();
                return exitStatus != null && exitStatus == 0;
            }
        }
    }

    private static void closeQuietly(SSHClient ssh) {
        try {
            ssh.close();
        } catch (IOException ignored) {
        }
    }
}
