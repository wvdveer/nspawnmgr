package com.nspawnmgr.repository;

import com.nspawnmgr.domain.Container;
import com.nspawnmgr.domain.ContainerOutboundAllowlistEntry;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface ContainerOutboundAllowlistRepository extends JpaRepository<ContainerOutboundAllowlistEntry, Long> {

    List<ContainerOutboundAllowlistEntry> findByContainer(Container container);

    void deleteByContainer(Container container);
}
