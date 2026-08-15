CREATE TABLE users (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    external_user_id VARCHAR(255) NOT NULL,
    username VARCHAR(255),
    email VARCHAR(255),
    full_name VARCHAR(255),
    guacamole_username VARCHAR(255),
    role VARCHAR(20) NOT NULL DEFAULT 'USER',
    created_at DATETIME NOT NULL,
    updated_at DATETIME NOT NULL,
    CONSTRAINT uq_users_external_user_id UNIQUE (external_user_id)
);

-- backend: which container/VM technology this template boots under. Only SYSTEMD_NSPAWN exists
-- today; podman/QEMU are planned as additional backends in a future release (see
-- domain/ContainerBackend.java). No seed rows here - a fresh install starts with zero templates,
-- which is what surfaces the Templates admin page's one-click "Set up alpine-minimal" button (only
-- shown when none exist yet - see TemplateService.createAlpineMinimalDefault).
CREATE TABLE templates (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    name VARCHAR(255) NOT NULL,
    description VARCHAR(1000),
    source_path VARCHAR(500) NOT NULL,
    package_manager VARCHAR(20) NOT NULL,
    install_ssh_command VARCHAR(1000),
    -- Comma-separated package-name override for this step's own host-side pre-fetch (see
    -- TemplateService#sshPackagesToPreDownload) - blank/null falls back to the package-manager
    -- default. Lets a distro package rename be fixed via configuration, not a Java code change.
    ssh_pre_download_packages VARCHAR(1000),
    -- Whether this template's own image already has openssh-server installed and enabled - lets
    -- ProvisioningService.provisionSsh skip the redundant download+install+enable steps for a
    -- container cloned from it. See Template.sshPreinstalled's own comment.
    ssh_preinstalled BOOLEAN NOT NULL DEFAULT FALSE,
    install_xrdp_command VARCHAR(1000),
    xrdp_pre_download_packages VARCHAR(1000),
    install_vnc_command VARCHAR(1000),
    vnc_pre_download_packages VARCHAR(1000),
    rdp_capable BOOLEAN NOT NULL DEFAULT TRUE,
    vnc_capable BOOLEAN NOT NULL DEFAULT TRUE,
    vnc_xstartup_template VARCHAR(2000),
    vnc_process_name_pattern VARCHAR(200),
    -- Desktop-manager install-command/pre-fetch overrides - one pair per DesktopManager value
    -- (excluding NONE), same override-then-default pattern as the ssh/xrdp/vnc columns above.
    install_gnome_command VARCHAR(1000),
    gnome_pre_download_packages VARCHAR(1000),
    install_kde_standard_command VARCHAR(1000),
    kde_standard_pre_download_packages VARCHAR(1000),
    install_xfce4_command VARCHAR(1000),
    xfce4_pre_download_packages VARCHAR(1000),
    -- Null falls back to IDENTITY (see NspawnSettingsRenderer/PrivateUsersMode) - overrides
    -- systemd-nspawn's own PrivateUsers= default for containers cloned from this template.
    private_users_mode VARCHAR(20),
    active BOOLEAN NOT NULL DEFAULT TRUE,
    backend VARCHAR(20) NOT NULL DEFAULT 'SYSTEMD_NSPAWN',
    CONSTRAINT uq_templates_name UNIQUE (name)
);

CREATE TABLE cached_packages (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    package_manager VARCHAR(20) NOT NULL,
    original_filename VARCHAR(255) NOT NULL,
    stored_filename VARCHAR(300) NOT NULL,
    description VARCHAR(1000),
    uploaded_by_user_id BIGINT NOT NULL,
    size_bytes BIGINT NOT NULL,
    created_at DATETIME NOT NULL,
    CONSTRAINT fk_cached_packages_uploaded_by FOREIGN KEY (uploaded_by_user_id) REFERENCES users (id),
    -- Lets the CI-facing install/update path (nspawnmgr-install-package.sh) upsert by identity -
    -- it always computes stored_filename as "ci-<--filename>", so repeat calls for the same
    -- package naturally collide on this and update in place, mirroring templates.name for
    -- nspawnmgr-install-template.sh. Deliberately scoped to stored_filename, not
    -- original_filename: a plain admin upload from the browser always mints a fresh
    -- UUID-prefixed stored_filename (PackageCacheService.upload), so two uploads that happen to
    -- share an original_filename never collide here - only CI's own deterministic naming does.
    CONSTRAINT uq_cached_packages_manager_stored_filename UNIQUE (package_manager, stored_filename)
);

