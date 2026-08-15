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
 * An explicit grant of access to a container for one user.
 * Used both for LIST-visibility sharing and, for PUBLIC containers, as the ledger of who
 * has actually been lazily granted a live Guacamole permission (see ShareService).
 */
@Entity
@Table(name = "container_shares", uniqueConstraints = @UniqueConstraint(columnNames = {"container_id", "user_id"}))
@Getter
@Setter
@NoArgsConstructor
public class ContainerShare {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "container_id", nullable = false)
    private Container container;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "user_id", nullable = false)
    private User user;

    @Column(name = "granted_at", nullable = false)
    private Instant grantedAt;

    public ContainerShare(Container container, User user) {
        this.container = container;
        this.user = user;
        this.grantedAt = Instant.now();
    }
}
