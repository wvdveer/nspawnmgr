package com.nspawnmgr.web;

import com.nspawnmgr.cli.TomcatRestartService;
import com.nspawnmgr.domain.User;
import com.nspawnmgr.guacamole.GuacamolePropertiesSchema;
import com.nspawnmgr.security.CurrentUserProvider;
import com.nspawnmgr.service.AuditLogService;
import com.nspawnmgr.service.SettingsService;
import com.nspawnmgr.service.TomcatConfigService;
import com.nspawnmgr.web.dto.GuacamolePropertiesConfigResponse;
import com.nspawnmgr.web.dto.RunGuacamoleSchemaScriptsRequest;
import com.nspawnmgr.web.dto.SettingsResponse;
import com.nspawnmgr.web.dto.TestGuacamoleDatabaseRequest;
import com.nspawnmgr.web.dto.UpdateGuacamolePropertiesRequest;
import com.nspawnmgr.web.dto.UpdateSettingsRequest;
import com.nspawnmgr.web.dto.UpdateTomcatConfigRequest;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

import java.util.Set;

@RestController
public class AdminSettingsApiController {

    /** Metadata, not settings values — always differ between before/after, not worth logging. */
    private static final Set<String> SETTINGS_DIFF_IGNORED_FIELDS = Set.of("updatedAt", "updatedByUserId");

    private final SettingsService settingsService;
    private final CurrentUserProvider currentUserProvider;
    private final TomcatRestartService tomcatRestartService;
    private final TomcatConfigService tomcatConfigService;
    private final AuditLogService auditLogService;

    public AdminSettingsApiController(SettingsService settingsService, CurrentUserProvider currentUserProvider,
                                       TomcatRestartService tomcatRestartService, TomcatConfigService tomcatConfigService,
                                       AuditLogService auditLogService) {
        this.settingsService = settingsService;
        this.currentUserProvider = currentUserProvider;
        this.tomcatRestartService = tomcatRestartService;
        this.tomcatConfigService = tomcatConfigService;
        this.auditLogService = auditLogService;
    }

    @GetMapping("/api/admin/settings")
    public SettingsResponse get() {
        return toResponse();
    }

    @PutMapping("/api/admin/settings")
    public SettingsResponse update(@RequestBody UpdateSettingsRequest request) {
        User actingAdmin = currentUserProvider.get();
        SettingsResponse before = toResponse();
        settingsService.update(new SettingsService.SettingsUpdate(
                request.guacamoleBaseUrl(), request.guacamoleDataSource(), request.hostPublicAddress(),
                request.authCookieName(), request.authUserIdUrl(), request.authLoginUrl(),
                request.authUserIdJson(), request.authUserUsernameJson(), request.authUserEmailJson(),
                request.authUserFullnameJson(), request.authUserIsAdminJson(), request.authCacheTtlSeconds(),
                request.provisioningAdminAccountName(), request.provisioningRdpPasswordLength(),
                request.authBackend(), request.authSmbServer(), request.authSmbDomain(),
                request.authRequiredGroup(), request.authSmbRequiredShare(),
                request.sshHost(), request.sshPort(), request.sshUsername(), request.sshPassword(),
                request.sshPrivateKeyPath(), request.sshConnectTimeoutMs(), request.sshStrictHostKeyChecking(),
                request.nspawnTemplatesDir(), request.nspawnMachinesDir(), request.nspawnSettingsDir(),
                request.nspawnPrivilegedScriptsDir(), request.authHttpTimeoutMs(), request.hostExternalHostname(),
                request.dnsUpstreamServers(), request.qemuVncPortRangeStart(), request.qemuVncPortRangeEnd()), actingAdmin);
        SettingsResponse after = toResponse();
        auditLogService.logSettingsChange(actingAdmin, before, after, SETTINGS_DIFF_IGNORED_FIELDS);
        return after;
    }

