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

@Entity
@Table(name = "containers")
@Getter
@Setter
@NoArgsConstructor
public class Container {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, unique = true)
    private String name;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "owner_id", nullable = false)
    private User owner;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private ContainerKind kind = ContainerKind.MANAGED;

    /** Only set for MANAGED containers — EXTERNAL hosts aren't cloned from a template. */
    @ManyToOne(fetch = FetchType.LAZY, optional = true)
    @JoinColumn(name = "template_id", nullable = true)
    private Template template;

    /** Only set for EXTERNAL hosts — the bare-metal host itself, or any other machine. */
    @Column(name = "hostname")
    private String hostname;

    /**
     * MANAGED only, and only meaningful when {@link #template} is null (a discovered machine, or one
     * otherwise created without a template) — an admin-supplied fallback so package-management
     * features (see {@link #effectivePackageManager()}) still work without one. Ignored whenever
     * {@code template} is set; the template's own package manager always wins.
     */
    @Enumerated(EnumType.STRING)
    @Column(name = "package_manager")
    private PackageManager packageManager;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private ContainerState state = ContainerState.CREATING;

    @Column(length = 500)
    private String description;

    /** EXTERNAL only: the target port on the external host itself — not unique (e.g. many hosts use 22). */
    @Column(name = "external_ssh_port")
    private Integer externalSshPort;

    /** EXTERNAL only: the target port on the external host itself — not unique (e.g. many hosts use 3389). */
    @Column(name = "external_rdp_port")
    private Integer externalRdpPort;

    /** EXTERNAL only: the target port on the external host itself — not unique (e.g. many hosts use 5900). */
    @Column(name = "external_vnc_port")
    private Integer externalVncPort;

    @Column(name = "rdp_enabled", nullable = false)
    private boolean rdpEnabled;

    /**
     * The Guacamole {@code security} parameter used for this container's RDP connection - some
     * xrdp versions (e.g. Fedora's, ≥0.10.6) reject the default {@code ANY} negotiation for reasons
     * unrelated to credentials; forcing {@code RDP} (classic security) is the confirmed workaround
     * until that's root-caused. Applies to MANAGED containers' real generated-credential connection
     * and prompt-credentials connections alike - never consulted for EXTERNAL hosts (the Hosts admin
     * feature), which always use {@code ANY} regardless of this field's value.
     */
    @Enumerated(EnumType.STRING)
    @Column(name = "rdp_security", nullable = false)
    private RdpSecurityMode rdpSecurity = RdpSecurityMode.ANY;

    @Column(name = "vnc_enabled", nullable = false)
    private boolean vncEnabled;

    /**
     * What {@code pam_nspawnmgr} (see PamCredentialAuthService) checks a submitted password
     * against for whichever PAM services are enabled on this container (see {@link
     * ContainerPamService}) — meaningless while no service is enabled. Defaults to {@code
     * RDP_PASSWORD} since that's the source used the moment RDP provisioning auto-enables this
     * for a Fedora container.
     */
    @Enumerated(EnumType.STRING)
    @Column(name = "pam_auth_source", nullable = false)
    private PamAuthSource pamAuthSource = PamAuthSource.RDP_PASSWORD;

    /**
     * Opaque bearer token this container presents to nspawnmgr's internal {@code
     * /internal/pam-auth/verify} endpoint to identify itself — minted lazily the first time any
     * PAM service is enabled (see PamCredentialAuthService#applyToContainer), null until then.
     */
    @Column(name = "pam_auth_token", length = 64)
    private String pamAuthToken;

    /**
     * The Linux account inside this container that its generated SSH key / RDP password / VNC
     * password credentials all target — see ProvisioningService.resolveAccountName. Null for any
     * container provisioned before this field existed, or one nspawnmgr never itself provisioned
     * (e.g. discovered) — treated as "use the global provisioning.adminAccountName default" in
     * that case, preserving pre-existing behavior exactly rather than needing a backfill.
     */
    @Column(name = "primary_account_name", length = 32)
    private String primaryAccountName;

    /** Desktop environment installed for RDP/VNC to attach to - NONE means nothing was installed. */
    @Enumerated(EnumType.STRING)
    @Column(name = "desktop_manager", nullable = false)
    private DesktopManager desktopManager = DesktopManager.NONE;

    /** MANAGED only: whether this container may reach the internet through the host's masquerade. */
    @Column(name = "outbound_enabled", nullable = false)
    private boolean outboundEnabled = true;

    /**
     * MANAGED only: the ISO (a CachedPackage with packageManager=ISO) configured to be mounted at
     * /mnt/cdrom, if any - at most one at a time (ContainerLifecycleService.mountIso auto-ejects
     * whatever's already here before mounting a new one). A persistent setting, same as custom
     * port mappings: it's rendered into the .nspawn file's own [Files] BindReadOnly= directive
     * (see NspawnSettingsRenderer) and takes effect on the next (re)start, but stays configured
     * across stop/start cycles - unlike an early design of this feature (`machinectl bind`
     * auto-ejected on every stop), stopping the container no longer clears this.
     */
    @ManyToOne(fetch = FetchType.LAZY, optional = true)
    @JoinColumn(name = "mounted_iso_id", nullable = true)
    private CachedPackage mountedIso;

    /**
     * MANAGED only: the last internal veth address resolved for this container (see
     * nspawnmgr-get-internal-address.sh). Left set after a stop/restart rather than cleared -
     * harmless, since ContainerDnsSyncService only publishes it while state is RUNNING.
     */
    @Column(name = "internal_address")
    private String internalAddress;

    @Column(name = "guac_ssh_connection_id")
    private String guacSshConnectionId;

    @Column(name = "guac_rdp_connection_id")
    private String guacRdpConnectionId;

    @Column(name = "guac_vnc_connection_id")
    private String guacVncConnectionId;

    @Column(name = "error_message", length = 2000)
    private String errorMessage;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    public Container(String name, User owner, Template template, boolean rdpEnabled, String description) {
        this.name = name;
        this.owner = owner;
        this.kind = ContainerKind.MANAGED;
        this.template = template;
        this.rdpEnabled = rdpEnabled;
        this.description = description;
        Instant now = Instant.now();
        this.createdAt = now;
        this.updatedAt = now;
    }

    /** EXTERNAL hosts start RUNNING immediately — nspawnmgr doesn't provision or control their lifecycle. */
    public static Container external(String name, User owner, String hostname) {
        Container container = new Container();
        container.name = name;
        container.owner = owner;
        container.kind = ContainerKind.EXTERNAL;
        container.hostname = hostname;
        container.state = ContainerState.RUNNING;
        Instant now = Instant.now();
        container.createdAt = now;
        container.updatedAt = now;
        return container;
    }

    /**
     * A MANAGED machine ContainerDiscoveryService found already on the host, not created through
     * nspawnmgr's own provisioning — {@code template} stays null (unknown), {@code rdpEnabled}
     * stays false (no signal to guess from), and no SSH/RDP credentials or Guacamole connections
     * exist yet. Caller is responsible for setting the real {@code state} (and {@code
     * internalAddress} if running) right after construction.
     */
    public static Container discovered(String name, User owner) {
        Container container = new Container();
        container.name = name;
        container.owner = owner;
        container.kind = ContainerKind.MANAGED;
        container.rdpEnabled = false;
        container.outboundEnabled = true;
        Instant now = Instant.now();
        container.createdAt = now;
        container.updatedAt = now;
        return container;
    }

    public void touch() {
        this.updatedAt = Instant.now();
    }

    /** The package manager to use for this container's package-management features - the template's
     *  own, if it has one, otherwise the admin-supplied fallback (see {@link #packageManager}'s own
     *  javadoc). Null for a MANAGED container with neither. */
    public PackageManager effectivePackageManager() {
        return template != null ? template.getPackageManager() : packageManager;
    }
}
