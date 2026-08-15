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
import javax.persistence.UniqueConstraint;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.Instant;

/**
 * A specific destination (IP + port + protocol) an owner has punched through their container's
 * otherwise-blocked outbound access, without granting general internet access. Only has any effect
 * while the container's outboundEnabled is false; a no-op otherwise since everything, including
 * other managed containers by name (see ContainerDnsSyncService), is already reachable.
 */
@Entity
@Table(name = "container_outbound_allowlist", uniqueConstraints = @UniqueConstraint(
        columnNames = {"container_id", "destination_host", "destination_port", "protocol"}))
@Getter
@Setter
@NoArgsConstructor
public class ContainerOutboundAllowlistEntry {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "container_id", nullable = false)
    private Container container;

    @Column(name = "destination_host", nullable = false)
    private String destinationHost;

    @Column(name = "destination_port", nullable = false)
    private int destinationPort;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private PortMappingProtocol protocol;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    public ContainerOutboundAllowlistEntry(Container container, String destinationHost, int destinationPort,
                                            PortMappingProtocol protocol) {
        this.container = container;
        this.destinationHost = destinationHost;
        this.destinationPort = destinationPort;
        this.protocol = protocol;
        this.createdAt = Instant.now();
    }
}
