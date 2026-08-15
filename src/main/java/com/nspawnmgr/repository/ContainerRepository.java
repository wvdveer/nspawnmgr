package com.nspawnmgr.repository;

import com.nspawnmgr.domain.Container;
import com.nspawnmgr.domain.ContainerKind;
import com.nspawnmgr.domain.ContainerState;
import com.nspawnmgr.domain.User;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;

public interface ContainerRepository extends JpaRepository<Container, Long> {

    Optional<Container> findByName(String name);

    /** Looks up the container a pam_nspawnmgr verify script is calling on behalf of — see
     *  PamAuthVerifyController. */
    Optional<Container> findByPamAuthToken(String pamAuthToken);

    /**
     * Fetches Template and owner eagerly: ProvisioningService.provision() runs outside any single
     * transaction (each repository call commits independently, so a failed step's ERROR state
     * still persists) and accesses container.getTemplate()/getOwner() well after this initial
     * load, which a plain findById's lazy proxies can't survive.
     */
    @Query("select c from Container c left join fetch c.template left join fetch c.owner left join fetch c.mountedIso where c.id = :id")
    Optional<Container> findByIdWithTemplate(@Param("id") Long id);

    List<Container> findByOwner(User owner);

    /**
     * Template fetched eagerly - ContainerDiscoveryService.reconcileSelfHostedInfrastructure needs
     * this to survive into ProvisioningService.provisionSshForExistingContainer's own separate
     * REQUIRES_NEW sub-transaction, which runs in a session distinct from whatever loaded this
     * list (discover() is deliberately not @Transactional - see its own javadoc). Confirmed live: a
     * plain findAll() here threw "could not initialize proxy [Template#1] - no Session" the moment
     * provisionSsh tried to read template.isSshPreinstalled(), since that lazy proxy's own
     * originating session had already closed by then.
     */
    @Query("select c from Container c left join fetch c.template")
    List<Container> findAllWithTemplate();

    /** Every managed container, regardless of who owns/shares it - still used for machine-boot-
     *  dependency selection (any machine can be a valid dependency, not just ones shared with the
     *  current viewer) and other admin-facing listings. Owner fetched eagerly. Excludes EXTERNAL
     *  rows - those live on their own Hosts page. */
    @Query("select c from Container c left join fetch c.owner where c.kind <> com.nspawnmgr.domain.ContainerKind.EXTERNAL")
    List<Container> findManagedWithOwner();

    /** As {@link #findManagedWithOwner}, but restricted to containers the given user owns or has
     *  been explicitly shared - used for the main containers list page, sorted by name. */
    @Query("select c from Container c left join fetch c.owner where c.kind <> com.nspawnmgr.domain.ContainerKind.EXTERNAL "
            + "and (c.owner = :user or exists (select 1 from ContainerShare s where s.container = c and s.user = :user)) "
            + "order by c.name")
    List<Container> findManagedVisibleToUserOrderByName(@Param("user") User user);

    /** Owner fetched eagerly - used by the admin Hosts page (kind = EXTERNAL) and HostPageController. */
    @Query("select c from Container c left join fetch c.owner where c.kind = :kind order by c.name")
    List<Container> findByKindOrderByName(@Param("kind") ContainerKind kind);

    /** Owner/template fetched eagerly for the admin pending-approvals page. */
    @Query("select c from Container c left join fetch c.owner left join fetch c.template where c.state = :state")
    List<Container> findByStateWithOwnerAndTemplate(@Param("state") ContainerState state);

    boolean existsByTemplate_Id(Long templateId);

    boolean existsByMountedIso_Id(Long isoImageId);

    /** Feeds ContainerDnsSyncService's hosts-file regeneration — RUNNING alone excludes stale/mid-restart addresses. */
    List<Container> findByKindAndStateAndInternalAddressIsNotNull(ContainerKind kind, ContainerState state);
}
