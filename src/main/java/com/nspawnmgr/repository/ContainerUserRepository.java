package com.nspawnmgr.repository;

import com.nspawnmgr.domain.Container;
import com.nspawnmgr.domain.ContainerUser;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface ContainerUserRepository extends JpaRepository<ContainerUser, Long> {
    List<ContainerUser> findByContainer(Container container);

    void deleteByContainer(Container container);
}
