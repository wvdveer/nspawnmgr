package com.nspawnmgr.service;

import com.nspawnmgr.cli.PackageCacheFilesystem;
import com.nspawnmgr.cli.PackageDownloadExecutor;
import com.nspawnmgr.cli.PackageDownloadUnitStatus;
import com.nspawnmgr.domain.CachedPackage;
import com.nspawnmgr.domain.PackageManager;
import com.nspawnmgr.domain.User;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class PackageDownloadServiceTest {

    private PackageDownloadExecutor executor;
    private PackageCacheFilesystem filesystem;
    private PackageCacheService packageCacheService;
    private PackageDownloadService service;
    private User admin;

    @BeforeEach
    void setUp() {
        executor = mock(PackageDownloadExecutor.class);
        filesystem = mock(PackageCacheFilesystem.class);
        packageCacheService = mock(PackageCacheService.class);
        when(packageCacheService.uploadedDir(PackageManager.ISO)).thenReturn("/var/cache/nspawnmgr/packages/iso/uploaded");
        service = new PackageDownloadService(executor, filesystem, packageCacheService, mock(AuditLogService.class));
        admin = new User("admin-external-id");
        admin.setId(1L);
    }

    @Test
    void startProbesSizeAndLaunchesTheDownload() {
        when(executor.probeContentLength(anyString())).thenReturn(1024L);

        String downloadId = service.start(PackageManager.ISO, "https://example.com/debian.iso", "desc", admin);

        assertThat(downloadId).isNotBlank();
        assertThat(service.status(downloadId).state()).isEqualTo(PackageDownloadState.RUNNING);
        assertThat(service.status(downloadId).totalBytes()).isEqualTo(1024L);
        verify(executor).start(eq(downloadId), eq("https://example.com/debian.iso"), anyString());
    }

    @Test
    void aSuccessfulDownloadRegistersACachedPackageAndCompletes() {
        String downloadId = service.start(PackageManager.ISO, "https://example.com/debian.iso", "desc", admin);
        when(executor.currentBytes(anyString())).thenReturn(2048L);
        when(executor.status(downloadId)).thenReturn(PackageDownloadUnitStatus.SUCCEEDED);
        CachedPackage saved = new CachedPackage(PackageManager.ISO, "debian.iso", "stored.iso", "desc", admin, 2048L);
        saved.setId(42L);
        when(packageCacheService.registerDownloaded(eq(PackageManager.ISO), anyString(), anyString(), eq("desc"), eq(admin), eq(2048L)))
                .thenReturn(saved);

        service.pollActiveDownloads();

        PackageDownloadService.ActiveDownload status = service.status(downloadId);
        assertThat(status.state()).isEqualTo(PackageDownloadState.COMPLETED);
        assertThat(status.cachedPackageId()).isEqualTo(42L);
        assertThat(status.bytesDownloaded()).isEqualTo(2048L);
        verify(filesystem, never()).delete(anyString());
    }

    @Test
    void aFailedDownloadDeletesThePartialFileAndReportsAnError() {
        String downloadId = service.start(PackageManager.ISO, "https://example.com/nope.iso", "desc", admin);
        when(executor.currentBytes(anyString())).thenReturn(100L);
        when(executor.status(downloadId)).thenReturn(PackageDownloadUnitStatus.FAILED);

        service.pollActiveDownloads();

        PackageDownloadService.ActiveDownload status = service.status(downloadId);
        assertThat(status.state()).isEqualTo(PackageDownloadState.FAILED);
        assertThat(status.errorMessage()).isNotBlank();
        verify(filesystem).delete(anyString());
        verify(packageCacheService, never()).registerDownloaded(any(), anyString(), anyString(), any(), any(), anyLong());
    }

    @Test
    void abortingStopsTheUnitAndMarksAborted() {
        String downloadId = service.start(PackageManager.ISO, "https://example.com/debian.iso", "desc", admin);

        service.abort(downloadId);
        verify(executor).stop(downloadId);

        // The next poll tick observes the unit actually stopping (a real stop() doesn't complete
        // instantly) - ABORTED wins even if the unit's own exit looks like any other failure.
        when(executor.status(downloadId)).thenReturn(PackageDownloadUnitStatus.FAILED);
        service.pollActiveDownloads();

        PackageDownloadService.ActiveDownload status = service.status(downloadId);
        assertThat(status.state()).isEqualTo(PackageDownloadState.ABORTED);
        verify(filesystem).delete(anyString());
    }

    @Test
    void stillRunningLeavesStateAloneButUpdatesProgress() {
        String downloadId = service.start(PackageManager.ISO, "https://example.com/debian.iso", "desc", admin);
        when(executor.currentBytes(anyString())).thenReturn(512L);
        when(executor.status(downloadId)).thenReturn(PackageDownloadUnitStatus.RUNNING);

        service.pollActiveDownloads();

        PackageDownloadService.ActiveDownload status = service.status(downloadId);
        assertThat(status.state()).isEqualTo(PackageDownloadState.RUNNING);
        assertThat(status.bytesDownloaded()).isEqualTo(512L);
    }

    @Test
    void statusThrowsForAnUnknownDownloadId() {
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> service.status("does-not-exist"))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
