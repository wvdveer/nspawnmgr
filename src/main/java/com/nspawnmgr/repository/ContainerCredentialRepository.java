package com.nspawnmgr.repository;

import com.nspawnmgr.domain.Container;
import com.nspawnmgr.domain.ContainerCredential;
import com.nspawnmgr.domain.CredentialType;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface ContainerCredentialRepository extends JpaRepository<ContainerCredential, Long> {
    Optional<ContainerCredential> findByContainerAndType(Container container, CredentialType type);

    void deleteByContainer(Container container);
}
