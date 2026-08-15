package com.nspawnmgr.repository;

import com.nspawnmgr.domain.Container;
import com.nspawnmgr.domain.ContainerPamService;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface ContainerPamServiceRepository extends JpaRepository<ContainerPamService, Long> {

    List<ContainerPamService> findByContainer(Container container);

    void deleteByContainer(Container container);
}
