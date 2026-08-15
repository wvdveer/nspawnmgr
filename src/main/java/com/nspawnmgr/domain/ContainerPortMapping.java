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

/** A custom inbound TCP/UDP forward an owner has added beyond the fixed SSH/RDP ports. */
@Entity
@Table(name = "container_port_mappings", uniqueConstraints = {
        @UniqueConstraint(columnNames = {"host_port", "protocol"}),
        @UniqueConstraint(columnNames = {"container_id", "container_port", "protocol"})
})
@Getter
@Setter
@NoArgsConstructor
public class ContainerPortMapping {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "container_id", nullable = false)
    private Container container;

    @Column(name = "host_port", nullable = false)
    private int hostPort;

    @Column(name = "container_port", nullable = false)
    private int containerPort;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private PortMappingProtocol protocol;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    public ContainerPortMapping(Container container, int hostPort, int containerPort, PortMappingProtocol protocol) {
        this.container = container;
        this.hostPort = hostPort;
        this.containerPort = containerPort;
        this.protocol = protocol;
        this.createdAt = Instant.now();
    }
}
