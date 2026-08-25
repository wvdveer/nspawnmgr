package com.nspawnmgr.service;

import com.jayway.jsonpath.InvalidPathException;
import com.jayway.jsonpath.JsonPath;
import com.nspawnmgr.bootstrap.JdbcDrivers;
import com.nspawnmgr.config.AuthProperties;
import com.nspawnmgr.config.DnsProperties;
import com.nspawnmgr.config.GuacamoleProperties;
import com.nspawnmgr.config.HostProperties;
import com.nspawnmgr.config.NspawnProperties;
import com.nspawnmgr.config.ProvisioningProperties;
import com.nspawnmgr.config.SshProperties;
import com.nspawnmgr.domain.AppSettings;
import com.nspawnmgr.domain.User;
import com.nspawnmgr.guacamole.GuacamolePropertiesSchema;
import com.nspawnmgr.guacamole.GuacamoleSchemaRunner;
import com.nspawnmgr.repository.AppSettingsRepository;
import net.schmizz.sshj.SSHClient;
import net.schmizz.sshj.transport.verification.PromiscuousVerifier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.client.RestTemplate;

import java.io.FileOutputStream;
import java.io.IOException;
import java.net.InetAddress;
import java.net.UnknownHostException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Single source of truth for the subset of nspawnmgr.* config that's live-editable via
 * /admin/settings, layered over the static @ConfigurationProperties defaults (which stay in force
 * for every field this service doesn't have an override for).
 *
 * <p>Holds an in-memory snapshot rather than querying the database on every accessor call:
 * some of these fields (e.g. auth.cookieName) are read on every single HTTP request
 * (CookiePreAuthFilter), so a live JPA read per call would add a DB round-trip to the hottest path
 * in the app. The snapshot is refreshed synchronously, in place, the moment an admin's update is
 * saved — there's no TTL and no staleness window, since this service is the only writer.
 */
@Service
public class SettingsService {

    private static final Logger log = LoggerFactory.getLogger(SettingsService.class);

    private static final Pattern USERNAME_PATTERN = Pattern.compile("^[a-z_][a-z0-9_-]*$");
    private static final Pattern HOSTNAME_PATTERN = Pattern.compile("^[a-zA-Z0-9.-]+$");
    private static final Duration PROBE_TIMEOUT = Duration.ofSeconds(3);
    private static final Set<String> VALID_AUTH_BACKENDS = Set.of("pam", "smb");

    private final AppSettingsRepository appSettingsRepository;
    private final GuacamoleProperties guacamoleProperties;
    private final HostProperties hostProperties;
    private final AuthProperties authProperties;
    private final ProvisioningProperties provisioningProperties;
    private final SshProperties sshProperties;
    private final NspawnProperties nspawnProperties;
    private final DnsProperties dnsProperties;
    private final RestTemplate probeRestTemplate = restTemplateWithTimeout(PROBE_TIMEOUT);

    private static RestTemplate restTemplateWithTimeout(Duration timeout) {
        var factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout((int) timeout.toMillis());
        factory.setReadTimeout((int) timeout.toMillis());
        return new RestTemplate(factory);
    }

    private volatile AppSettings snapshot;

    public SettingsService(AppSettingsRepository appSettingsRepository,
                            GuacamoleProperties guacamoleProperties,
                            HostProperties hostProperties, AuthProperties authProperties,
                            ProvisioningProperties provisioningProperties, SshProperties sshProperties,
                            NspawnProperties nspawnProperties, DnsProperties dnsProperties) {
        this.appSettingsRepository = appSettingsRepository;
        this.guacamoleProperties = guacamoleProperties;
        this.hostProperties = hostProperties;
        this.authProperties = authProperties;
        this.provisioningProperties = provisioningProperties;
        this.sshProperties = sshProperties;
        this.nspawnProperties = nspawnProperties;
        this.dnsProperties = dnsProperties;
        this.snapshot = appSettingsRepository.findById(1L)
                .orElseThrow(() -> new IllegalStateException("app_settings row (id=1) is missing — check V4 migration ran"));
    }

    // --- Typed accessors: snapshot override if present, else the static config default ---

    public String guacamoleBaseUrl() {
        return orDefault(snapshot.getGuacamoleBaseUrl(), guacamoleProperties.baseUrl());
    }

    public String guacamoleDataSource() {
        return orDefault(snapshot.getGuacamoleDataSource(), guacamoleProperties.dataSource());
    }

    public String hostPublicAddress() {
        return orDefault(snapshot.getHostPublicAddress(), hostProperties.publicAddress());
    }

    public String hostExternalHostname() {
        return orDefault(snapshot.getHostExternalHostname(), hostProperties.externalHostname());
    }

    public String authCookieName() {
        return orDefault(snapshot.getAuthCookieName(), authProperties.cookieName());
    }

    public String authUserIdUrl() {
        return orDefault(snapshot.getAuthUserIdUrl(), authProperties.userIdUrl());
    }

    public String authLoginUrl() {
        return orDefault(snapshot.getAuthLoginUrl(), authProperties.loginUrl());
    }

    public String authUserIdJson() {
        return orDefault(snapshot.getAuthUserIdJson(), authProperties.userIdJson());
    }

    public String authUserUsernameJson() {
        return orDefault(snapshot.getAuthUserUsernameJson(), authProperties.userUsernameJson());
    }

    public String authUserEmailJson() {
        return orDefault(snapshot.getAuthUserEmailJson(), authProperties.userEmailJson());
    }

    public String authUserFullnameJson() {
        return orDefault(snapshot.getAuthUserFullnameJson(), authProperties.userFullnameJson());
    }

    public String authUserIsAdminJson() {
        return orDefault(snapshot.getAuthUserIsAdminJson(), authProperties.userIsAdminJson());
    }

    public long authCacheTtlSeconds() {
        return snapshot.getAuthCacheTtlSeconds() != null ? snapshot.getAuthCacheTtlSeconds() : authProperties.cacheTtlSeconds();
    }

    public String provisioningAdminAccountName() {
        return orDefault(snapshot.getProvisioningAdminAccountName(), provisioningProperties.adminAccountName());
    }

    public int provisioningRdpPasswordLength() {
        return snapshot.getProvisioningRdpPasswordLength() != null
                ? snapshot.getProvisioningRdpPasswordLength() : provisioningProperties.rdpPasswordLength();
    }

    /** ProvisioningService#allocateQemuVncPort's own port range (on 10.100.0.1) - no static
     *  properties fallback needed, a hardcoded default here is enough (5900-5999, the same base
     *  QEMU/nspawn's own fixed VNC_PORT constant already uses for its display-0-&gt;port-5900
     *  convention). */
    public int qemuVncPortRangeStart() {
        return snapshot.getQemuVncPortRangeStart() != null ? snapshot.getQemuVncPortRangeStart() : 5900;
    }

    public int qemuVncPortRangeEnd() {
        return snapshot.getQemuVncPortRangeEnd() != null ? snapshot.getQemuVncPortRangeEnd() : 5999;
    }

    // --- SSH transport (the sudo-capable local account RealContainerCliExecutor/
    // RealContainerFilesystemProvisioner/RealTomcatRestartService SSH into) ---

    public String sshHost() {
        return orDefault(snapshot.getSshHost(), sshProperties.host());
    }

    public int sshPort() {
        return snapshot.getSshPort() != null ? snapshot.getSshPort() : sshProperties.port();
    }

    public String sshUsername() {
        return orDefault(snapshot.getSshUsername(), sshProperties.username());
    }

    public String sshPassword() {
        return orDefault(snapshot.getSshPassword(), sshProperties.password());
    }

    public String sshPrivateKeyPath() {
        return orDefault(snapshot.getSshPrivateKeyPath(), sshProperties.privateKeyPath());
    }

    public long sshConnectTimeoutMs() {
        return snapshot.getSshConnectTimeoutMs() != null ? snapshot.getSshConnectTimeoutMs() : sshProperties.connectTimeoutMs();
    }

    public boolean sshStrictHostKeyChecking() {
        return snapshot.getSshStrictHostKeyChecking() != null
                ? snapshot.getSshStrictHostKeyChecking() : sshProperties.strictHostKeyChecking();
    }

    /** Blank password selects container-creation approval mode (see docs/administrator-guide.md &sect;3). */
    public boolean sshApprovalRequired() {
        String password = sshPassword();
        return password == null || password.isBlank();
    }

    // --- nspawn filesystem paths ---

    public String nspawnTemplatesDir() {
        return orDefault(snapshot.getNspawnTemplatesDir(), nspawnProperties.templatesDir());
    }

    public String nspawnMachinesDir() {
        return orDefault(snapshot.getNspawnMachinesDir(), nspawnProperties.machinesDir());
    }

    public String nspawnSettingsDir() {
        return orDefault(snapshot.getNspawnSettingsDir(), nspawnProperties.settingsDir());
    }

    public String nspawnPrivilegedScriptsDir() {
        return orDefault(snapshot.getNspawnPrivilegedScriptsDir(), nspawnProperties.privilegedScriptsDir());
    }

    // --- misc ---

    public long authHttpTimeoutMs() {
        return snapshot.getAuthHttpTimeoutMs() != null ? snapshot.getAuthHttpTimeoutMs() : authProperties.httpTimeoutMs();
    }

    /** Comma-separated upstream DNS server IP literals dnsmasq forwards non-.internal queries to
     *  — see {@link com.nspawnmgr.service.ContainerDnsSyncService}. */
    public String dnsUpstreamServers() {
        return orDefault(snapshot.getDnsUpstreamServers(), dnsProperties.upstreamServers());
    }

    // --- auth.war's own backend settings: no static-properties fallback exists (nspawnmgr never
    // had an opinion on these before), so these read straight off the snapshot. Null here means
    // "no override" — auth.war falls back to its own web.xml/system-property default, exactly as
    // before this feature existed.

    public String authBackend() {
        return snapshot.getAuthBackend();
    }

    public String authSmbServer() {
        return snapshot.getAuthSmbServer();
    }

    public String authSmbDomain() {
        return snapshot.getAuthSmbDomain();
    }

    public String authRequiredGroup() {
        return snapshot.getAuthRequiredGroup();
    }

    public String authSmbRequiredShare() {
        return snapshot.getAuthSmbRequiredShare();
    }

    /** Whether auth.war looks reachable (probes {@link #authLoginUrl()}) — gates whether
     *  /admin/settings shows the auth section at all. */
    public boolean isAuthDetected() {
        String url = authLoginUrl();
        return url != null && !url.isBlank() && probeReachable(url);
    }

    /** Whether Guacamole looks reachable (probes {@link #guacamoleBaseUrl()}) — gates whether
     *  /admin/settings shows the write-only Guacamole section at all. */
    public boolean isGuacamoleDetected() {
        String url = guacamoleBaseUrl();
        return url != null && !url.isBlank() && probeReachable(url);
    }

    /** Current effective settings row, for rendering the admin settings page/API response. */
    public AppSettings current() {
        return snapshot;
    }

    /**
     * A downloadable plain-text summary of every setting on /admin/settings, plus what the
     * first-boot database wizard persisted and the Guacamole structured editor's current file
     * values — every password-shaped value is replaced with {@code ********} (shown as
     * present/absent, never as its real value).
     */
    public String generateReport() {
        StringBuilder report = new StringBuilder();
        report.append("nspawnmgr settings report\n");
        report.append("Generated: ").append(Instant.now()).append("\n\n");

        appendSection(report, "Guacamole (nspawnmgr -> Guacamole API)",
                "guacamoleBaseUrl", str(guacamoleBaseUrl()), "guacamoleDataSource", str(guacamoleDataSource()));
        appendSection(report, "Host", "hostPublicAddress", str(hostPublicAddress()),
                "hostExternalHostname", str(hostExternalHostname()));
        appendSection(report, "Auth",
                "authCookieName", str(authCookieName()), "authUserIdUrl", str(authUserIdUrl()),
                "authLoginUrl", str(authLoginUrl()), "authUserIdJson", str(authUserIdJson()),
                "authUserUsernameJson", str(authUserUsernameJson()), "authUserEmailJson", str(authUserEmailJson()),
                "authUserFullnameJson", str(authUserFullnameJson()), "authUserIsAdminJson", str(authUserIsAdminJson()),
                "authCacheTtlSeconds", String.valueOf(authCacheTtlSeconds()), "authHttpTimeoutMs", String.valueOf(authHttpTimeoutMs()));
        appendSection(report, "Provisioning",
                "provisioningAdminAccountName", str(provisioningAdminAccountName()),
                "provisioningRdpPasswordLength", String.valueOf(provisioningRdpPasswordLength()));
        appendSection(report, "SSH",
                "sshHost", str(sshHost()), "sshPort", String.valueOf(sshPort()), "sshUsername", str(sshUsername()),
                "sshPassword", MASK, "sshPrivateKeyPath", str(sshPrivateKeyPath()),
                "sshConnectTimeoutMs", String.valueOf(sshConnectTimeoutMs()),
                "sshStrictHostKeyChecking", String.valueOf(sshStrictHostKeyChecking()));
        appendSection(report, "Filesystem paths (nspawn)",
                "nspawnTemplatesDir", str(nspawnTemplatesDir()), "nspawnMachinesDir", str(nspawnMachinesDir()),
                "nspawnSettingsDir", str(nspawnSettingsDir()), "nspawnPrivilegedScriptsDir", str(nspawnPrivilegedScriptsDir()));
        appendSection(report, "DNS", "dnsUpstreamServers", str(dnsUpstreamServers()));

        if (isAuthDetected()) {
            appendSection(report, "Auth (auth.war)",
                    "authBackend", str(authBackend()), "authSmbServer", str(authSmbServer()),
                    "authSmbDomain", str(authSmbDomain()), "authRequiredGroup", str(authRequiredGroup()),
                    "authSmbRequiredShare", str(authSmbRequiredShare()));
        }

        var dbSettings = com.nspawnmgr.bootstrap.DbConnectionSettings.resolve();
        appendSection(report, "Database (first-boot wizard)",
                "DB_URL", str(dbSettings.url()), "DB_USERNAME", str(dbSettings.username()),
                "DB_PASSWORD", MASK, "DB_VENDOR", str(dbSettings.vendor()));

        Map<String, String> guacamoleValues = readGuacamoleProperties();
        if (!guacamoleValues.isEmpty()) {
            report.append("== Guacamole (guacamole.properties) ==\n");
            guacamoleValues.forEach((key, value) -> report.append(key).append("=")
                    .append(key.toLowerCase().contains("password") ? MASK : value).append("\n"));
            report.append("\n");
        }

        return report.toString();
    }

    private static final String MASK = "********";

    private static String str(String value) {
        return value == null ? "" : value;
    }

    private static void appendSection(StringBuilder report, String title, String... keyValuePairs) {
        report.append("== ").append(title).append(" ==\n");
        for (int i = 0; i < keyValuePairs.length; i += 2) {
            report.append(keyValuePairs[i]).append("=").append(keyValuePairs[i + 1]).append("\n");
        }
        report.append("\n");
    }

    // --- Guacamole (structured guacamole.properties editor) ---

    private Path guacamolePropertiesPath() {
        return Path.of(guacamoleProperties.home(), "guacamole.properties");
    }

    public GuacamolePropertiesStatus guacamolePropertiesStatus() {
        Path path = guacamolePropertiesPath();
        boolean exists = Files.exists(path);
        Instant lastModified = null;
        if (exists) {
            try {
                lastModified = Files.getLastModifiedTime(path).toInstant();
            } catch (IOException e) {
                log.warn("Could not read last-modified time for {}: {}", path, e.getMessage());
            }
        }
        return new GuacamolePropertiesStatus(path.toString(), exists, lastModified);
    }

    /** Every key currently in guacamole.properties, including ones outside this schema (e.g. a
     *  hand-added extension's settings) — callers filter down to what they know how to render. */
    public Map<String, String> readGuacamoleProperties() {
        Path path = guacamolePropertiesPath();
        if (!Files.exists(path)) {
            return Map.of();
        }
        Properties props = new Properties();
        try (var in = Files.newInputStream(path)) {
            props.load(in);
        } catch (IOException e) {
            log.warn("Could not read {}: {}", path, e.getMessage());
            return Map.of();
        }
        Map<String, String> values = new LinkedHashMap<>();
        for (String key : props.stringPropertyNames()) {
            values.put(key, props.getProperty(key));
        }
        return values;
    }

    /** Best-effort guess at which database extension the file is currently configured for, so the
     *  page can pre-select the right option — prefers whichever schema has more non-blank keys
     *  present, falling back to nspawnmgr's own guacamole.data-source, then "mysql". */
    public String detectGuacamolePropertiesDatabaseType() {
        Map<String, String> current = readGuacamoleProperties();
        long mysqlCount = GuacamolePropertiesSchema.mysqlKeys().stream().filter(k -> hasValue(current, k)).count();
        long postgresqlCount = GuacamolePropertiesSchema.postgresqlKeys().stream().filter(k -> hasValue(current, k)).count();
        if (mysqlCount > postgresqlCount) {
            return "mysql";
        }
        if (postgresqlCount > mysqlCount) {
            return "postgresql";
        }
        String configured = guacamoleDataSource();
        return "postgresql".equals(configured) ? "postgresql" : "mysql";
    }

    private static boolean hasValue(Map<String, String> values, String key) {
        String v = values.get(key);
        return v != null && !v.isBlank();
    }

    /**
     * Merges {@code values} for {@code databaseType} (plus guacd) into the existing
     * guacamole.properties, preserving any key this schema doesn't know about (e.g. a different
     * auth extension's settings someone added by hand), and clearing the OTHER database
     * extension's keys so the file doesn't accumulate stale config from a previous selection. A
     * blank/missing value for a schema key removes it (falls back to that extension's own
     * documented default). Does NOT restart Tomcat — Guacamole won't see this until an admin
     * restarts it themselves.
     */
    public void writeGuacamoleProperties(String databaseType, Map<String, String> values) {
        Properties props = new Properties();
        readGuacamoleProperties().forEach(props::setProperty);

        List<String> otherDbKeys = "postgresql".equals(databaseType)
                ? GuacamolePropertiesSchema.mysqlKeys() : GuacamolePropertiesSchema.postgresqlKeys();
        otherDbKeys.forEach(props::remove);

        List<String> editableKeys = new ArrayList<>(GuacamolePropertiesSchema.guacdKeys());
        editableKeys.addAll(GuacamolePropertiesSchema.groupsFor(databaseType).stream()
                .flatMap(group -> group.fields().stream())
                .map(field -> field.key())
                .toList());
        for (String key : editableKeys) {
            String value = values.get(key);
            if (value == null || value.isBlank()) {
                props.remove(key);
            } else {
                props.setProperty(key, value);
            }
        }

        Path path = guacamolePropertiesPath();
        try {
            if (path.getParent() != null) {
                Files.createDirectories(path.getParent());
            }
            try (var out = Files.newOutputStream(path)) {
                props.store(out, "Written by nspawnmgr's /admin/settings page.");
            }
            try {
                Files.setPosixFilePermissions(path, java.nio.file.attribute.PosixFilePermissions.fromString("rw-------"));
            } catch (UnsupportedOperationException e) {
                // Non-POSIX filesystem (e.g. local Windows dev) — best-effort only, not fatal.
            }
        } catch (IOException e) {
            throw new IllegalStateException("Cannot write " + path + ": " + e.getMessage(), e);
        }
    }

    public record GuacamolePropertiesStatus(String path, boolean fileExists, Instant lastModified) {
    }

    /**
     * Tests a connection to Guacamole's own database with the *currently-entered* (not
     * necessarily saved) connection fields from the structured editor, and — if it connects —
     * checks whether the {@code guacamole-auth-jdbc} schema looks set up yet, by probing for the
     * {@code guacamole_connection} table every version of that schema defines.
     */
    public GuacamoleDatabaseTestResult testGuacamoleDatabase(String databaseType, String hostname, int port,
                                                                String database, String username, String password) {
        String url = GuacamoleSchemaRunner.jdbcUrl(databaseType, hostname, port, database);
        JdbcDrivers.load(databaseType);
        try (Connection connection = DriverManager.getConnection(url, username, password)) {
            return new GuacamoleDatabaseTestResult(true, null, GuacamoleSchemaRunner.tableExists(connection, "guacamole_connection"));
        } catch (SQLException e) {
            return new GuacamoleDatabaseTestResult(false, e.getMessage(), false);
        }
    }

    /**
     * Runs guacamole-auth-jdbc's schema scripts against Guacamole's database — see
     * {@link GuacamoleSchemaRunner} (used from the pre-Spring first-boot database wizard too, which
     * is why this logic lives there and not here).
     */
    public void runGuacamoleSchemaScripts(String databaseType, String hostname, int port, String database,
                                            String username, String password, String scriptsDirectory) {
        GuacamoleSchemaRunner.runSchemaScripts(databaseType, hostname, port, database, username, password, scriptsDirectory);
    }

    public record GuacamoleDatabaseTestResult(boolean connected, String error, boolean schemaExists) {
    }

    // --- Validated update ---

    @Transactional
    public AppSettings update(SettingsUpdate update, User actingAdmin) {
        List<String> errors = new ArrayList<>();
        validateUrlIfPresent("Guacamole base URL", update.guacamoleBaseUrl(), errors, true);
        validateUrlIfPresent("Auth user-id URL", update.authUserIdUrl(), errors, true);
        validateUrlIfPresent("Auth login URL", update.authLoginUrl(), errors, false);
        validateJsonPathIfPresent("User-ID JSON path", update.authUserIdJson(), errors);
        validateJsonPathIfPresent("Username JSON path", update.authUserUsernameJson(), errors);
        validateJsonPathIfPresent("Email JSON path", update.authUserEmailJson(), errors);
        validateJsonPathIfPresent("Full-name JSON path", update.authUserFullnameJson(), errors);
        validateJsonPathIfPresent("Admin-flag JSON path", update.authUserIsAdminJson(), errors);
        if (update.authCookieName() != null && !update.authCookieName().matches("^[!#$%&'*+\\-.^_`|~0-9A-Za-z]+$")) {
            errors.add("Cookie name contains characters not valid in an HTTP cookie name");
        }
        if (update.authCacheTtlSeconds() != null && update.authCacheTtlSeconds() <= 0) {
            errors.add("Auth cache TTL must be positive");
        }
        if (update.hostPublicAddress() != null && !HOSTNAME_PATTERN.matcher(update.hostPublicAddress()).matches()) {
            errors.add("Host public address is not a valid hostname/IP");
        }
        if (update.hostExternalHostname() != null && !HOSTNAME_PATTERN.matcher(update.hostExternalHostname()).matches()) {
            errors.add("External hostname is not a valid hostname/IP");
        }
        if (update.provisioningAdminAccountName() != null && !USERNAME_PATTERN.matcher(update.provisioningAdminAccountName()).matches()) {
            errors.add("Provisioning admin account name is not a valid POSIX username");
        }
        if (update.provisioningRdpPasswordLength() != null
                && (update.provisioningRdpPasswordLength() < 8 || update.provisioningRdpPasswordLength() > 128)) {
            errors.add("RDP password length must be between 8 and 128");
        }
        if (update.authBackend() != null && !update.authBackend().isBlank()
                && !VALID_AUTH_BACKENDS.contains(update.authBackend())) {
            errors.add("Auth backend must be 'pam' or 'smb'");
        }
        if (update.sshPort() != null && (update.sshPort() < 1 || update.sshPort() > 65535)) {
            errors.add("SSH port must be between 1 and 65535");
        }
        if (update.sshConnectTimeoutMs() != null && update.sshConnectTimeoutMs() <= 0) {
            errors.add("SSH connect timeout must be positive");
        }
        if (update.authHttpTimeoutMs() != null && update.authHttpTimeoutMs() <= 0) {
            errors.add("Auth HTTP timeout must be positive");
        }
        validateDnsUpstreamServersIfPresent(update.dnsUpstreamServers(), errors);
        validateSshIfPresent(update, errors);
        // Both null (use the 5900-5999 default) or both set - a lone start/end makes no sense.
        // >=5900 required: `-vnc host:display` addresses a display number, not a port directly
        // (display = port - 5900 - see nspawnmgr-qemu-write-unit.sh), so a port below 5900 would
        // compute a negative display number.
        if ((update.qemuVncPortRangeStart() == null) != (update.qemuVncPortRangeEnd() == null)) {
            errors.add("QEMU VNC port range must set both start and end, or neither");
        } else if (update.qemuVncPortRangeStart() != null) {
            if (update.qemuVncPortRangeStart() < 5900 || update.qemuVncPortRangeStart() > 65535) {
                errors.add("QEMU VNC port range start must be between 5900 and 65535");
            }
            if (update.qemuVncPortRangeEnd() < 5900 || update.qemuVncPortRangeEnd() > 65535) {
                errors.add("QEMU VNC port range end must be between 5900 and 65535");
            }
            if (update.qemuVncPortRangeStart() > update.qemuVncPortRangeEnd()) {
                errors.add("QEMU VNC port range start must not be after end");
            }
        }

        if (!errors.isEmpty()) {
            throw new IllegalArgumentException(String.join("; ", errors));
        }

        AppSettings entity = appSettingsRepository.findById(1L)
                .orElseThrow(() -> new IllegalStateException("app_settings row (id=1) is missing"));
        entity.setGuacamoleBaseUrl(update.guacamoleBaseUrl());
        entity.setGuacamoleDataSource(update.guacamoleDataSource());
        entity.setHostPublicAddress(update.hostPublicAddress());
        entity.setHostExternalHostname(update.hostExternalHostname());
        entity.setAuthCookieName(update.authCookieName());
        entity.setAuthUserIdUrl(update.authUserIdUrl());
        entity.setAuthLoginUrl(update.authLoginUrl());
        entity.setAuthUserIdJson(update.authUserIdJson());
        entity.setAuthUserUsernameJson(update.authUserUsernameJson());
        entity.setAuthUserEmailJson(update.authUserEmailJson());
        entity.setAuthUserFullnameJson(update.authUserFullnameJson());
        entity.setAuthUserIsAdminJson(update.authUserIsAdminJson());
        entity.setAuthCacheTtlSeconds(update.authCacheTtlSeconds());
        entity.setProvisioningAdminAccountName(update.provisioningAdminAccountName());
        entity.setProvisioningRdpPasswordLength(update.provisioningRdpPasswordLength());
        entity.setAuthBackend(update.authBackend());
        entity.setAuthSmbServer(update.authSmbServer());
        entity.setAuthSmbDomain(update.authSmbDomain());
        entity.setAuthRequiredGroup(update.authRequiredGroup());
        entity.setAuthSmbRequiredShare(update.authSmbRequiredShare());
        entity.setSshHost(update.sshHost());
        entity.setSshPort(update.sshPort());
        entity.setSshUsername(update.sshUsername());
        entity.setSshPassword(update.sshPassword());
        entity.setSshPrivateKeyPath(update.sshPrivateKeyPath());
        entity.setSshConnectTimeoutMs(update.sshConnectTimeoutMs());
        entity.setSshStrictHostKeyChecking(update.sshStrictHostKeyChecking());
        entity.setNspawnTemplatesDir(update.nspawnTemplatesDir());
        entity.setNspawnMachinesDir(update.nspawnMachinesDir());
        entity.setNspawnSettingsDir(update.nspawnSettingsDir());
        entity.setNspawnPrivilegedScriptsDir(update.nspawnPrivilegedScriptsDir());
        entity.setAuthHttpTimeoutMs(update.authHttpTimeoutMs());
        entity.setDnsUpstreamServers(update.dnsUpstreamServers());
        entity.setQemuVncPortRangeStart(update.qemuVncPortRangeStart());
        entity.setQemuVncPortRangeEnd(update.qemuVncPortRangeEnd());
        entity.setUpdatedAt(Instant.now());
        entity.setUpdatedByUserId(actingAdmin.getId());

        AppSettings saved = appSettingsRepository.save(entity);
        this.snapshot = saved;
        writeAuthSettingsFile();
        return saved;
    }

    /**
     * Narrow update for exactly one field, used by the network-diagnostics page's "Fix" action
     * for a loopback HOST_PUBLIC_ADDRESS - deliberately not routed through {@link #update} above,
     * which assigns every field of its {@link SettingsUpdate} unconditionally and would null out
     * every other setting if called with just this one populated.
     */
    @Transactional
    public void updateHostPublicAddress(String newAddress, User actingAdmin) {
        if (!HOSTNAME_PATTERN.matcher(newAddress).matches()) {
            throw new IllegalArgumentException("Host public address is not a valid hostname/IP");
        }
        AppSettings entity = appSettingsRepository.findById(1L)
                .orElseThrow(() -> new IllegalStateException("app_settings row (id=1) is missing"));
        entity.setHostPublicAddress(newAddress);
        entity.setUpdatedAt(Instant.now());
        entity.setUpdatedByUserId(actingAdmin.getId());
        this.snapshot = appSettingsRepository.save(entity);
    }

    private void validateSshIfPresent(SettingsUpdate update, List<String> errors) {
        // "Present" alone isn't enough to decide whether to run this: the settings page always
        // resubmits every field together (like every other section), so ssh.* is non-null on
        // basically every save regardless of whether the admin touched it. Only actually run the
        // connection test when a submitted value *differs* from what's already in effect —
        // otherwise an unrelated save (e.g. changing the auth cookie name) would be blocked by SSH
        // settings that happen to be unreachable for reasons that have nothing to do with this save.
        boolean sshChanged = differs(update.sshHost(), sshHost()) || differs(update.sshPort(), sshPort())
                || differs(update.sshUsername(), sshUsername()) || differs(update.sshPassword(), sshPassword())
                || differs(update.sshPrivateKeyPath(), sshPrivateKeyPath())
                || differs(update.sshConnectTimeoutMs(), sshConnectTimeoutMs())
                || differs(update.sshStrictHostKeyChecking(), sshStrictHostKeyChecking());
        if (!sshChanged) {
            return;
        }
        String host = update.sshHost() != null ? update.sshHost() : sshHost();
        int port = update.sshPort() != null ? update.sshPort() : sshPort();
        String username = update.sshUsername() != null ? update.sshUsername() : sshUsername();
        String password = update.sshPassword() != null ? update.sshPassword() : sshPassword();
        String privateKeyPath = update.sshPrivateKeyPath() != null ? update.sshPrivateKeyPath() : sshPrivateKeyPath();
        long connectTimeoutMs = update.sshConnectTimeoutMs() != null ? update.sshConnectTimeoutMs() : sshConnectTimeoutMs();
        boolean strictHostKeyChecking = update.sshStrictHostKeyChecking() != null
                ? update.sshStrictHostKeyChecking() : sshStrictHostKeyChecking();

        try (SSHClient ssh = new SSHClient()) {
            if (strictHostKeyChecking) {
                ssh.loadKnownHosts();
            } else {
                ssh.addHostKeyVerifier(new PromiscuousVerifier());
            }
            ssh.setConnectTimeout((int) connectTimeoutMs);
            ssh.connect(host, port);
            if (privateKeyPath != null && !privateKeyPath.isBlank()) {
                ssh.authPublickey(username, privateKeyPath);
            } else {
                ssh.authPassword(username, password);
            }
        } catch (IOException e) {
            errors.add("SSH connection failed: " + e.getMessage());
        }
    }

    /**
     * Mirrors the auth-relevant subset of the snapshot out to the shared properties file
     * auth.war's AuthConfig reads on every request (see AuthConfig.setting()) — the mechanism that
     * lets a settings-page save take effect immediately, in the other webapp, with no restart.
     * Best-effort: the DB save above already succeeded and is the source of truth, so an
     * IOException here (e.g. the directory doesn't exist on a manual/non-.deb install that never
     * set it up — see docs/administrator-guide.md §9) is logged, not thrown.
     */
    private void writeAuthSettingsFile() {
        Path path = Path.of(authProperties.settingsFile());
        Properties props = new Properties();
        putIfPresent(props, "cookie.name", authCookieName());
        putIfPresent(props, "auth.backend", authBackend());
        putIfPresent(props, "smb.server", authSmbServer());
        putIfPresent(props, "smb.domain", authSmbDomain());
        putIfPresent(props, "auth.required-group", authRequiredGroup());
        putIfPresent(props, "smb.required-share", authSmbRequiredShare());
        try {
            if (path.getParent() != null) {
                Files.createDirectories(path.getParent());
            }
            try (FileOutputStream out = new FileOutputStream(path.toFile())) {
                props.store(out, "Written by nspawnmgr's /admin/settings page — do not edit by hand, changes are overwritten on the next save.");
            }
        } catch (IOException e) {
            log.warn("Could not write auth settings file {}: {}", path, e.getMessage());
        }
    }

    private static void putIfPresent(Properties props, String key, String value) {
        if (value != null && !value.isBlank()) {
            props.setProperty(key, value);
        }
    }

    private void validateUrlIfPresent(String label, String url, List<String> errors, boolean probe) {
        if (url == null || url.isBlank()) {
            return;
        }
        if (!probe) {
            return;
        }
        if (!probeReachable(url)) {
            errors.add(label + " (" + url + ") is not reachable");
        }
    }

    /** Any HTTP response (even 4xx/5xx) proves the URL resolves and something answers. */
    private boolean probeReachable(String url) {
        try {
            probeRestTemplate.getForEntity(url, String.class);
            return true;
        } catch (org.springframework.web.client.HttpStatusCodeException httpError) {
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    private void validateJsonPathIfPresent(String label, String path, List<String> errors) {
        if (path == null || path.isBlank()) {
            return;
        }
        try {
            JsonPath.compile(path);
        } catch (InvalidPathException e) {
            errors.add(label + " ('" + path + "') is not a valid JsonPath expression");
        }
    }

    /**
     * dnsmasq's {@code server=} directive needs each entry to be an IP literal, not a hostname —
     * it's what dnsmasq itself uses to resolve everything else, so a hostname there could never be
     * resolved in the first place. Rejects blank entries in the list (e.g. a trailing comma) too,
     * rather than silently dropping them.
     */
    private void validateDnsUpstreamServersIfPresent(String value, List<String> errors) {
        if (value == null || value.isBlank()) {
            return;
        }
        // limit -1: String.split() otherwise silently discards trailing empty strings, which would
        // let a trailing comma (or a bare comma) through unnoticed.
        for (String entry : value.split(",", -1)) {
            String server = entry.strip();
            if (server.isEmpty() || !isValidIpLiteral(server)) {
                errors.add("DNS upstream servers must be a comma-separated list of IP addresses ('" + entry + "' is not valid)");
            }
        }
    }

    private static final Pattern IPV4_PATTERN = Pattern.compile(
            "^(25[0-5]|2[0-4]\\d|1\\d\\d|[1-9]?\\d)(\\.(25[0-5]|2[0-4]\\d|1\\d\\d|[1-9]?\\d)){3}$");

    /** True for a well-formed IPv4/IPv6 literal only — never true for a hostname (no DNS lookup
     *  performed: {@link InetAddress#getByName} resolves literal notation locally). */
    private static boolean isValidIpLiteral(String candidate) {
        if (candidate.contains(":")) {
            try {
                return InetAddress.getByName(candidate) instanceof java.net.Inet6Address;
            } catch (UnknownHostException e) {
                return false;
            }
        }
        return IPV4_PATTERN.matcher(candidate).matches();
    }

    private static String orDefault(String override, String fallback) {
        return override != null && !override.isBlank() ? override : fallback;
    }

    /** True when {@code submitted} is present and doesn't match the currently-effective value. */
    private static boolean differs(Object submitted, Object current) {
        return submitted != null && !submitted.equals(current);
    }

    /** Plain carrier for an update request — every field nullable/optional (null = clear the override). */
    public record SettingsUpdate(
            String guacamoleBaseUrl, String guacamoleDataSource, String hostPublicAddress,
            String authCookieName, String authUserIdUrl, String authLoginUrl,
            String authUserIdJson, String authUserUsernameJson, String authUserEmailJson,
            String authUserFullnameJson, String authUserIsAdminJson, Long authCacheTtlSeconds,
            String provisioningAdminAccountName, Integer provisioningRdpPasswordLength,
            String authBackend, String authSmbServer, String authSmbDomain,
            String authRequiredGroup, String authSmbRequiredShare,
            String sshHost, Integer sshPort, String sshUsername, String sshPassword,
            String sshPrivateKeyPath, Long sshConnectTimeoutMs, Boolean sshStrictHostKeyChecking,
            String nspawnTemplatesDir, String nspawnMachinesDir, String nspawnSettingsDir,
            String nspawnPrivilegedScriptsDir, Long authHttpTimeoutMs, String hostExternalHostname,
            String dnsUpstreamServers, Integer qemuVncPortRangeStart, Integer qemuVncPortRangeEnd) {
    }
}
