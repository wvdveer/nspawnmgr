package com.nspawnmgr.repository;

import com.nspawnmgr.domain.GuacamoleUserSecret;
import org.springframework.data.jpa.repository.JpaRepository;

public interface GuacamoleUserSecretRepository extends JpaRepository<GuacamoleUserSecret, Long> {
}
