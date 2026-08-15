package com.nspawnmgr.repository;

import com.nspawnmgr.domain.CachedPackage;
import com.nspawnmgr.domain.PackageManager;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

import java.util.List;

public interface CachedPackageRepository extends JpaRepository<CachedPackage, Long> {

    /** uploadedBy fetched eagerly - both callers (admin page, container detail's picker) render it
     *  outside a transaction (open-in-view is off), same reasoning as ContainerRepository#findManagedWithOwner. */
    @Query("select p from CachedPackage p left join fetch p.uploadedBy where p.packageManager = :packageManager order by p.originalFilename")
    List<CachedPackage> findByPackageManagerOrderByOriginalFilename(PackageManager packageManager);

    @Query("select p from CachedPackage p left join fetch p.uploadedBy")
    List<CachedPackage> findAllWithUploadedBy();

    boolean existsByPackageManagerAndStoredFilename(PackageManager packageManager, String storedFilename);
}
