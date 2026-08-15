package com.nspawnmgr.repository;

import com.nspawnmgr.domain.Container;
import com.nspawnmgr.domain.ContainerPortMapping;
import com.nspawnmgr.domain.PortMappingProtocol;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;

public interface ContainerPortMappingRepository extends JpaRepository<ContainerPortMapping, Long> {

    List<ContainerPortMapping> findByContainer(Container container);

    /** Eagerly fetches container: used by NetworkDiagnosticsService to name the container behind an uncovered ufw port. */
    @Query("select m from ContainerPortMapping m left join fetch m.container")
    List<ContainerPortMapping> findAllWithContainer();

    void deleteByContainer(Container container);

    @Query("select m.hostPort from ContainerPortMapping m where m.protocol = :protocol")
    List<Integer> findUsedHostPorts(@Param("protocol") PortMappingProtocol protocol);
}
