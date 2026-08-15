package com.nspawnmgr.domain;

import javax.persistence.Column;
import javax.persistence.Entity;
import javax.persistence.Id;
import javax.persistence.Table;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.Instant;

/**
 * Singleton row (always id=1, never a second row) holding live-editable overrides for a subset of
 * nspawnmgr.* config. Every override column is nullable — null means "no override, use the static
 * application.yml/env-var default." Read via SettingsService's in-memory snapshot, not queried
 * directly on the request hot path.
 */
@Entity
@Table(name = "app_settings")
@Getter
@Setter
@NoArgsConstructor
public class AppSettings {

    @Id
    private Long id;

    @Column(name = "guacamole_base_url")
    private String guacamoleBaseUrl;

    @Column(name = "guacamole_data_source")
    private String guacamoleDataSource;

    @Column(name = "host_public_address")
    private String hostPublicAddress;

    @Column(name = "host_external_hostname")
    private String hostExternalHostname;

    @Column(name = "auth_cookie_name")
    private String authCookieName;

    @Column(name = "auth_user_id_url")
    private String authUserIdUrl;

    @Column(name = "auth_login_url")
    private String authLoginUrl;

    @Column(name = "auth_user_id_json")
    private String authUserIdJson;

    @Column(name = "auth_user_username_json")
    private String authUserUsernameJson;

    @Column(name = "auth_user_email_json")
    private String authUserEmailJson;

    @Column(name = "auth_user_fullname_json")
    private String authUserFullnameJson;

    @Column(name = "auth_user_is_admin_json")
    private String authUserIsAdminJson;

    @Column(name = "auth_cache_ttl_seconds")
    private Long authCacheTtlSeconds;

    @Column(name = "provisioning_admin_account_name")
    private String provisioningAdminAccountName;

    @Column(name = "provisioning_rdp_password_length")
    private Integer provisioningRdpPasswordLength;

    @Column(name = "auth_backend")
    private String authBackend;

    @Column(name = "auth_smb_server")
    private String authSmbServer;

    @Column(name = "auth_smb_domain")
    private String authSmbDomain;

    @Column(name = "auth_required_group")
    private String authRequiredGroup;

    @Column(name = "auth_smb_required_share")
    private String authSmbRequiredShare;

    @Column(name = "ssh_host")
    private String sshHost;

    @Column(name = "ssh_port")
    private Integer sshPort;

    @Column(name = "ssh_username")
    private String sshUsername;

    @Column(name = "ssh_password")
    private String sshPassword;

    @Column(name = "ssh_private_key_path")
    private String sshPrivateKeyPath;

    @Column(name = "ssh_connect_timeout_ms")
    private Long sshConnectTimeoutMs;

    @Column(name = "ssh_strict_host_key_checking")
    private Boolean sshStrictHostKeyChecking;

    @Column(name = "nspawn_templates_dir")
    private String nspawnTemplatesDir;

    @Column(name = "nspawn_machines_dir")
    private String nspawnMachinesDir;

    @Column(name = "nspawn_settings_dir")
    private String nspawnSettingsDir;

    @Column(name = "nspawn_privileged_scripts_dir")
    private String nspawnPrivilegedScriptsDir;

    @Column(name = "auth_http_timeout_ms")
    private Long authHttpTimeoutMs;

    @Column(name = "dns_upstream_servers")
    private String dnsUpstreamServers;

    @Column(name = "updated_at")
    private Instant updatedAt;

    @Column(name = "updated_by_user_id")
    private Long updatedByUserId;
}