CREATE INDEX idx_cached_packages_package_manager ON cached_packages (package_manager);

CREATE TABLE containers (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    name VARCHAR(100) NOT NULL,
    owner_id BIGINT NOT NULL,
    template_id BIGINT,
    kind VARCHAR(20) NOT NULL DEFAULT 'MANAGED',
    hostname VARCHAR(255),
    internal_address VARCHAR(45),
    state VARCHAR(20) NOT NULL,
    description VARCHAR(500),
    external_ssh_port INT,
    external_rdp_port INT,
    external_vnc_port INT,
    rdp_enabled BOOLEAN NOT NULL DEFAULT FALSE,
    rdp_security VARCHAR(20) NOT NULL DEFAULT 'ANY',
    vnc_enabled BOOLEAN NOT NULL DEFAULT FALSE,
    -- What pam_nspawnmgr checks a submitted password against, for whichever PAM services are
    -- enabled on this container (see container_pam_services below) - see Container.pamAuthSource.
    pam_auth_source VARCHAR(30) NOT NULL DEFAULT 'RDP_PASSWORD',
    -- Opaque bearer token minted the first time any PAM service is enabled - see
    -- Container.pamAuthToken.
    pam_auth_token VARCHAR(64),
    -- The Linux account SSH/RDP/VNC credentials target - see Container.primaryAccountName. Null
    -- means "use the global provisioning.adminAccountName default".
    primary_account_name VARCHAR(32),
    desktop_manager VARCHAR(20) NOT NULL DEFAULT 'NONE',
    outbound_enabled BOOLEAN NOT NULL DEFAULT TRUE,
    -- MANAGED only: the ISO (a cached_packages row with package_manager='ISO') configured to be
    -- mounted at /mnt/cdrom, if any - at most one at a time (see Container.mountedIso /
    -- ContainerLifecycleService.mountIso). No ON DELETE behavior here deliberately -
    -- PackageCacheService.delete() guards against deleting a still-mounted ISO in Java, same
    -- posture as TemplateService.delete()'s existing in-use guard.
    mounted_iso_id BIGINT,
    -- MANAGED only, and only meaningful when template_id is null - an admin-supplied fallback so
    -- package-management features (see Container.effectivePackageManager) still work on a
    -- container with no template (e.g. one found by "Discover machines"). Ignored whenever
    -- template_id is set.
    package_manager VARCHAR(20),
    guac_ssh_connection_id VARCHAR(100),
    guac_rdp_connection_id VARCHAR(100),
    guac_vnc_connection_id VARCHAR(255),
    error_message VARCHAR(2000),
    created_at DATETIME NOT NULL,
    updated_at DATETIME NOT NULL,
    CONSTRAINT uq_containers_name UNIQUE (name),
    CONSTRAINT fk_containers_owner FOREIGN KEY (owner_id) REFERENCES users (id),
    CONSTRAINT fk_containers_template FOREIGN KEY (template_id) REFERENCES templates (id),
    CONSTRAINT fk_containers_mounted_iso FOREIGN KEY (mounted_iso_id) REFERENCES cached_packages (id)
);

CREATE INDEX idx_containers_owner_id ON containers (owner_id);

CREATE TABLE container_shares (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    container_id BIGINT NOT NULL,
    user_id BIGINT NOT NULL,
    granted_at DATETIME NOT NULL,
    CONSTRAINT uq_container_shares_container_user UNIQUE (container_id, user_id),
    CONSTRAINT fk_container_shares_container FOREIGN KEY (container_id) REFERENCES containers (id) ON DELETE CASCADE,
    CONSTRAINT fk_container_shares_user FOREIGN KEY (user_id) REFERENCES users (id) ON DELETE CASCADE
);

CREATE INDEX idx_container_shares_user_id ON container_shares (user_id);

