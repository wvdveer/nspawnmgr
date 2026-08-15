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

/**
 * One PAM service (see {@link PamServiceCatalog}) on which this container's {@code
 * pam_nspawnmgr} replacement login check ({@link Container#getPamAuthSource()}) has been
 * enabled — e.g. a row with {@code serviceName="xrdp-sesman"} means RDP logins on this
 * container skip local {@code /etc/shadow} and check the configured source instead. An empty
 * set for a container means the replacement check isn't installed anywhere on it.
 */
@Entity
@Table(name = "container_pam_services", uniqueConstraints = @UniqueConstraint(
        columnNames = {"container_id", "service_name"}))
@Getter
@Setter
@NoArgsConstructor
public class ContainerPamService {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "container_id", nullable = false)
    private Container container;

    @Column(name = "service_name", nullable = false)
    private String serviceName;

    public ContainerPamService(Container container, String serviceName) {
        this.container = container;
        this.serviceName = serviceName;
    }
}