    @GetMapping("/api/admin/settings/guacamole-properties")
    public GuacamolePropertiesConfigResponse guacamolePropertiesConfig() {
        var status = settingsService.guacamolePropertiesStatus();
        return new GuacamolePropertiesConfigResponse(
                status.path(), status.fileExists(), status.lastModified(),
                settingsService.detectGuacamolePropertiesDatabaseType(),
                settingsService.readGuacamoleProperties(),
                GuacamolePropertiesSchema.GUACD_GROUPS,
                GuacamolePropertiesSchema.MYSQL_GROUPS,
                GuacamolePropertiesSchema.POSTGRESQL_GROUPS);
    }

    @PutMapping("/api/admin/settings/guacamole-properties")
    public void updateGuacamoleProperties(@RequestBody UpdateGuacamolePropertiesRequest request) {
        settingsService.writeGuacamoleProperties(request.databaseType(), request.values());
    }

    @PostMapping("/api/admin/settings/guacamole-properties/test-database")
    public SettingsService.GuacamoleDatabaseTestResult testGuacamoleDatabase(@RequestBody TestGuacamoleDatabaseRequest request) {
        return settingsService.testGuacamoleDatabase(request.databaseType(), request.hostname(), request.port(),
                request.database(), request.username(), request.password());
    }

    @PostMapping("/api/admin/settings/guacamole-properties/run-schema-scripts")
    public void runGuacamoleSchemaScripts(@RequestBody RunGuacamoleSchemaScriptsRequest request) {
        settingsService.runGuacamoleSchemaScripts(request.databaseType(), request.hostname(), request.port(),
                request.database(), request.username(), request.password(), request.scriptsDirectory());
    }

    @GetMapping("/api/admin/settings/report")
    public ResponseEntity<String> report() {
        return ResponseEntity.ok()
                .contentType(MediaType.TEXT_PLAIN)
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"nspawnmgr-settings-report.txt\"")
                .body(settingsService.generateReport());
    }

    @PostMapping("/api/admin/settings/restart-tomcat")
    public void restartTomcat() {
        tomcatRestartService.restart();
    }

    @GetMapping("/api/admin/settings/tomcat-config")
    public TomcatConfigService.TomcatConnectorStatus tomcatConfig() {
        return tomcatConfigService.readStatus();
    }

    @PutMapping("/api/admin/settings/tomcat-config")
    public TomcatConfigService.TomcatConnectorStatus updateTomcatConfig(@RequestBody UpdateTomcatConfigRequest request) {
        tomcatConfigService.update(request.httpPort(), request.httpsEnabled(), request.httpsPort(),
                request.certificateFile(), request.certificateKeyFile());
        return tomcatConfigService.readStatus();
    }

    private SettingsResponse toResponse() {
        var current = settingsService.current();
        return new SettingsResponse(
                settingsService.guacamoleBaseUrl(), settingsService.guacamoleDataSource(), settingsService.hostPublicAddress(),
                settingsService.authCookieName(), settingsService.authUserIdUrl(), settingsService.authLoginUrl(),
                settingsService.authUserIdJson(), settingsService.authUserUsernameJson(), settingsService.authUserEmailJson(),
                settingsService.authUserFullnameJson(), settingsService.authUserIsAdminJson(), settingsService.authCacheTtlSeconds(),
                settingsService.provisioningAdminAccountName(), settingsService.provisioningRdpPasswordLength(),
                settingsService.isAuthDetected(), settingsService.authBackend(), settingsService.authSmbServer(),
                settingsService.authSmbDomain(), settingsService.authRequiredGroup(), settingsService.authSmbRequiredShare(),
                settingsService.isGuacamoleDetected(),
                settingsService.sshHost(), settingsService.sshPort(), settingsService.sshUsername(), settingsService.sshPassword(),
                settingsService.sshPrivateKeyPath(), settingsService.sshConnectTimeoutMs(), settingsService.sshStrictHostKeyChecking(),
                settingsService.nspawnTemplatesDir(), settingsService.nspawnMachinesDir(), settingsService.nspawnSettingsDir(),
                settingsService.nspawnPrivilegedScriptsDir(), settingsService.authHttpTimeoutMs(),
                settingsService.hostExternalHostname(), settingsService.dnsUpstreamServers(),
                settingsService.qemuVncPortRangeStart(), settingsService.qemuVncPortRangeEnd(),
                current.getUpdatedAt(), current.getUpdatedByUserId());
    }
}
