package com.nspawnmgr.repository;

import com.nspawnmgr.domain.Container;
import com.nspawnmgr.domain.ContainerScript;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface ContainerScriptRepository extends JpaRepository<ContainerScript, Long> {

    List<ContainerScript> findByContainerOrderByName(Container container);

    Optional<ContainerScript> findByIdAndContainer(Long id, Container container);
}
