package com.nspawnmgr.service;

import com.nspawnmgr.cli.ContainerCliExecutor;
import com.nspawnmgr.cli.ContainerFilesystemBrowser;
import com.nspawnmgr.cli.ContainerFilesystemProvisioner;
import com.nspawnmgr.cli.FileEntry;
import com.nspawnmgr.cli.RemoteSftpBrowser;
import com.nspawnmgr.domain.Container;
import com.nspawnmgr.domain.ContainerBackend;
import com.nspawnmgr.domain.ContainerKind;
import org.springframework.stereotype.Service;

import java.nio.file.Path;
import java.util.Comparator;
import java.util.List;
import java.util.regex.Pattern;

/**
 * Browse/upload/download a container's filesystem - backs the per-container Files page. Two
 * completely different mechanisms depending on the container, both landing on the same
 * {@link FileEntry} shape for the caller:
 *
 * <ul>
 *     <li>MANAGED nspawn/podman: a real host-visible rootfs directory, via
 *     {@link ContainerFilesystemBrowser}. Owns the one genuinely new security-critical piece this
 *     originally needed: validating a user-supplied in-rootfs path before it's ever handed to that
 *     browser, which (like every privileged-script caller in this codebase) does no validation of
 *     its own.</li>
 *     <li>QEMU VMs and EXTERNAL hosts (see {@link #needsRemoteSftp}): no host-visible rootfs at
 *     all - a QEMU VM's storage is a qcow2 disk file, and a Host is a genuinely separate machine.
 *     Real access goes over SFTP against that machine's own SSH server instead, via
 *     {@link RemoteSftpBrowser}, using a credential the user typed into the Files page's own
 *     "Connect" prompt (see {@link GuestSftpSessionStore}) - nspawnmgr never generates or stores
 *     one for either case, unlike a container it provisioned itself. Browsing here is genuinely
 *     unrestricted (not capped at the connecting account's own home directory), bounded only by
 *     that account's own OS permissions on the target - same as any real SFTP client.</li>
 * </ul>
 */
@Service
public class ContainerFileBrowserService {

    private static final Pattern SAFE_FILENAME = Pattern.compile("^[^/\\\\\\x00]+$");
    private static final int QEMU_GUEST_SSH_PORT = 22;
    private static final int DEFAULT_EXTERNAL_SSH_PORT = 22;

    private final SettingsService settingsService;
    private final ContainerFilesystemBrowser browser;
    private final ContainerFilesystemProvisioner filesystemProvisioner;
    private final RemoteSftpBrowser remoteBrowser;
    private final ContainerCliExecutor cliExecutor;

    public ContainerFileBrowserService(SettingsService settingsService, ContainerFilesystemBrowser browser,
                                        ContainerFilesystemProvisioner filesystemProvisioner,
                                        RemoteSftpBrowser remoteBrowser, ContainerCliExecutor cliExecutor) {
        this.settingsService = settingsService;
        this.browser = browser;
        this.filesystemProvisioner = filesystemProvisioner;
        this.remoteBrowser = remoteBrowser;
        this.cliExecutor = cliExecutor;
    }

    /** A QEMU VM or an EXTERNAL host - no host-visible rootfs, needs a real SFTP connection and a
     *  user-supplied credential instead. See this class's own javadoc. */
    public boolean needsRemoteSftp(Container container) {
        return container.getKind() == ContainerKind.EXTERNAL || container.getBackend() == ContainerBackend.QEMU;
    }

    /** Directories sorted first, then alphabetically within each group. */
    public List<FileEntry> list(Container container, String path, GuestSftpSessionStore.Credential credential) {
        if (needsRemoteSftp(container)) {
            GuestSftpSessionStore.Credential c = requireCredential(credential);
            RemoteTarget target = resolveTarget(container);
            String validated = validateRemoteAbsolutePath(path);
            return sorted(remoteBrowser.list(target.address(), target.port(), c.username(), c.password(), validated));
        }
        Path root = rootfsRoot(container);
        String validated = validateRelativePath(root, path);
        return sorted(browser.list(root.toString(), validated));
    }

