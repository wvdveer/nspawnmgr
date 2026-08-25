package com.nspawnmgr.cli;

/**
 * Host-side URL-download operations backing {@code PackageDownloadService} - entirely separate
 * from {@link PackageCacheFilesystem#upload}, which buffers a browser-uploaded file fully in JVM
 * heap and Base64-encodes it over an SSH exec's stdin. That approach doesn't scale to a multi-GB
 * ISO; this interface instead launches {@code curl} as its own detached systemd unit directly on
 * the real host (see the real implementation's own comment for why no {@code --collect}), never
 * proxying file bytes through Tomcat's own JVM at all. Real implementation goes over SSH as the
 * sudo-capable account; the dev profile swaps in a fake that simulates progress over a few seconds
 * so the browser's own polling/progress-bar UI is actually exercised.
 */
public interface PackageDownloadExecutor {

    /**
     * Best-effort HEAD probe for the download's total size - null if the server doesn't report
     * {@code Content-Length}, the request fails, or it times out. Never throws; a caller with no
     * known total just shows an indeterminate/bytes-only progress display instead of a percentage.
     */
    Long probeContentLength(String url);

    /** Starts the download as a detached unit named after {@code downloadId} and returns
     *  immediately - does not wait for it to finish. */
    void start(String downloadId, String url, String targetPath);

    /** Current size of the partially- (or fully-) downloaded file at {@code targetPath}, or 0 if it
     *  doesn't exist yet (the download hasn't started writing). Never throws. */
    long currentBytes(String targetPath);

    /** Live status of the unit started by {@link #start} for {@code downloadId}. */
    PackageDownloadUnitStatus status(String downloadId);

    /** Kills the in-progress download's unit - best-effort, safe to call on one that's already finished. */
    void stop(String downloadId);
}