CREATE TABLE container_credentials (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    container_id BIGINT NOT NULL,
    type VARCHAR(20) NOT NULL,
    account_name VARCHAR(100) NOT NULL,
    secret_ciphertext VARCHAR(4000) NOT NULL,
    iv VARCHAR(64) NOT NULL,
    public_key VARCHAR(2000),
    created_at DATETIME NOT NULL,
    CONSTRAINT uq_container_credentials_container_type UNIQUE (container_id, type),
    CONSTRAINT fk_container_credentials_container FOREIGN KEY (container_id) REFERENCES containers (id) ON DELETE CASCADE
);

CREATE TABLE guacamole_user_secrets (
    user_id BIGINT PRIMARY KEY,
    password_ciphertext VARCHAR(2000) NOT NULL,
    iv VARCHAR(64) NOT NULL,
    CONSTRAINT fk_guacamole_user_secrets_user FOREIGN KEY (user_id) REFERENCES users (id) ON DELETE CASCADE
);

CREATE TABLE container_port_mappings (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    container_id BIGINT NOT NULL,
    host_port INT NOT NULL,
    container_port INT NOT NULL,
    protocol VARCHAR(3) NOT NULL,
    created_at DATETIME NOT NULL,
    CONSTRAINT uq_container_port_mappings_host_port_protocol UNIQUE (host_port, protocol),
    CONSTRAINT uq_container_port_mappings_container_port_protocol UNIQUE (container_id, container_port, protocol),
    CONSTRAINT fk_container_port_mappings_container FOREIGN KEY (container_id) REFERENCES containers (id) ON DELETE CASCADE
);

CREATE INDEX idx_container_port_mappings_container_id ON container_port_mappings (container_id);

CREATE TABLE container_outbound_allowlist (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    container_id BIGINT NOT NULL,
    destination_host VARCHAR(255) NOT NULL,
    destination_port INT NOT NULL,
    protocol VARCHAR(3) NOT NULL,
    created_at DATETIME NOT NULL,
    CONSTRAINT uq_container_outbound_allowlist UNIQUE (container_id, destination_host, destination_port, protocol),
    CONSTRAINT fk_container_outbound_allowlist_container FOREIGN KEY (container_id) REFERENCES containers (id) ON DELETE CASCADE
);

CREATE INDEX idx_container_outbound_allowlist_container_id ON container_outbound_allowlist (container_id);

CREATE TABLE container_pam_services (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    container_id BIGINT NOT NULL,
    service_name VARCHAR(30) NOT NULL,
    CONSTRAINT uq_container_pam_services UNIQUE (container_id, service_name),
    CONSTRAINT fk_container_pam_services_container FOREIGN KEY (container_id) REFERENCES containers (id) ON DELETE CASCADE
);

CREATE INDEX idx_container_pam_services_container_id ON container_pam_services (container_id);

CREATE TABLE container_users (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    container_id BIGINT NOT NULL,
    username VARCHAR(32) NOT NULL,
    created_at DATETIME NOT NULL,
    CONSTRAINT uq_container_users_container_username UNIQUE (container_id, username),
    CONSTRAINT fk_container_users_container FOREIGN KEY (container_id) REFERENCES containers (id) ON DELETE CASCADE
);

CREATE TABLE container_user_action_requests (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    container_id BIGINT NOT NULL,
    requested_by_id BIGINT NOT NULL,
    action_type VARCHAR(20) NOT NULL,
    username VARCHAR(32) NOT NULL,
    password_ciphertext VARCHAR(2000),
    iv VARCHAR(64),
    state VARCHAR(20) NOT NULL,
    created_at DATETIME NOT NULL,
    resolved_at DATETIME,
    resolved_by_id BIGINT,
    CONSTRAINT fk_container_user_action_requests_container FOREIGN KEY (container_id) REFERENCES containers (id) ON DELETE CASCADE,
    CONSTRAINT fk_container_user_action_requests_requested_by FOREIGN KEY (requested_by_id) REFERENCES users (id) ON DELETE CASCADE,
    CONSTRAINT fk_container_user_action_requests_resolved_by FOREIGN KEY (resolved_by_id) REFERENCES users (id) ON DELETE SET NULL
);