    public byte[] download(Container container, String path, GuestSftpSessionStore.Credential credential) {
        if (needsRemoteSftp(container)) {
            GuestSftpSessionStore.Credential c = requireCredential(credential);
            RemoteTarget target = resolveTarget(container);
            String validated = validateRemoteAbsolutePath(path);
            return remoteBrowser.download(target.address(), target.port(), c.username(), c.password(), validated);
        }
        Path root = rootfsRoot(container);
        String validated = validateRelativePath(root, path);
        return browser.download(root.toString(), validated);
    }

    /** Rejects a name collision with whatever's already at the target - see
     *  nspawnmgr-upload-rootfs-file.sh, which is the actual (race-free) source of truth for this;
     *  RealContainerFilesystemBrowser/FakeContainerFilesystemBrowser both surface that as an
     *  IllegalArgumentException, which reaches the browser as a clear 400 rather than a silent
     *  overwrite. RealRemoteSftpBrowser/FakeRemoteSftpBrowser do the equivalent check themselves
     *  for the remote-SFTP case. */
    public void upload(Container container, String dir, String filename, byte[] content,
                        GuestSftpSessionStore.Credential credential) {
        String validatedFilename = validateFilename(filename);
        if (needsRemoteSftp(container)) {
            GuestSftpSessionStore.Credential c = requireCredential(credential);
            RemoteTarget target = resolveTarget(container);
            String validatedDir = validateRemoteAbsolutePath(dir);
            remoteBrowser.upload(target.address(), target.port(), c.username(), c.password(), validatedDir, validatedFilename, content);
            return;
        }
        Path root = rootfsRoot(container);
        String validatedDir = validateRelativePath(root, dir);
        browser.upload(root.toString(), validatedDir, validatedFilename, content);
    }

    /** Verifies a credential actually authenticates against the resolved target - throws on
     *  failure. Only meaningful when {@link #needsRemoteSftp} is true; backs the Files page's own
     *  "Connect" prompt. Returns the connecting user's own home directory (the real, resolved
     *  absolute path), for the Connect endpoint to hand back to the frontend to show as the
     *  browsing root - see {@link RemoteSftpBrowser#testConnection}'s own javadoc for why this
     *  shouldn't be guessed at client-side. */
    public String testConnection(Container container, String username, char[] password) {
        RemoteTarget target = resolveTarget(container);
        return remoteBrowser.testConnection(target.address(), target.port(), username, password);
    }

    private List<FileEntry> sorted(List<FileEntry> entries) {
        return entries.stream()
                .sorted(Comparator.comparing(FileEntry::directory).reversed()
                        .thenComparing(FileEntry::name, String.CASE_INSENSITIVE_ORDER))
                .toList();
    }

    private GuestSftpSessionStore.Credential requireCredential(GuestSftpSessionStore.Credential credential) {
        if (credential == null) {
            // Maps to 409 via ApiExceptionHandler - the frontend re-shows the Connect prompt on this.
            throw new IllegalStateException("Not connected - use the Connect form to enter credentials first.");
        }
        return credential;
    }

    private record RemoteTarget(String address, int port) {
    }

    /** Resolves the real address/port to open the SFTP connection against - same resolution
     *  {@code ContainerSessionService} already uses for SSH/RDP/VNC sessions against these same
     *  two container kinds, re-resolved fresh on every call rather than cached (a QEMU VM's DHCP
     *  lease, or a Host's own LAN-local hostname, can change between calls). */
    private RemoteTarget resolveTarget(Container container) {
        if (container.getBackend() == ContainerBackend.QEMU) {
            String address = cliExecutor.getInternalAddress(container.getName(), ContainerBackend.QEMU);
            if (address.isBlank()) {
                throw new IllegalStateException("This VM has no address yet - it may still be booting.");
            }
            return new RemoteTarget(address, QEMU_GUEST_SSH_PORT);
        }
        String address = cliExecutor.resolveHostname(container.getHostname());
        Integer configuredPort = container.getExternalSshPort();
        int port = configuredPort != null ? configuredPort : DEFAULT_EXTERNAL_SSH_PORT;
        return new RemoteTarget(address, port);
    }

