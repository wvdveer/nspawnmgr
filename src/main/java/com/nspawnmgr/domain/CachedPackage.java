package com.nspawnmgr.domain;

import javax.persistence.Column;
import javax.persistence.Entity;
import javax.persistence.EnumType;
import javax.persistence.Enumerated;
import javax.persistence.FetchType;
import javax.persistence.GeneratedValue;
import javax.persistence.GenerationType;
import javax.persistence.Id;
import javax.persistence.JoinColumn;
import javax.persistence.ManyToOne;
import javax.persistence.Table;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.Instant;

/**
 * A package (.deb/.rpm/etc.) sitting in the host-side package cache, available for any container
 * owner to install on a container they own via PackageCacheService.installOnContainer — either
 * uploaded directly by an admin, or the top-level package from one nspawnmgr auto-fetched for its
 * own SSH/RDP/VNC/desktop-manager provisioning (see
 * ContainerFilesystemProvisioner#downloadPackagesIntoContainer and
 * PackageCacheService#registerAutoFetchedIfAbsent) - transitive dependencies of the latter stay a
 * cache-directory implementation detail, not registered individually.
 */
@Entity
@Table(name = "cached_packages")
@Getter
@Setter
@NoArgsConstructor
public class CachedPackage {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Enumerated(EnumType.STRING)
    @Column(name = "package_manager", nullable = false)
    private PackageManager packageManager;

    @Column(name = "original_filename", nullable = false)
    private String originalFilename;

    /** {@code <id>_<sanitized-original-filename>} on disk under the uploaded-packages cache dir - the id
     *  prefix alone guarantees no collisions; sanitization is belt-and-braces. */
    @Column(name = "stored_filename", nullable = false)
    private String storedFilename;

    @Column(length = 1000)
    private String description;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "uploaded_by_user_id", nullable = false)
    private User uploadedBy;

    @Column(name = "size_bytes", nullable = false)
    private long sizeBytes;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    public CachedPackage(PackageManager packageManager, String originalFilename, String storedFilename,
                          String description, User uploadedBy, long sizeBytes) {
        this.packageManager = packageManager;
        this.originalFilename = originalFilename;
        this.storedFilename = storedFilename;
        this.description = description;
        this.uploadedBy = uploadedBy;
        this.sizeBytes = sizeBytes;
        this.createdAt = Instant.now();
    }
}
