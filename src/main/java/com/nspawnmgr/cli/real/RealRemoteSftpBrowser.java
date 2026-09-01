package com.nspawnmgr.cli.real;

import com.nspawnmgr.cli.ContainerCliException;
import com.nspawnmgr.cli.FileEntry;
import com.nspawnmgr.cli.RemoteAuthenticationException;
import com.nspawnmgr.cli.RemotePermissionDeniedException;
import com.nspawnmgr.cli.RemoteSftpBrowser;
import com.nspawnmgr.service.UserMessages;
import net.schmizz.sshj.SSHClient;
import net.schmizz.sshj.common.IOUtils;
import net.schmizz.sshj.sftp.OpenMode;
import net.schmizz.sshj.sftp.RemoteFile;
import net.schmizz.sshj.sftp.RemoteResourceInfo;
import net.schmizz.sshj.sftp.Response;
import net.schmizz.sshj.sftp.SFTPClient;
import net.schmizz.sshj.sftp.SFTPException;
import net.schmizz.sshj.transport.TransportException;
import net.schmizz.sshj.transport.verification.PromiscuousVerifier;
import net.schmizz.sshj.userauth.UserAuthException;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

import java.io.Closeable;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.EnumSet;
import java.util.List;

/**
 * Real SFTP implementation of {@link RemoteSftpBrowser} - a genuine network SSH connection to an
 * arbitrary, admin/user-supplied target (a QEMU guest or an EXTERNAL host), authenticated with a
 * credential the caller obtained by prompting the user, never one nspawnmgr generated or stored
 * itself. A fresh connection per call, no pooling - same "infrequent operation, not a hot path"
 * posture {@link SshRemoteExecutor} already uses for its own (different) SSH connections.
 *
 * <p>Host key checking is off ({@link PromiscuousVerifier}), same as {@link SshRemoteExecutor} -
 * unlike that class's fixed loopback target, these are genuinely arbitrary hosts with no
 * pre-established {@code known_hosts} entry to check against either way.
 */
@Component
@Profile("!dev")
public class RealRemoteSftpBrowser implements RemoteSftpBrowser {

    private static final int CONNECT_TIMEOUT_MS = 10_000;
    // Confirmed live against a real, modern OpenSSH server (10.0p2, Debian 13): sshj's strict-KEX
    // implementation (added for the Terrapin CVE, see Proposal.java - always advertised, no public
    // Config toggle to disable it) intermittently corrupts a fresh connection's packet framing,
    // surfacing as "Broken transport; encountered EOF" (client side) / "userauth passwd: parse
    // packet: invalid format" (server side, sshd_config's own journal). A known, still-open sshj
    // issue (hierynomus/sshj#933) reports the same failure class. A brand-new SSHClient/TCP
    // connection reliably does NOT reproduce it on the very next attempt, so retrying with a fresh
    // connection is a real, working mitigation - not a blind retry-everything: see
    // isTransportCorruption below, which only retries this specific failure class and still fails
    // fast on a genuine wrong-password rejection.
    private static final int MAX_CONNECT_ATTEMPTS = 3;

    private final UserMessages messages;

    public RealRemoteSftpBrowser(UserMessages messages) {
        this.messages = messages;
    }

    @Override
    public List<FileEntry> list(String address, int port, String username, char[] password, String absoluteDir) {
        try (SSHClient ssh = connect(address, port, username, password); SFTPClient sftp = ssh.newSFTPClient()) {
            return sftp.ls(absoluteDir).stream()
                    .filter(info -> !".".equals(info.getName()) && !"..".equals(info.getName()))
                    .map(RealRemoteSftpBrowser::toFileEntry)
                    .toList();
        } catch (SFTPException e) {
            throw translateSftpException(e, "error.remote.failedToList", "error.remote.permissionDeniedList", absoluteDir, address);
        } catch (IOException e) {
            throw new ContainerCliException(messages.get("error.remote.failedToList", absoluteDir, address), e);
        }
    }

    @Override
    public byte[] download(String address, int port, String username, char[] password, String absolutePath) {
        try (SSHClient ssh = connect(address, port, username, password); SFTPClient sftp = ssh.newSFTPClient()) {
            try (RemoteFile file = sftp.open(absolutePath, EnumSet.of(OpenMode.READ))) {
                try (InputStream in = file.new RemoteFileInputStream()) {
                    return IOUtils.readFully(in).toByteArray();
                }
            }
        } catch (SFTPException e) {
            throw translateSftpException(e, "error.remote.failedToDownload", "error.remote.permissionDeniedDownload", absolutePath, address);
        } catch (IOException e) {
            throw new ContainerCliException(messages.get("error.remote.failedToDownload", absolutePath, address), e);
        }
    }