    private Path rootfsRoot(Container container) {
        if (container.getBackend() == ContainerBackend.PODMAN) {
            return Path.of(filesystemProvisioner.mountPodmanContainer(container.getName()));
        }
        return Path.of(settingsService.nspawnMachinesDir(), container.getName());
    }

    /**
     * Validates a user-supplied relative directory/file path before it's ever joined onto the
     * container's rootfs root and handed to a privileged script. Two layers:
     * <ol>
     *     <li>Reject any ".." segment, a leading "/" or "\" (absolute), and null bytes, before
     *     doing any filesystem resolution.</li>
     *     <li>Resolve {@code root.resolve(relativePath).normalize()} and verify the result still
     *     starts with {@code root} - defense in depth against anything the segment check missed,
     *     since this is the first place the app resolves a user-controlled path toward a
     *     privileged filesystem operation and nothing downstream validates it either.</li>
     * </ol>
     * Returns the root-relative form of the validated, normalized path - the caller (and
     * {@link ContainerFilesystemBrowser}) always re-joins this onto the root itself, never does
     * path math with the raw input again.
     */
    private String validateRelativePath(Path root, String relativePath) {
        String segmentChecked = validateSegments(relativePath);
        if (segmentChecked.isEmpty()) {
            return "";
        }
        Path resolved = root.resolve(segmentChecked).normalize();
        if (!resolved.equals(root) && !resolved.startsWith(root)) {
            throw new IllegalArgumentException("Invalid path: escapes the container's filesystem");
        }
        return root.relativize(resolved).toString();
    }

    /**
     * Validates a user-supplied path for a remote SFTP target, where there's no local {@link Path}
     * root to resolve/normalize against and - per the user's explicit choice - no artificial
     * home-directory cap either: the remote SSH account's own OS filesystem permissions are the
     * real boundary here (the user authenticated as themselves, not through anything nspawnmgr
     * generated), same as any real SFTP client. A path this account's own permissions don't allow
     * surfaces as {@link com.nspawnmgr.cli.RemotePermissionDeniedException} from
     * {@link RemoteSftpBrowser} itself, not rejected here.
     *
     * <p>Still rejects null bytes and any ".." segment - not a sandbox boundary anymore, just
     * ensuring the path this app ever composes is a clean, real absolute path (the UI itself never
     * constructs one containing "..", always computing a clean absolute string instead - see
     * files.js's own parentPath()). Requires a leading "/": every path handed to this method comes
     * from either {@code testConnection}'s own resolved home directory or a UI-computed absolute
     * path built from it, never a bare relative fragment.
     */
    private String validateRemoteAbsolutePath(String path) {
        if (path == null) {
            throw new IllegalArgumentException("Invalid path: must be absolute");
        }
        if (path.indexOf('\0') >= 0) {
            throw new IllegalArgumentException("Invalid path: contains a null byte");
        }
        if (!path.startsWith("/")) {
            throw new IllegalArgumentException("Invalid path: must be absolute");
        }
        for (String segment : path.split("/")) {
            if (segment.equals("..")) {
                throw new IllegalArgumentException("Invalid path: '..' is not allowed");
            }
        }
        return path;
    }

    private String validateSegments(String relativePath) {
        if (relativePath == null || relativePath.isBlank()) {
            return "";
        }
        if (relativePath.indexOf('\0') >= 0) {
            throw new IllegalArgumentException("Invalid path: contains a null byte");
        }
        if (relativePath.startsWith("/") || relativePath.startsWith("\\")) {
            throw new IllegalArgumentException("Invalid path: must be relative");
        }
        for (String segment : relativePath.split("[/\\\\]")) {
            if (segment.equals("..")) {
                throw new IllegalArgumentException("Invalid path: '..' is not allowed");
            }
        }
        return relativePath;
    }

    /** Upload target filename: a single path segment, never a path - permissive on characters
     *  (arbitrary uploaded filenames are legitimate), the only real security surface is that it
     *  must contain no separators. */
    private String validateFilename(String filename) {
        if (filename == null || filename.isBlank() || !SAFE_FILENAME.matcher(filename).matches()
                || filename.equals(".") || filename.equals("..")) {
            throw new IllegalArgumentException("Invalid filename: " + filename);
        }
        return filename;
    }
}
