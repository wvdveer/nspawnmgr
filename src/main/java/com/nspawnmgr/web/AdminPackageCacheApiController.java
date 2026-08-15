package com.nspawnmgr.web;

import com.nspawnmgr.domain.AuditAction;
import com.nspawnmgr.domain.AuditTargetType;
import com.nspawnmgr.domain.CachedPackage;
import com.nspawnmgr.domain.PackageManager;
import com.nspawnmgr.domain.Role;
import com.nspawnmgr.domain.User;
import com.nspawnmgr.cli.DownloadedPackage;
import com.nspawnmgr.security.CurrentUserProvider;
import com.nspawnmgr.service.AuditLogService;
import com.nspawnmgr.service.PackageCacheService;
import com.nspawnmgr.web.dto.AutoFetchedPackageResponse;
import com.nspawnmgr.web.dto.CachedPackageResponse;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.List;

/**
 * Upload (admin-only) and read-only listing (any authenticated user - the per-container "Install
 * package" picker on ContainerApiController needs to read this too, not just the admin page) for
 * the admin-curated package cache. See PackageCacheService for the "why" and how this differs from
 * nspawnmgr's own automatic RDP/VNC/desktop-manager package pre-fetch.
 */
@RestController
public class AdminPackageCacheApiController {

    private final PackageCacheService packageCacheService;
    private final AuditLogService auditLogService;
    private final CurrentUserProvider currentUserProvider;

    public AdminPackageCacheApiController(PackageCacheService packageCacheService, AuditLogService auditLogService,
                                           CurrentUserProvider currentUserProvider) {
        this.packageCacheService = packageCacheService;
        this.auditLogService = auditLogService;
        this.currentUserProvider = currentUserProvider;
    }

    @GetMapping("/api/admin/packages")
    public List<CachedPackageResponse> list(@RequestParam(required = false) String packageManager) {
        List<CachedPackage> packages = packageManager == null || packageManager.isBlank()
                ? packageCacheService.listAll()
                : packageCacheService.listFor(parsePackageManager(packageManager));
        return packages.stream().map(this::toResponse).toList();
    }

    @PostMapping("/api/admin/packages")
    public CachedPackageResponse upload(@RequestParam String packageManager,
                                         @RequestParam(required = false) String description,
                                         @RequestParam("file") MultipartFile file) {
        requireAdmin();
        User admin = currentUserProvider.get();
        byte[] content;
        try {
            content = file.getBytes();
        } catch (IOException e) {
            throw new UncheckedIOException("Failed to read uploaded file", e);
        }
        CachedPackage saved = packageCacheService.upload(parsePackageManager(packageManager),
                file.getOriginalFilename(), content, description, admin);
        auditLogService.log(admin, AuditAction.CREATED, AuditTargetType.PACKAGE, saved.getId(),
                saved.getOriginalFilename(), "packageManager=" + saved.getPackageManager());
        return toResponse(saved);
    }

    /**
     * Read-only, generated fresh on every call (nothing here is stored in the database) - see
     * PackageCacheService#listAutoFetched. Admin-only, unlike {@link #list}: this is a debugging/
     * visibility aid into the host's own cache directory, not something every container owner needs
     * the way the per-container "Install package" picker does.
     */
    @GetMapping("/api/admin/packages/auto-cache")
    public List<AutoFetchedPackageResponse> autoCache(@RequestParam String packageManager) {
        requireAdmin();
        List<DownloadedPackage> files = packageCacheService.listAutoFetched(parsePackageManager(packageManager));
        return files.stream().map(f -> new AutoFetchedPackageResponse(f.filename(), f.sizeBytes())).toList();
    }

    @DeleteMapping("/api/admin/packages/{id}")
    public void delete(@PathVariable Long id) {
        requireAdmin();
        CachedPackage cachedPackage = packageCacheService.getById(id);
        packageCacheService.delete(id);
        auditLogService.log(currentUserProvider.get(), AuditAction.DELETED, AuditTargetType.PACKAGE,
                id, cachedPackage.getOriginalFilename(), null);
    }

    private void requireAdmin() {
        if (currentUserProvider.get().getRole() != Role.ADMIN) {
            throw new AccessDeniedException("Only an admin may manage the package cache");
        }
    }

    private PackageManager parsePackageManager(String value) {
        try {
            return PackageManager.valueOf(value.toUpperCase());
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("Invalid package manager: " + value);
        }
    }

    private CachedPackageResponse toResponse(CachedPackage p) {
        return new CachedPackageResponse(p.getId(), p.getPackageManager().name(), p.getOriginalFilename(),
                p.getDescription(), p.getUploadedBy().getUsername(), p.getSizeBytes(), p.getCreatedAt());
    }
}
