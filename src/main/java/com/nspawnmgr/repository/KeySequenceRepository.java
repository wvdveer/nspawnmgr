package com.nspawnmgr.repository;

import com.nspawnmgr.domain.KeySequence;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface KeySequenceRepository extends JpaRepository<KeySequence, Long> {

    List<KeySequence> findAllByOrderBySortOrderAsc();
}
