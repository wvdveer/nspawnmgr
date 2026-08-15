package com.nspawnmgr.domain;

import javax.persistence.Column;
import javax.persistence.Entity;
import javax.persistence.FetchType;
import javax.persistence.GeneratedValue;
import javax.persistence.GenerationType;
import javax.persistence.Id;
import javax.persistence.JoinColumn;
import javax.persistence.ManyToOne;
import javax.persistence.Table;
import javax.persistence.UniqueConstraint;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.Instant;

/**
 * A named, reusable script an owner (or a shared user — see ContainerShare) has defined for a
 * container, run on demand via ContainerCliExecutor.runScript. Authored by whoever already has full
 * interactive root-shell access to this same container (via Guacamole), so this grants no new
 * privilege — see docs/administrator-guide.md's trust-boundary section.
 */
@Entity
@Table(name = "container_scripts", uniqueConstraints = {
        @UniqueConstraint(columnNames = {"container_id", "name"})
})
@Getter
@Setter
@NoArgsConstructor
public class ContainerScript {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "container_id", nullable = false)
    private Container container;

    @Column(nullable = false)
    private String name;

    // Plain String (no @Lob) - see NOTES.md-style rationale in the migration files: Hibernate's
    // schema validator only checks JDBC type-code family, not declared length, and this specific
    // combination (plain String, no @Lob) is the one confirmed live against both H2 and Postgres to
    // match VARCHAR-family columns. @Lob was tried and rejected: it maps to Postgres's legacy oid
    // large-object type by default, which doesn't match this column's plain-VARCHAR-family design
    // on any of the three vendors (see the three migration files' own script_body column type).
    @Column(name = "script_body", nullable = false)
    private String scriptBody;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    public ContainerScript(Container container, String name, String scriptBody) {
        this.container = container;
        this.name = name;
        this.scriptBody = scriptBody;
        Instant now = Instant.now();
        this.createdAt = now;
        this.updatedAt = now;
    }
}
