package com.nspawnmgr.cli;

import java.util.List;

/**
 * Host-side browse/transfer of a container's rootfs directory tree - backs the per-container Files
 * page. Deliberately operates on the host-visible rootfs path directly rather than
 * ContainerCliExecutor's machinectl-based exec-in-container methods, so it works whether or not the
 * container is currently running. Real implementation goes over SSH as the sudo-capable account
 * (base64-encoded, same pattern as {@link PackageCacheFilesystem#upload}); the dev profile swaps in
 * a fake that does plain local filesystem I/O.
 *
 * <p>Every method takes an already-resolved absolute root path and a relative path already
 * validated by the caller (see {@code ContainerFileBrowserService}) - path composition and
 * traversal validation are the caller's responsibility, never done here.
 */
public interface ContainerFilesystemBrowser {

    /** Lists the immediate children of {@code rootAbsolutePath}/{@code relativeDir}. */
    List<FileEntry> list(String rootAbsolutePath, String relativeDir);

    /** Reads the full content of the file at {@code rootAbsolutePath}/{@code relativePath}. */
    byte[] download(String rootAbsolutePath, String relativePath);

    /** Writes {@code content} to {@code rootAbsolutePath}/{@code relativeDir}/{@code filename}, creating {@code relativeDir} if needed. */
    void upload(String rootAbsolutePath, String relativeDir, String filename, byte[] content);
}
