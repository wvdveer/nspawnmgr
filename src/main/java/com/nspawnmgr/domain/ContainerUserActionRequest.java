package com.nspawnmgr.domain;

import javax.persistence.Column;
import javax.persistence.Entity;
import javax.persistence.EnumType;
import javax.persistence.Enumerated;
import javax.persistence.FetchType;
import javax.persistence.GeneratedValue;
import javax.persistence.GenerationType;
import javax.persistence.Id;
import javax.persistence.JoinColumn;
import javax.persistence.ManyToOne;
import javax.persistence.Table;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.Instant;

/**
 * A pending (or resolved) request to add a user or change a password inside a container, created
 * whenever that action can't run immediately — either because no stored sudo secret is configured,
 * or because the container isn't RUNNING right now (exec-in-container needs it up either way). See
 * ContainerUserService for the branching logic and ADMIN approval flow.
 */
@Entity
@Table(name = "container_user_action_requests")
@Getter
@Setter
@NoArgsConstructor
public class ContainerUserActionRequest {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "container_id", nullable = false)
    private Container container;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "requested_by_id", nullable = false)
    private User requestedBy;

    @Enumerated(EnumType.STRING)
    @Column(name = "action_type", nullable = false)
    private ContainerUserActionType actionType;

    @Column(nullable = false, length = 32)
    private String username;

    /** Cleared once resolved (APPLIED or DENIED) — no reason to keep a decryptable copy around. */
    @Column(name = "password_ciphertext", length = 2000)
    private String passwordCiphertext;

    @Column(length = 64)
    private String iv;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private ContainerUserActionState state = ContainerUserActionState.PENDING;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt = Instant.now();

    @Column(name = "resolved_at")
    private Instant resolvedAt;

    @ManyToOne(fetch = FetchType.LAZY, optional = true)
    @JoinColumn(name = "resolved_by_id")
    private User resolvedBy;

    public ContainerUserActionRequest(Container container, User requestedBy, ContainerUserActionType actionType,
                                       String username, String passwordCiphertext, String iv) {
        this.container = container;
        this.requestedBy = requestedBy;
        this.actionType = actionType;
        this.username = username;
        this.passwordCiphertext = passwordCiphertext;
        this.iv = iv;
    }
}
