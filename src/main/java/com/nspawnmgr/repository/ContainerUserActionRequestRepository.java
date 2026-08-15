package com.nspawnmgr.repository;

import com.nspawnmgr.domain.ContainerUserActionRequest;
import com.nspawnmgr.domain.ContainerUserActionState;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;

public interface ContainerUserActionRequestRepository extends JpaRepository<ContainerUserActionRequest, Long> {

    @Query("select r from ContainerUserActionRequest r left join fetch r.container left join fetch r.requestedBy where r.state = :state")
    List<ContainerUserActionRequest> findByStateWithContainerAndRequestedBy(@Param("state") ContainerUserActionState state);

    @Query("select r from ContainerUserActionRequest r left join fetch r.container where r.id = :id")
    Optional<ContainerUserActionRequest> findByIdWithContainer(@Param("id") Long id);
}