    @Override
    public void upload(String address, int port, String username, char[] password, String absoluteDir, String filename, byte[] content) {
        try (SSHClient ssh = connect(address, port, username, password); SFTPClient sftp = ssh.newSFTPClient()) {
            String target = absoluteDir.equals("/") ? "/" + filename : absoluteDir + "/" + filename;
            if (sftp.statExistence(target) != null) {
                throw new IllegalArgumentException(messages.get("error.validation.fileAlreadyExists", filename));
            }
            sftp.mkdirs(absoluteDir);
            try (RemoteFile file = sftp.open(target, EnumSet.of(OpenMode.WRITE, OpenMode.CREAT, OpenMode.TRUNC))) {
                try (OutputStream out = file.new RemoteFileOutputStream()) {
                    out.write(content);
                }
            }
        } catch (SFTPException e) {
            throw translateSftpException(e, "error.remote.failedToUpload", "error.remote.permissionDeniedUpload", filename, address);
        } catch (IOException e) {
            throw new ContainerCliException(messages.get("error.remote.failedToUpload", filename, address), e);
        }
    }

    @Override
    public String testConnection(String address, int port, String username, char[] password) {
        try (SSHClient ssh = connect(address, port, username, password); SFTPClient sftp = ssh.newSFTPClient()) {
            return sftp.canonicalize(".");
        } catch (IOException e) {
            throw new ContainerCliException(messages.get("error.remote.couldNotConnectAs", address, port, username), e);
        }
    }

    /** Now that browsing isn't capped at the connecting account's own home directory, a
     *  permission-denied response from the target (another user's home, a root-owned path) is an
     *  expected, routine thing to hit - surfaced as {@link RemotePermissionDeniedException} (a
     *  clear 403) rather than lumped in with a genuine connection/protocol failure. */
    private RuntimeException translateSftpException(SFTPException e, String failedKey, String permissionDeniedKey, Object... args) {
        if (e.getStatusCode() == Response.StatusCode.PERMISSION_DENIED) {
            return new RemotePermissionDeniedException(messages.get(permissionDeniedKey, args), e);
        }
        return new ContainerCliException(messages.get(failedKey, args), e);
    }

    private static FileEntry toFileEntry(RemoteResourceInfo info) {
        boolean directory = info.isDirectory();
        long sizeBytes = directory ? 0 : info.getAttributes().getSize();
        long mtimeEpochSeconds = info.getAttributes().getMtime();
        return new FileEntry(info.getName(), directory, sizeBytes, mtimeEpochSeconds);
    }

    private SSHClient connect(String address, int port, String username, char[] password) {
        IOException lastFailure = null;
        for (int attempt = 1; attempt <= MAX_CONNECT_ATTEMPTS; attempt++) {
            SSHClient ssh = new SSHClient();
            try {
                ssh.addHostKeyVerifier(new PromiscuousVerifier());
                ssh.setConnectTimeout(CONNECT_TIMEOUT_MS);
                ssh.connect(address, port);
                // .clone(): SSHClient#authPassword(String, char[]) unconditionally blanks out
                // (zeroes) the exact array it's given in its own finally block, win or lose - see
                // its own javadoc ("The char[] will be blanked out after use"). `password` here is
                // the caller's own array, reused across every retry attempt in this loop AND across
                // every separate list/download/upload call against the same stored session
                // credential (GuestSftpSessionStore.get() returns the same array reference every
                // time). Passing it directly meant the *first* successful use (or even a use that
                // failed only after this call ran) silently zeroed it for every use after - live
                // confirmed: after surviving the strict-KEX transport-corruption retry below, the
                // very next attempt authenticated with an already-blanked password and failed with
                // "Exhausted available authentication methods", indistinguishable from a genuinely
                // wrong password. A fresh clone per attempt means only the throwaway clone gets
                // zeroed, never the caller's own copy.
                ssh.authPassword(username, password.clone());
                return ssh;
            } catch (IOException e) {
                closeQuietly(ssh);
                lastFailure = e;
                if (!isTransportCorruption(e)) {
                    break;
                }
            }
        }
        if (isAuthenticationFailure(lastFailure)) {
            throw new RemoteAuthenticationException(
                    messages.get("error.remote.wrongUsernameOrPassword", username, address, port),
                    lastFailure);
        }
        throw new ContainerCliException(messages.get("error.remote.failedToEstablishSshConnection", address, port), lastFailure);
    }

    /** True for the specific sshj strict-KEX transport corruption class this retries (see
     *  MAX_CONNECT_ATTEMPTS's own comment) - false for a genuine credential rejection, which
     *  should fail fast rather than retry the same wrong password. */
    private static boolean isTransportCorruption(Throwable e) {
        for (Throwable t = e; t != null; t = t.getCause()) {
            if (t instanceof TransportException) {
                return true;
            }
        }
        return false;
    }

    /** True when the server itself rejected every authentication method offered (wrong password,
     *  or a policy like {@code PermitRootLogin prohibit-password} refusing password auth outright)
     *  - distinct from a TCP/network-level failure (host unreachable, connection refused/timed
     *  out), which surfaces as some other {@link IOException} subtype instead. */
    private static boolean isAuthenticationFailure(Throwable e) {
        for (Throwable t = e; t != null; t = t.getCause()) {
            if (t instanceof UserAuthException) {
                return true;
            }
        }
        return false;
    }

    private static void closeQuietly(Closeable closeable) {
        try {
            closeable.close();
        } catch (IOException ignored) {
        }
    }
}
