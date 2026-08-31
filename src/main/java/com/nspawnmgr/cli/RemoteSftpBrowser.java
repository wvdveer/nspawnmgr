package com.nspawnmgr.cli;

import java.util.List;

/**
 * Browse/transfer files over SFTP against a real, arbitrary SSH-reachable machine nspawnmgr
 * doesn't otherwise control - a QEMU VM's own guest OS, or an EXTERNAL host entry. Unlike
 * {@link ContainerFilesystemBrowser}, there's no host-visible rootfs path to operate on directly:
 * this genuinely opens a network SSH connection to {@code address}:{@code port} using a
 * credential the caller obtained by prompting the user (see {@code GuestSftpSessionStore}),
 * never one nspawnmgr generated or stored itself.
 *
 * <p>Browsing is genuinely unrestricted - not capped at the connecting account's own home
 * directory - bounded only by that account's own OS permissions on the remote target, same as any
 * real SFTP client (a user's own file manager, the {@code sftp} CLI, etc.). Every method here
 * takes a genuine absolute path (starting with {@code /}), already validated by the caller (see
 * {@code ContainerFileBrowserService}) - path composition and traversal validation are the
 * caller's responsibility, never done here, same division as {@link ContainerFilesystemBrowser}.
 * A path the target's own permissions don't allow throws {@link RemotePermissionDeniedException},
 * not a generic failure - that's an expected, routine thing to hit now, not a bug. A rejected
 * username/password (or a guest's own SSH policy refusing the auth method entirely) throws
 * {@link RemoteAuthenticationException}, distinct again from a genuine network/protocol failure.
 */
public interface RemoteSftpBrowser {

    /** Lists the immediate children of {@code absoluteDir}. */
    List<FileEntry> list(String address, int port, String username, char[] password, String absoluteDir);

    /** Reads the full content of the file at {@code absolutePath}. */
    byte[] download(String address, int port, String username, char[] password, String absolutePath);

    /** Writes {@code content} to {@code absoluteDir}/{@code filename}, creating {@code absoluteDir} if needed. */
    void upload(String address, int port, String username, char[] password, String absoluteDir, String filename, byte[] content);

    /** Verifies the credential actually authenticates - throws (any RuntimeException) on failure.
     *  Returns the connecting user's own home directory (the real, resolved absolute path - e.g.
     *  {@code /home/frank}, not a placeholder) as a sensible place for the caller to land the user
     *  initially - not a browsing-root boundary, just the natural SFTP landing spot. */
    String testConnection(String address, int port, String username, char[] password);
}
