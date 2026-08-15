package com.nspawnmgr.repository;

import com.nspawnmgr.domain.Template;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface TemplateRepository extends JpaRepository<Template, Long> {
    List<Template> findByActiveTrue();

    Optional<Template> findByName(String name);
}
