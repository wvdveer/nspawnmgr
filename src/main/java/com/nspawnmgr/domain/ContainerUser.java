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
 * A cached snapshot of one Linux username seen inside a container the last time it was actually
 * reachable — used to still show a user list while the container is stopped, since there's no way
 * to exec into it then. Replaced wholesale (delete-then-reinsert) every time a live listing
 * succeeds; never itself the source of truth while the container is RUNNING.
 */
@Entity
@Table(name = "container_users", uniqueConstraints = @UniqueConstraint(columnNames = {"container_id", "username"}))
@Getter
@Setter
@NoArgsConstructor
public class ContainerUser {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "container_id", nullable = false)
    private Container container;

    @Column(nullable = false, length = 32)
    private String username;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt = Instant.now();

    public ContainerUser(Container container, String username) {
        this.container = container;
        this.username = username;
    }
}