CREATE INDEX idx_container_user_action_requests_state ON container_user_action_requests (state);

-- script_body: bare VARCHAR (H2 makes this effectively unbounded), not CLOB - matches
-- ContainerScript.scriptBody's plain (non-@Lob) String mapping (see that field's own comment).
CREATE TABLE container_scripts (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    container_id BIGINT NOT NULL,
    name VARCHAR(255) NOT NULL,
    script_body VARCHAR NOT NULL,
    created_at DATETIME NOT NULL,
    updated_at DATETIME NOT NULL,
    CONSTRAINT uq_container_scripts_container_name UNIQUE (container_id, name),
    CONSTRAINT fk_container_scripts_container FOREIGN KEY (container_id) REFERENCES containers (id) ON DELETE CASCADE
);

CREATE INDEX idx_container_scripts_container_id ON container_scripts (container_id);

-- Singleton row (fixed id=1, never a second row) holding live-editable overrides for a subset of
-- nspawnmgr.* config — NULL in any column means "no override, use the static application.yml/env
-- var default." See SettingsService for the read side and AdminSettingsApiController for writes.
CREATE TABLE app_settings (
    id BIGINT PRIMARY KEY,
    guacamole_base_url VARCHAR(500),
    guacamole_data_source VARCHAR(100),
    host_public_address VARCHAR(255),
    -- host_external_hostname: the outward-facing hostname users outside this host should use -
    -- distinct from host_public_address above (what Guacamole's guacd uses to reach into a
    -- container).
    host_external_hostname VARCHAR(255),
    auth_cookie_name VARCHAR(255),
    auth_user_id_url VARCHAR(500),
    auth_login_url VARCHAR(500),
    auth_user_id_json VARCHAR(255),
    auth_user_username_json VARCHAR(255),
    auth_user_email_json VARCHAR(255),
    auth_user_fullname_json VARCHAR(255),
    auth_user_is_admin_json VARCHAR(255),
    auth_cache_ttl_seconds BIGINT,
    -- auth_backend/auth_smb_*/auth_required_group: live-editable overrides for auth.war's own
    -- backend config (PAM vs SMB, SMB server/domain, required group/share) - mirrored out to a
    -- shared properties file that AuthConfig reads on every request. All nullable: null means "let
    -- auth.war use its own web.xml/system-property default".
    auth_backend VARCHAR(20),
    auth_smb_server VARCHAR(255),
    auth_smb_domain VARCHAR(255),
    auth_required_group VARCHAR(255),
    auth_smb_required_share VARCHAR(255),
    auth_http_timeout_ms BIGINT,
    -- ssh_*/nspawn_*: live-editable overrides for SSH transport and nspawn filesystem paths. All
    -- nullable: null means "use the static application.yml/env-var default".
    ssh_host VARCHAR(255),
    ssh_port INT,
    ssh_username VARCHAR(255),
    ssh_password VARCHAR(255),
    ssh_private_key_path VARCHAR(500),
    ssh_connect_timeout_ms BIGINT,
    ssh_strict_host_key_checking BOOLEAN,
    nspawn_templates_dir VARCHAR(500),
    nspawn_machines_dir VARCHAR(500),
    nspawn_settings_dir VARCHAR(500),
    nspawn_privileged_scripts_dir VARCHAR(500),
    provisioning_admin_account_name VARCHAR(255),
    provisioning_rdp_password_length INT,
    -- dns_upstream_servers: comma-separated IP literals dnsmasq forwards non-.internal queries to
    -- (e.g. containers' own apt/dnf/pacman lookups) - see ContainerDnsSyncService. Null means "use
    -- the static application.yml/env-var default" (1.1.1.1,9.9.9.9), same convention as ssh_*/nspawn_* above.
    dns_upstream_servers VARCHAR(500),
    updated_at DATETIME,
    updated_by_user_id BIGINT,
    CONSTRAINT fk_app_settings_updated_by FOREIGN KEY (updated_by_user_id) REFERENCES users (id)
);

INSERT INTO app_settings (id) VALUES (1);
