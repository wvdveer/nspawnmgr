package com.nspawnmgr.fake.cli;

import com.nspawnmgr.cli.PackageDownloadExecutor;
import com.nspawnmgr.cli.PackageDownloadUnitStatus;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * No real curl/systemd-run in dev mode - simulates a download completing gradually over {@link
 * #FAKE_DURATION} (computed from elapsed wall-clock time since {@link #start}, not a background
 * thread) so PackageDownloadService's own 2-second poll loop, and the browser's progress-bar UI
 * polling it, actually have something changing to render across a few real ticks instead of an
 * instant canned success. A URL containing the literal substring "fail" simulates a failed
 * download instead, so the FAILED-state UI path can be exercised in dev mode too.
 */
@Component
@Profile("dev")
public class FakePackageDownloadExecutor implements PackageDownloadExecutor {

    private static final Duration FAKE_DURATION = Duration.ofSeconds(20);
    private static final long FAKE_TOTAL_BYTES = 200L * 1024 * 1024;

    private final Map<String, FakeDownload> byId = new ConcurrentHashMap<>();
    private final Map<String, FakeDownload> byPath = new ConcurrentHashMap<>();

    @Override
    public Long probeContentLength(String url) {
        return url.contains("fail") ? null : FAKE_TOTAL_BYTES;
    }

    @Override
    public void start(String downloadId, String url, String targetPath) {
        FakeDownload fakeDownload = new FakeDownload(url.contains("fail"));
        byId.put(downloadId, fakeDownload);
        byPath.put(targetPath, fakeDownload);
    }

    @Override
    public long currentBytes(String targetPath) {
        FakeDownload fakeDownload = byPath.get(targetPath);
        if (fakeDownload == null) {
            return 0;
        }
        if (fakeDownload.stopped || fakeDownload.simulateFailure) {
            return fakeDownload.bytesAt(FAKE_DURATION);
        }
        Duration elapsed = Duration.between(fakeDownload.startedAt, Instant.now());
        return fakeDownload.bytesAt(elapsed.compareTo(FAKE_DURATION) > 0 ? FAKE_DURATION : elapsed);
    }

    @Override
    public PackageDownloadUnitStatus status(String downloadId) {
        FakeDownload fakeDownload = byId.get(downloadId);
        if (fakeDownload == null) {
            return PackageDownloadUnitStatus.NOT_FOUND;
        }
        if (fakeDownload.stopped) {
            return PackageDownloadUnitStatus.FAILED;
        }
        Duration elapsed = Duration.between(fakeDownload.startedAt, Instant.now());
        if (elapsed.compareTo(FAKE_DURATION) < 0) {
            return PackageDownloadUnitStatus.RUNNING;
        }
        return fakeDownload.simulateFailure ? PackageDownloadUnitStatus.FAILED : PackageDownloadUnitStatus.SUCCEEDED;
    }

    @Override
    public void stop(String downloadId) {
        FakeDownload fakeDownload = byId.get(downloadId);
        if (fakeDownload != null) {
            fakeDownload.stopped = true;
        }
    }

    private static final class FakeDownload {
        private final Instant startedAt = Instant.now();
        private final boolean simulateFailure;
        private volatile boolean stopped;

        private FakeDownload(boolean simulateFailure) {
            this.simulateFailure = simulateFailure;
        }

        private long bytesAt(Duration elapsed) {
            return FAKE_TOTAL_BYTES * elapsed.toMillis() / FAKE_DURATION.toMillis();
        }
    }
}
