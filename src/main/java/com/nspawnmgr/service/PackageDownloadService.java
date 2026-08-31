package com.nspawnmgr.service;

import com.nspawnmgr.cli.PackageCacheFilesystem;
import com.nspawnmgr.cli.PackageDownloadExecutor;
import com.nspawnmgr.cli.PackageDownloadUnitStatus;
import com.nspawnmgr.domain.AuditAction;
import com.nspawnmgr.domain.AuditTargetType;
import com.nspawnmgr.domain.CachedPackage;
import com.nspawnmgr.domain.PackageManager;
import com.nspawnmgr.domain.User;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.net.URI;
import java.nio.file.Path;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Admin-initiated "download this URL into the package cache" flow - the URL-download counterpart
 * to {@link PackageCacheService#upload}, for files too large to buffer through a browser upload
 * (see {@code PackageDownloadExecutor}'s own javadoc). Tracks in-flight (and briefly,
 * recently-finished) downloads in an in-memory {@link #activeDownloads} map, same posture as
 * {@link ContainerScriptService#activeRuns} - nothing here is durable except the
 * {@link CachedPackage} row created once a download actually succeeds.
 *
 * <p>Unlike a script run, nothing here blocks a worker thread waiting on the transfer: {@code curl}
 * runs fully detached in its own systemd unit on the host (started and immediately returned from -
 * see {@link #start}), and {@link #pollActiveDownloads()} periodically checks each active entry's
 * progress and terminal state instead, the same shape {@link ContainerLivenessPollingService}/
 * {@link QemuAddressPollingService} already use for reconciling several rows each tick.
 */
@Service
public class PackageDownloadService {

    private static final Logger log = LoggerFactory.getLogger(PackageDownloadService.class);

    private static final java.time.Duration RETENTION = java.time.Duration.ofMinutes(10);

    private final PackageDownloadExecutor executor;
    private final PackageCacheFilesystem filesystem;
    private final PackageCacheService packageCacheService;
    private final AuditLogService auditLogService;
    private final Map<String, ActiveDownload> activeDownloads = new ConcurrentHashMap<>();

    public PackageDownloadService(PackageDownloadExecutor executor, PackageCacheFilesystem filesystem,
                                   PackageCacheService packageCacheService, AuditLogService auditLogService) {
        this.executor = executor;
        this.filesystem = filesystem;
        this.packageCacheService = packageCacheService;
        this.auditLogService = auditLogService;
    }

    /**
     * Starts a download and returns immediately with an id to poll/abort - the final stored
     * filename ({@code <downloadId>_<sanitized-filename-from-url>}, mirroring {@code upload()}'s
     * own {@code <uuid>_<sanitized-original-filename>} convention) is decided here, up front, so
     * curl can write straight to its final resting place under
     * {@code PackageCacheService.uploadedDir(packageManager)} with no separate stage-then-move step.
     */
    public String start(PackageManager packageManager, String url, String description, User admin) {
        String downloadId = UUID.randomUUID().toString();
        String filename = PackageCacheService.sanitizeFilename(filenameFromUrl(url));
        String storedFilename = downloadId + "_" + filename;
        String targetPath = packageCacheService.uploadedDir(packageManager) + "/" + storedFilename;

        Long totalBytes = executor.probeContentLength(url);
        ActiveDownload activeDownload = new ActiveDownload(packageManager, url, targetPath, storedFilename,
                filename, description, admin, totalBytes);
        activeDownloads.put(downloadId, activeDownload);
        executor.start(downloadId, url, targetPath);
        return downloadId;
    }

    public ActiveDownload status(String downloadId) {
        ActiveDownload activeDownload = activeDownloads.get(downloadId);
        if (activeDownload == null) {
            throw new IllegalArgumentException("No such download: " + downloadId);
        }
        return activeDownload;
    }

    public void abort(String downloadId) {
        ActiveDownload activeDownload = status(downloadId);
        activeDownload.aborted = true;
        executor.stop(downloadId);
    }

    @Scheduled(fixedDelay = 2000)
    public void pollActiveDownloads() {
        activeDownloads.forEach((downloadId, activeDownload) -> {
            if (activeDownload.state != PackageDownloadState.RUNNING) {
                return;
            }
            try {
                checkOne(downloadId, activeDownload);
            } catch (Exception e) {
                log.warn("Progress check failed for download {}: {}", downloadId, e.getMessage(), e);
            }
        });
    }

    private void checkOne(String downloadId, ActiveDownload activeDownload) {
        activeDownload.bytesDownloaded = executor.currentBytes(activeDownload.targetPath);
        PackageDownloadUnitStatus unitStatus = executor.status(downloadId);
        if (unitStatus == PackageDownloadUnitStatus.RUNNING) {
            return;
        }
        if (activeDownload.aborted) {
            filesystem.delete(activeDownload.targetPath);
            activeDownload.state = PackageDownloadState.ABORTED;
            activeDownload.completedAt = Instant.now();
            auditLogService.log(activeDownload.admin, AuditAction.ABORTED, AuditTargetType.PACKAGE, null,
                    activeDownload.originalFilename, "download " + downloadId + " aborted");
            return;
        }
        // A NOT_FOUND unit status doesn't necessarily mean curl failed - confirmed live, a
        // completely healthy download (curl exits 0, systemd logs "Deactivated successfully", no
        // error anywhere) can still have its transient unit garbage-collected by systemd before
        // this poll's own systemctl-show call ever sees a terminal ActiveState, even without
        // --collect (the assumption a plain, non-collected transient unit stays queryable
        // indefinitely - see nspawnmgr-download-package-start.sh's own header comment - turned out
        // to be wrong on a real host: it can vanish within this poll's 2-second interval). The
        // downloaded file on disk is durable in a way the unit's own transient bookkeeping isn't,
        // so when a known expected size matches what's actually on disk, that's treated as
        // completion regardless of what systemctl reports for the unit.
        boolean sizeConfirmsCompletion = activeDownload.totalBytes != null
                && activeDownload.bytesDownloaded == activeDownload.totalBytes;
        if (unitStatus == PackageDownloadUnitStatus.SUCCEEDED || sizeConfirmsCompletion) {
            CachedPackage saved = packageCacheService.registerDownloaded(activeDownload.packageManager,
                    activeDownload.originalFilename, activeDownload.storedFilename, activeDownload.description,
                    activeDownload.admin, activeDownload.bytesDownloaded);
            activeDownload.cachedPackageId = saved.getId();
            activeDownload.state = PackageDownloadState.COMPLETED;
            activeDownload.completedAt = Instant.now();
            auditLogService.log(activeDownload.admin, AuditAction.CREATED, AuditTargetType.PACKAGE, saved.getId(),
                    saved.getOriginalFilename(), "packageManager=" + saved.getPackageManager() + " (downloaded)");
            return;
        }
        // Genuinely FAILED, or NOT_FOUND with no on-disk evidence the transfer actually finished
        // (e.g. totalBytes was never known - the source didn't report Content-Length - so a
        // vanished unit can't be distinguished from a real failure here).
        filesystem.delete(activeDownload.targetPath);
        activeDownload.state = PackageDownloadState.FAILED;
        activeDownload.errorMessage = "Download failed - check the URL is reachable and points directly at a file.";
        activeDownload.completedAt = Instant.now();
    }

    /** Evicts finished downloads once they're old enough that nobody's still polling for them. */
    @Scheduled(fixedDelay = 600000)
    public void evictCompletedDownloads() {
        Instant cutoff = Instant.now().minus(RETENTION);
        activeDownloads.values().removeIf(d -> d.state != PackageDownloadState.RUNNING
                && d.completedAt != null && d.completedAt.isBefore(cutoff));
    }

    /** The URL's own last path segment (query string/fragment stripped), or a generic fallback if
     *  it doesn't look like one (e.g. a bare domain, or a trailing slash) - an imperfect but simple
     *  heuristic; {@link PackageCacheService#sanitizeFilename} still runs on whatever this returns. */
    private static String filenameFromUrl(String url) {
        try {
            String path = URI.create(url).getPath();
            String lastSegment = path == null ? "" : Path.of(path).getFileName() == null ? "" : Path.of(path).getFileName().toString();
            return lastSegment.isBlank() ? "download" : lastSegment;
        } catch (Exception e) {
            return "download";
        }
    }

    /** Tracks one in-flight (or recently-finished) {@link #start} call. */
    public static final class ActiveDownload {
        private final PackageManager packageManager;
        private final String url;
        private final String targetPath;
        private final String storedFilename;
        private final String originalFilename;
        private final String description;
        private final User admin;
        private final Long totalBytes;
        private volatile PackageDownloadState state = PackageDownloadState.RUNNING;
        private volatile long bytesDownloaded;
        private volatile boolean aborted;
        private volatile String errorMessage;
        private volatile Long cachedPackageId;
        private volatile Instant completedAt;

        private ActiveDownload(PackageManager packageManager, String url, String targetPath, String storedFilename,
                                String originalFilename, String description, User admin, Long totalBytes) {
            this.packageManager = packageManager;
            this.url = url;
            this.targetPath = targetPath;
            this.storedFilename = storedFilename;
            this.originalFilename = originalFilename;
            this.description = description;
            this.admin = admin;
            this.totalBytes = totalBytes;
        }

        public PackageDownloadState state() {
            return state;
        }

        public long bytesDownloaded() {
            return bytesDownloaded;
        }

        public Long totalBytes() {
            return totalBytes;
        }

        public String errorMessage() {
            return errorMessage;
        }

        public Long cachedPackageId() {
            return cachedPackageId;
        }

        public String url() {
            return url;
        }

        public User admin() {
            return admin;
        }
    }
}
