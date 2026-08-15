package com.nspawnmgr.rootwizard;

import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.FlywayException;

import javax.servlet.ServletException;
import javax.servlet.http.HttpServlet;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;

/**
 * Deployed at Tomcat's ROOT context ("/") inside the self-hosted {@code nspawnmgr} machine (see
 * {@code nspawnmgr-bootstrap-app-machine.sh}). Redirects every request to {@code /nspawnmgr/} once
 * {@link DbConnectionSettings#isReachable()} says nspawnmgr's own database is configured;
 * otherwise serves this first-boot database setup wizard itself.
 *
 * <p>Self-host-only: rather than asking for an existing/remote database server's root credentials
 * (the old {@code RemoteRootDbChannel} path, removed), this always provisions a brand-new Debian
 * database machine of its own via {@code nspawnmgr-bootstrap-db-machine.sh} — the admin only picks
 * an engine (MySQL/MariaDB both use Debian's own {@code mariadb-server}; PostgreSQL uses
 * {@code postgresql}) and, optionally, a non-default machine name. That script installs the engine
 * and bakes in a first-boot systemd oneshot unit that creates the opinionated
 * {@value #NSPAWNMGR_DB}/{@value #GUACAMOLE_DB} databases and users with freshly generated
 * passwords once the machine actually boots with a genuinely running engine (offline chroot
 * SQL bootstrapping was considered and rejected — both engines really need to run briefly to
 * execute CREATE DATABASE/CREATE USER, and their own first-run sequences differ too much to
 * reimplement safely offline) — then returns those passwords over the same SSH call. Since every
 * install always creates a brand-new database machine with brand-new databases, there's no
 * existing-schema detection/drop-confirmation flow to run here anymore either.
 *
 * <p>Also prompts for and creates the initial {@code nspawnmgr} Linux account, inside the
 * self-hosted {@code nspawnmgr} machine itself (not the database machine) — {@code auth.war}'s
 * existing PAM backend then authenticates against it with no backend code changes at all, since
 * {@code auth.war}'s own JVM now lives in that same machine (local PAM naturally means "this
 * machine's own accounts" once it's the one running the JVM).
 *
 * <p>The wizard form/submission itself is unavoidably unauthenticated (there's no database yet, so
 * no users table, so no login system can exist) and reachable from any host, not just localhost —
 * restrict network access to this port at the firewall/network layer if that matters for a given
 * deployment.
 */
public class DatabaseSetupWizardServlet extends HttpServlet {

    private static final String NSPAWNMGR_DB = "nspawnmgr";
    private static final String GUACAMOLE_DB = "guacamole";
    /** The self-hosted app machine's own opinionated, fixed name — see nspawnmgr-bootstrap-app-machine.sh. */
    private static final String APP_MACHINE_NAME = "nspawnmgr";
    /** Container list "Description" column contents for the two self-hosted machines — same fixed
     *  text {@code ContainerDiscoveryService} uses in the main module for the same two machines. */
    private static final String APP_MACHINE_DESCRIPTION = "Virtual machine management";
    private static final String DB_MACHINE_DESCRIPTION = "Database server";
    /** Provisioning a whole machine (package install + first-boot DB init) needs real time, not the ~30s default. */
    private static final long PROVISION_TIMEOUT_MS = 5 * 60 * 1000L;
    private static final long ACCOUNT_CREATE_TIMEOUT_MS = 30 * 1000L;

    /**
     * Provisioning takes minutes (a whole machine + database engine from scratch), so it runs on a
     * background thread once the submitted form validates, rather than blocking the HTTP request
     * for the whole duration - {@link #renderProgress} is returned immediately instead, and its own
     * inline script polls {@link #respondProvisionLog} for the log lines accumulated so far. Plain
     * static fields, not a per-run registry keyed by ID (compare {@code ContainerScriptService} in
     * the main module) - this wizard only ever has one submission in flight at a time in practice
     * (first boot, one admin), so a single shared "current run" is enough; not durable across a
     * restart, same posture {@code SessionStore}'s own in-memory design already takes elsewhere in
     * this project for a similarly one-shot, low-stakes case.
     */
    private static final Object PROVISION_LOCK = new Object();
    private static List<String> provisionLog = new ArrayList<>();
    private static String provisionStatus = "idle";
    private static String provisionErrorMessage;

    @Override
    protected void doGet(HttpServletRequest req, HttpServletResponse resp) throws IOException {
        if ("/provision-log".equals(req.getPathInfo())) {
            respondProvisionLog(resp);
            return;
        }
        if (DbConnectionSettings.resolve().isReachable()) {
            resp.sendRedirect(req.getContextPath() + "/nspawnmgr/");
            return;
        }
        renderSetupForm(resp, ConnectionInfo.defaults(), null);
    }

    @Override
    protected void doPost(HttpServletRequest req, HttpServletResponse resp) throws IOException, ServletException {
        ConnectionInfo connection = ConnectionInfo.fromRequest(req);
        if (!"mysql".equals(connection.engine()) && !"mariadb".equals(connection.engine())
                && !"postgresql".equals(connection.engine())) {
            renderSetupForm(resp, connection, "Unsupported database engine: " + connection.engine());
            return;
        }
        if (connection.machineName().isBlank()) {
            renderSetupForm(resp, connection, "A database machine name is required.");
            return;
        }
        if (connection.nspawnmgrUsername().isBlank() || connection.nspawnmgrPassword().isBlank()) {
            renderSetupForm(resp, connection, "An initial nspawnmgr username and password are required.");
            return;
        }
        if (!connection.nspawnmgrPassword().equals(connection.nspawnmgrPasswordConfirm())) {
            renderSetupForm(resp, connection, "Password and confirmation don't match.");
            return;
        }
        synchronized (PROVISION_LOCK) {
            provisionLog = new ArrayList<>();
            provisionStatus = "running";
            provisionErrorMessage = null;
        }
        Thread worker = new Thread(() -> runProvisioning(connection), "nspawnmgr-db-wizard-provision");
        worker.setDaemon(true);
        worker.start();
        renderProgress(resp);
    }

    /**
     * Runs on the background thread started by {@link #doPost}, appending a line to
     * {@link #provisionLog} for each step (and every line the database-machine bootstrap script
     * itself prints, streamed as it arrives - see
     * {@link PreSpringSshClient#runWithSudoPasswordStreaming}) so the progress page's own polling
     * loop shows it "as it occurs" rather than only once the whole thing finishes. Sets
     * {@link #provisionStatus} to {@code success} or {@code error} (with
     * {@link #provisionErrorMessage} set) as its very last act.
     */
    private void runProvisioning(ConnectionInfo connection) {
        // Guacamole's own JDBC extension/schema only ships mysql/postgresql directories - MySQL and
        // MariaDB are wire-compatible and share the same driver/schema/Flyway migration location,
        // so "mariadb" is a machine-naming/labelling choice only, not a separate code path.
        String vendor = "postgresql".equals(connection.engine()) ? "postgresql" : "mysql";
        int port = "postgresql".equals(vendor) ? 5432 : 3306;

        String scriptsDir;
        Map<String, String> credentials;
        try {
            scriptsDir = PreSpringSshClient.privilegedScriptsDir();
            appendLog("Provisioning database machine '" + connection.machineName() + "' (" + connection.engine() + ")...");
            // nspawnmgr-bootstrap-db-machine.sh's own contract (see parseCredentials below) is that
            // every KEY=VALUE line it ever prints on success is one of these generated secrets - skip
            // those from the browser-visible progress log (this admin's own screen may not be a
            // private one), while still handing the untouched stdout to parseCredentials just below,
            // which needs the real values.
            PreSpringSshClient.CommandResult provisionResult = PreSpringSshClient.runWithSudoPasswordStreaming(
                    List.of(scriptsDir + "/nspawnmgr-bootstrap-db-machine.sh", connection.engine(), connection.machineName()),
                    PROVISION_TIMEOUT_MS, line -> {
                        if (!line.matches("^\\w+_PASSWORD=.*")) {
                            appendLog(line);
                        }
                    });
            if (!provisionResult.succeeded()) {
                fail("Provisioning the database machine failed (exit " + provisionResult.exitStatus() + "): "
                        + provisionResult.stderr().strip());
                return;
            }
            credentials = parseCredentials(provisionResult.stdout());
        } catch (IOException e) {
            fail("Provisioning the database machine failed: " + e.getMessage());
            return;
        }
        String nspawnmgrDbPassword = credentials.get("NSPAWNMGR_DB_PASSWORD");
        String guacamoleDbPassword = credentials.get("GUACAMOLE_DB_PASSWORD");
        if (nspawnmgrDbPassword == null || guacamoleDbPassword == null) {
            fail("The database machine was provisioned, but its generated credentials couldn't be read back — "
                    + "check 'journalctl -u nspawnmgr-db-init.service' on " + connection.machineName() + ".");
            return;
        }

        String dbHost;
        try {
            appendLog("Looking up " + connection.machineName() + "'s address...");
            PreSpringSshClient.CommandResult addressResult = PreSpringSshClient.runNoPasswordSudo(
                    List.of(scriptsDir + "/nspawnmgr-get-internal-address.sh", connection.machineName()), null);
            if (!addressResult.succeeded() || addressResult.stdout().isBlank()) {
                fail("The database machine was provisioned, but its address couldn't be determined.");
                return;
            }
            dbHost = addressResult.stdout().strip();
            appendLog("Found " + connection.machineName() + " at " + dbHost + ".");
        } catch (IOException e) {
            fail("Could not determine the database machine's address: " + e.getMessage());
            return;
        }

        JdbcDrivers.load(vendor);
        try {
            appendLog("Running nspawnmgr's own database migrations...");
            Flyway.configure()
                    .dataSource(GuacamoleSchemaRunner.jdbcUrl(vendor, dbHost, port, NSPAWNMGR_DB), NSPAWNMGR_DB, nspawnmgrDbPassword)
                    .locations("classpath:db/migration/" + vendor)
                    .load()
                    .migrate();
            appendLog("Migrations complete.");
        } catch (FlywayException e) {
            fail("The database machine is up, but running nspawnmgr's own database migrations failed: " + e.getMessage());
            return;
        }

        try {
            appendLog("Registering '" + connection.nspawnmgrUsername() + "' as nspawnmgr's first admin...");
            try (Connection dbConnection = DriverManager.getConnection(
                    GuacamoleSchemaRunner.jdbcUrl(vendor, dbHost, port, NSPAWNMGR_DB), NSPAWNMGR_DB, nspawnmgrDbPassword)) {
                long adminUserId = registerAdminUser(dbConnection, connection.nspawnmgrUsername());
                appendLog("Admin account registered.");

                Long debianMinimalTemplateId = tryRegisterDebianMinimalTemplate(scriptsDir, dbConnection);
                appendLog(debianMinimalTemplateId != null
                        ? "Registered the debian-minimal template."
                        : "debian-minimal isn't ready to register yet — nspawnmgr will pick it up automatically once it is.");

                String appMachineAddress = tryResolveInternalAddress(scriptsDir, APP_MACHINE_NAME);

                appendLog("Registering nspawnmgr and '" + connection.machineName() + "' as managed containers...");
                registerContainer(dbConnection, APP_MACHINE_NAME, APP_MACHINE_DESCRIPTION, adminUserId, debianMinimalTemplateId, appMachineAddress);
                registerContainer(dbConnection, connection.machineName(), DB_MACHINE_DESCRIPTION, adminUserId, debianMinimalTemplateId, dbHost);
                appendLog("Containers registered (owned by '" + connection.nspawnmgrUsername() + "').");
            }
        } catch (SQLException e) {
            fail("nspawnmgr's own database migrated, but registering the first admin account and self-hosted "
                    + "machines failed: " + e.getMessage());
            return;
        }

        try {
            // nspawnmgr.guacamole.data-source's own static default (application.yml's
            // ${GUACAMOLE_DATA_SOURCE:mysql}) never gets rewritten for a self-hosted install the way
            // SSH_HOST/HOST_PUBLIC_ADDRESS do (see nspawnmgr-bootstrap-app-machine.sh) - confirmed
            // live: picking PostgreSQL here left it at "mysql", and GuacamoleAdminClient kept dialing
            // /api/session/data/mysql/... against a Guacamole instance whose only wired auth
            // extension is "postgresql" (see GuacamoleExtensionWiring below), 404ing every admin
            // action ("Session not associated with authentication provider \"mysql\""). app_settings'
            // own guacamole_data_source column already exists (V1's INSERT above just seeded row
            // id=1) and takes priority over that static default (SettingsService.guacamoleDataSource) -
            // set it directly here, over the same connection just migrated, rather than requiring an
            // admin to notice and fix it by hand on /admin/settings afterward.
            setGuacamoleDataSource(vendor, dbHost, port, nspawnmgrDbPassword);
        } catch (SQLException e) {
            fail("nspawnmgr's own database migrated, but recording its Guacamole data source failed: " + e.getMessage());
            return;
        }

        try {
            appendLog("Running Guacamole's own schema scripts...");
            GuacamoleSchemaRunner.runSchemaScripts(vendor, dbHost, port, GUACAMOLE_DB, GUACAMOLE_DB, guacamoleDbPassword,
                    Path.of(GuacamoleExtensionWiring.guacamoleHome(), "guacamole-auth-jdbc", vendor, "schema").toString());
            appendLog("Guacamole schema ready.");
        } catch (Exception e) {
            fail("nspawnmgr's own database is set up, but running Guacamole's schema scripts failed: " + e.getMessage());
            return;
        }

        try {
            appendLog("Wiring Guacamole's database-backed auth extension...");
            GuacamoleExtensionWiring.wireExtension(vendor, dbHost, port, GUACAMOLE_DB, GUACAMOLE_DB, guacamoleDbPassword);
            appendLog("Guacamole extension wired.");
        } catch (IOException e) {
            // Non-fatal: nspawnmgr's own database (the thing that actually decides whether this
            // wizard keeps showing up) is already working at this point - don't block on Guacamole
            // extension wiring, just log it and keep going.
            appendLog("WARNING: wiring Guacamole's auth extension automatically failed (" + e.getMessage()
                    + ") — see §7 \"GUACAMOLE_HOME and the auth backend\" in the administrator's guide to finish that step by hand.");
        }

        try {
            appendLog("Creating the initial nspawnmgr account inside " + APP_MACHINE_NAME + "...");
            createInitialAccount(scriptsDir, connection.nspawnmgrUsername(), connection.nspawnmgrPassword());
            appendLog("Account created.");
        } catch (IOException e) {
            fail("Databases are ready, but creating the initial nspawnmgr account failed: " + e.getMessage());
            return;
        }

        try {
            writeConfigFile(vendor, dbHost, port, nspawnmgrDbPassword);
        } catch (IOException e) {
            fail("Connected and migrated successfully, but could not save the configuration to "
                    + DbConnectionSettings.configFilePath() + ": " + e.getMessage());
            return;
        }

        appendLog("Reloading nspawnmgr...");
        for (String contextName : List.of("nspawnmgr", "guacamole")) {
            String failure = touchContextXml(contextName);
            if (failure != null) {
                fail("Databases and the initial account are ready, but reloading nspawnmgr failed: " + failure);
                return;
            }
        }
        appendLog("Success.");
        synchronized (PROVISION_LOCK) {
            provisionStatus = "success";
        }
    }

    /** See the call site's own comment for why this needs setting explicitly. */
    private static void setGuacamoleDataSource(String vendor, String dbHost, int port, String nspawnmgrDbPassword) throws SQLException {
        String url = GuacamoleSchemaRunner.jdbcUrl(vendor, dbHost, port, NSPAWNMGR_DB);
        try (Connection connection = DriverManager.getConnection(url, NSPAWNMGR_DB, nspawnmgrDbPassword);
             PreparedStatement statement = connection.prepareStatement("UPDATE app_settings SET guacamole_data_source = ? WHERE id = 1")) {
            statement.setString(1, vendor);
            statement.executeUpdate();
        }
    }

    /**
     * Registers {@code username} directly as nspawnmgr's own first admin - the wizard already
     * collected this exact username/password to create their OS-level login account with (see
     * {@link #createInitialAccount}), so there's no need to wait for them to actually log in before
     * they can own the two self-hosted machine rows below. {@code external_user_id} =
     * {@code username}: matches exactly what a real PAM/SMB login later resolves as this same
     * user's external identity (see the main module's {@code auth.war} {@code LoginServlet}, which
     * always sets {@code Identity.id() = username}, and {@code USER_ID_JSON}'s own {@code $.id}
     * default) - so their first real login finds this same row rather than creating a second one.
     */
    private static long registerAdminUser(Connection dbConnection, String username) throws SQLException {
        Timestamp now = Timestamp.from(Instant.now());
        String sql = "INSERT INTO users (external_user_id, username, role, created_at, updated_at) VALUES (?, ?, 'ADMIN', ?, ?)";
        try (PreparedStatement statement = dbConnection.prepareStatement(sql, Statement.RETURN_GENERATED_KEYS)) {
            statement.setString(1, username);
            statement.setString(2, username);
            statement.setTimestamp(3, now);
            statement.setTimestamp(4, now);
            statement.executeUpdate();
            try (ResultSet keys = statement.getGeneratedKeys()) {
                keys.next();
                return keys.getLong(1);
            }
        }
    }

    /**
     * Best-effort: registers the {@code debian-minimal} template row if the bake script (see
     * {@code nspawnmgr-bootstrap-app-machine.sh}) already left its tarball on disk by this point -
     * exact same row shape the main module's own {@code TemplateService.create()} produces for
     * {@code MinimalTemplateFlavor.DEBIAN} (this module has no dependency on the main one, so these
     * are literal values, not shared enum constants - same posture as this class's own duplicated
     * {@link GuacamoleSchemaRunner}/{@link JdbcDrivers}). Returns {@code null} - not a hard failure -
     * on anything going wrong (SSH hiccup, tarball genuinely not there yet, DB error): the main
     * module's own {@code ContainerDiscoveryService.reconcileSelfHostedInfrastructureNow()} keeps
     * retrying this exact same registration indefinitely once nspawnmgr's Spring app is up, so a
     * transient failure here just means it gets picked up a little later instead of immediately.
     */
    private static Long tryRegisterDebianMinimalTemplate(String scriptsDir, Connection dbConnection) {
        try {
            PreSpringSshClient.CommandResult result = PreSpringSshClient.runNoPasswordSudo(
                    List.of(scriptsDir + "/nspawnmgr-list-template-files.sh", PreSpringSshClient.templatesNspawnDir()), null);
            if (!result.succeeded() || result.stdout().lines().map(String::strip).noneMatch("debian-minimal"::equals)) {
                return null;
            }
            String sql = "INSERT INTO templates (name, description, source_path, backend, package_manager, "
                    + "ssh_preinstalled, rdp_capable, vnc_capable, active) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)";
            try (PreparedStatement statement = dbConnection.prepareStatement(sql, Statement.RETURN_GENERATED_KEYS)) {
                statement.setString(1, "debian-minimal");
                statement.setString(2, "Minimal Debian base image (apt)");
                statement.setString(3, "debian-minimal");
                statement.setString(4, "SYSTEMD_NSPAWN");
                statement.setString(5, "APT");
                statement.setBoolean(6, true);
                statement.setBoolean(7, true);
                statement.setBoolean(8, true);
                statement.setBoolean(9, true);
                statement.executeUpdate();
                try (ResultSet keys = statement.getGeneratedKeys()) {
                    keys.next();
                    return keys.getLong(1);
                }
            }
        } catch (IOException | SQLException e) {
            return null;
        }
    }

    /** Best-effort version of the same {@code nspawnmgr-get-internal-address.sh} lookup used for
     *  the database machine above - {@code null}, not a hard failure, if it doesn't resolve (the
     *  containers row still gets created either way; see {@link #registerContainer}). */
    private static String tryResolveInternalAddress(String scriptsDir, String machineName) {
        try {
            PreSpringSshClient.CommandResult result = PreSpringSshClient.runNoPasswordSudo(
                    List.of(scriptsDir + "/nspawnmgr-get-internal-address.sh", machineName), null);
            if (!result.succeeded() || result.stdout().isBlank()) {
                return null;
            }
            return result.stdout().strip();
        } catch (IOException e) {
            return null;
        }
    }

    /**
     * Registers one self-hosted machine as an ordinary MANAGED, RUNNING, owned container - same
     * defaults the main module's own {@code Container.discovered()} sets. {@code templateId}/
     * {@code internalAddress} may be {@code null} (best-effort lookups above didn't resolve) -
     * both columns are nullable, and {@code ContainerDiscoveryService}'s own recurring
     * reconciliation pass already knows how to backfill a missing template link once it runs.
     */
    private static void registerContainer(Connection dbConnection, String name, String description, long ownerId,
                                            Long templateId, String internalAddress) throws SQLException {
        Timestamp now = Timestamp.from(Instant.now());
        String sql = "INSERT INTO containers (name, description, owner_id, template_id, kind, internal_address, "
                + "state, rdp_enabled, vnc_enabled, desktop_manager, outbound_enabled, created_at, updated_at) "
                + "VALUES (?, ?, ?, ?, 'MANAGED', ?, 'RUNNING', FALSE, FALSE, 'NONE', TRUE, ?, ?)";
        try (PreparedStatement statement = dbConnection.prepareStatement(sql)) {
            statement.setString(1, name);
            statement.setString(2, description);
            statement.setLong(3, ownerId);
            if (templateId != null) {
                statement.setLong(4, templateId);
            } else {
                statement.setNull(4, java.sql.Types.BIGINT);
            }
            statement.setString(5, internalAddress);
            statement.setTimestamp(6, now);
            statement.setTimestamp(7, now);
            statement.executeUpdate();
        }
    }

    private static void appendLog(String line) {
        synchronized (PROVISION_LOCK) {
            provisionLog.add(line);
        }
    }

    private static void fail(String message) {
        appendLog("ERROR: " + message);
        synchronized (PROVISION_LOCK) {
            provisionStatus = "error";
            provisionErrorMessage = message;
        }
    }

    /** Polled by the progress page's own inline script - see {@link #renderProgress}. */
    private void respondProvisionLog(HttpServletResponse resp) throws IOException {
        String log;
        String status;
        String message;
        synchronized (PROVISION_LOCK) {
            log = String.join("\n", provisionLog);
            status = provisionStatus;
            message = provisionErrorMessage;
        }
        resp.setHeader("X-Provision-Status", status);
        if (message != null) {
            // Base64'd - HTTP header values can't safely carry arbitrary text (newlines especially).
            resp.setHeader("X-Provision-Message", Base64.getEncoder().encodeToString(message.getBytes(StandardCharsets.UTF_8)));
        }
        resp.setContentType("text/plain;charset=UTF-8");
        resp.getWriter().print(log);
    }

    private void renderProgress(HttpServletResponse resp) throws IOException {
        resp.setContentType("text/html;charset=UTF-8");
        resp.getWriter().print(SetupWizardHtml.render("nspawnmgr — Setting up", SetupWizardHtml.TONE_NEUTRAL, """
                <h1>Setting up database</h1>
                <p>This can take a few minutes — a whole database machine is being created from
                scratch.</p>
                <pre id="log" class="log"></pre>
                <p id="status-line">Starting…</p>
                <script>
                    var logEl = document.getElementById('log');
                    var statusEl = document.getElementById('status-line');
                    function poll() {
                        fetch('provision-log').then(function (r) {
                            var status = r.headers.get('X-Provision-Status');
                            var messageHeader = r.headers.get('X-Provision-Message');
                            return r.text().then(function (text) {
                                logEl.textContent = text;
                                logEl.scrollTop = logEl.scrollHeight;
                                if (status === 'error') {
                                    statusEl.textContent = messageHeader ? atob(messageHeader) : 'Setup failed.';
                                    var button = document.createElement('button');
                                    button.textContent = 'Back to form';
                                    button.addEventListener('click', function () { window.location.href = '/'; });
                                    statusEl.after(button);
                                    return;
                                }
                                if (status === 'success') {
                                    statusEl.textContent = 'Waiting for nspawnmgr to come up...';
                                    waitForApp();
                                    return;
                                }
                                setTimeout(poll, 1000);
                            });
                        }).catch(function () {
                            setTimeout(poll, 1000);
                        });
                    }
                    function waitForApp() {
                        (async () => {
                            // Same "follow redirects, expect the redirect chain to eventually end
                            // normally" reasoning the old success page's own polling used.
                            for (var attempt = 0; attempt < 60; attempt++) {
                                try {
                                    var probe = await fetch('/nspawnmgr/', { method: 'GET' });
                                    if (probe.ok) {
                                        statusEl.textContent = 'Finishing self-hosted machine setup...';
                                        await waitForBootstrap();
                                        window.location.href = '/nspawnmgr/';
                                        return;
                                    }
                                } catch (e) {
                                    // Expected while the context is mid-redeploy - keep polling.
                                }
                                await new Promise(function (resolve) { setTimeout(resolve, 1000); });
                            }
                            statusEl.textContent = 'Still waiting - reload this page or try /nspawnmgr/ directly.';
                        })();
                    }
                    // Polls nspawnmgr's own bootstrap-status endpoint (unauthenticated - no admin
                    // has logged in yet) so this same log keeps showing real progress through
                    // managed-SSH/Guacamole-connection provisioning for the two self-hosted
                    // machines, not just up to Tomcat merely responding above. Bounded (2 minutes)
                    // rather than indefinite: that work keeps retrying forever server-side
                    // regardless (see ContainerDiscoveryService's own recurring reconciliation), so
                    // there's no reason to block the admin from reaching the app itself over it.
                    async function waitForBootstrap() {
                        // Captured once, on entry - logEl already holds the wizard's own step log
                        // at this point (ending in "Success."). Each poll below re-renders that
                        // fixed prefix plus the latest bootstrap log, rather than replacing
                        // logEl's content outright, so this reads as one continuously-growing log
                        // rather than two separate windows shown one after the other.
                        var wizardLog = logEl.textContent;
                        for (var attempt = 0; attempt < 120; attempt++) {
                            try {
                                var r = await fetch('/nspawnmgr/api/bootstrap/self-hosted-infra-status');
                                var text = await r.text();
                                if (text) {
                                    logEl.textContent = wizardLog + '\\n' + text;
                                    logEl.scrollTop = logEl.scrollHeight;
                                }
                                if (r.headers.get('X-Provision-Status') === 'done') {
                                    return;
                                }
                            } catch (e) {
                                // Expected immediately after redeploy, while nspawnmgr's own
                                // context is still coming up - keep polling.
                            }
                            await new Promise(function (resolve) { setTimeout(resolve, 1000); });
                        }
                    }
                    poll();
                </script>
                """));
    }

    /** Parses the KEY=VALUE lines nspawnmgr-bootstrap-db-machine.sh prints on success. */
    private static Map<String, String> parseCredentials(String stdout) {
        Map<String, String> result = new HashMap<>();
        for (String line : stdout.lines().toList()) {
            int eq = line.indexOf('=');
            if (eq > 0) {
                result.put(line.substring(0, eq).strip(), line.substring(eq + 1).strip());
            }
        }
        return result;
    }

    /**
     * Creates the initial nspawnmgr Linux account inside the self-hosted app machine itself (not
     * the database machine) - reuses the same broad, already-granted
     * "systemd-run --machine=* --pipe --quiet --wait *" PASSWORD-tier sudoers entry every other
     * creation-time in-machine command in this project relies on, rather than needing a new
     * dedicated privileged script. The password is fed the same way ProvisioningService's own
     * provisionRdp() already does for ordinary managed containers (interpolated into a `chpasswd`
     * shell line reached over SSH+sudo) - not a new pattern.
     */
    private static void createInitialAccount(String scriptsDir, String username, String password) throws IOException {
        String remoteCommand = "useradd -m -s /bin/bash " + shellQuote(username)
                + " && echo " + shellQuote(username + ":" + password) + " | chpasswd";
        PreSpringSshClient.CommandResult result = PreSpringSshClient.runWithSudoPassword(
                List.of("systemd-run", "--machine=" + APP_MACHINE_NAME, "--pipe", "--quiet", "--wait",
                        "/bin/sh", "-c", remoteCommand),
                ACCOUNT_CREATE_TIMEOUT_MS);
        if (!result.succeeded()) {
            throw new IOException("exit " + result.exitStatus() + ": " + result.stderr().strip());
        }
    }

    private static String shellQuote(String value) {
        return "'" + value.replace("'", "'\\''") + "'";
    }

    /**
     * Called from {@link #runProvisioning} once everything else has succeeded. Doesn't restart
     * Tomcat — instead touches nspawnmgr's own context XML ({@code conf/Catalina/localhost/
     * nspawnmgr.xml}) and, since {@link GuacamoleExtensionWiring} just wrote {@code GUACAMOLE_HOME}'s
     * extension JAR/{@code guacamole.properties} too, {@code guacamole.xml} as well — by reading
     * each file's current bytes and writing them straight back through the existing
     * {@code nspawnmgr-write-file.sh} privileged script (via {@link PreSpringSshClient}, since
     * there's no Spring context here). Touching {@code nspawnmgr.xml} alone isn't enough on its
     * own: Guacamole's own webapp only reads {@code guacamole.properties} and loads extensions once,
     * at its own deploy time — which, on a fresh boot, happens *before* an admin has had a chance to
     * fill in this wizard at all, so Guacamole comes up with no database-backed auth extension
     * loaded and rejects every login (including the {@code guacadmin} account this wizard's own
     * schema step just created) until its own context gets redeployed too. Each write updates that
     * file's mtime, which is exactly what Tomcat's own {@code HostConfig} background auto-deploy
     * thread watches for — it undeploys and redeploys just that context in place; for
     * {@code /nspawnmgr} that re-runs its own {@code onStartup()} reachability check (which now
     * finds the database this wizard just configured, and boots the real app for real). Returns
     * null on success, or a message describing the failure.
     */
    private String touchContextXml(String contextName) {
        Path contextXmlPath = contextXmlPath(contextName);
        String currentContent;
        try {
            currentContent = Files.readString(contextXmlPath);
        } catch (IOException e) {
            return "Could not read " + contextXmlPath + ": " + e.getMessage();
        }
        try {
            PreSpringSshClient.CommandResult result = PreSpringSshClient.runNoPasswordSudo(
                    List.of(PreSpringSshClient.privilegedScriptsDir() + "/nspawnmgr-write-file.sh",
                            hostVisiblePath(contextXmlPath)),
                    currentContent);
            if (!result.succeeded()) {
                return "Reload request for " + contextName + " failed (exit " + result.exitStatus() + "): " + result.stderr().strip();
            }
        } catch (IOException e) {
            return "Reload request for " + contextName + " failed: " + e.getMessage();
        }
        return null;
    }

    /** Same {@code catalina.base}/{@code catalina.home} convention {@code TomcatConfigService} uses in the main module. */
    private static Path contextXmlPath(String contextName) {
        String catalinaBase = System.getProperty("catalina.base", System.getProperty("catalina.home"));
        return Path.of(catalinaBase, "conf", "Catalina", "localhost", contextName + ".xml");
    }

    /**
     * {@code contextXmlPath} is only meaningful from inside the {@code nspawnmgr} container's own
     * JVM (which is what {@link #contextXmlPath} and {@link Files#readString} above use it for) —
     * but {@code nspawnmgr-write-file.sh} runs on the bare HOST via {@code nspawnmgr_exec}, where
     * that same path doesn't exist. The write needs to land under the container's rootfs as the host
     * sees it instead ({@code /var/lib/machines/nspawnmgr/...} by default — see
     * {@code nspawnmgr-bootstrap-app-machine.sh}), so this prefixes the in-container path with the
     * host's machines directory + this machine's name, the same convention every privileged
     * container-filesystem script in {@code packaging/nspawnmgr-deb/privileged-scripts/} already
     * uses for a "host-visible path" argument.
     */
    private static String hostVisiblePath(Path inContainerPath) {
        return machinesDir() + "/" + APP_MACHINE_NAME + inContainerPath;
    }

    // Same NSPAWN_MACHINES_DIR key/precedence application.yml's own nspawnmgr.machines-dir default
    // resolves from, kept independent of PreSpringSshClient's own resolveKey (SSH_* keys only) since
    // this isn't an SSH setting.
    private static String machinesDir() {
        String fromSystemProperty = System.getProperty("NSPAWN_MACHINES_DIR");
        if (fromSystemProperty != null) {
            return fromSystemProperty;
        }
        String fromEnv = System.getenv("NSPAWN_MACHINES_DIR");
        if (fromEnv != null) {
            return fromEnv;
        }
        return "/var/lib/machines";
    }

    private static void writeConfigFile(String vendor, String hostname, int port, String password) throws IOException {
        Path path = Path.of(DbConnectionSettings.configFilePath());
        if (path.getParent() != null) {
            Files.createDirectories(path.getParent());
        }
        Properties props = new Properties();
        props.setProperty("DB_URL", GuacamoleSchemaRunner.jdbcUrl(vendor, hostname, port, NSPAWNMGR_DB));
        props.setProperty("DB_USERNAME", NSPAWNMGR_DB);
        props.setProperty("DB_PASSWORD", password);
        props.setProperty("DB_VENDOR", vendor);
        try (var out = Files.newOutputStream(path)) {
            props.store(out, "Written by nspawnmgr's first-boot database setup wizard.");
        }
        try {
            Files.setPosixFilePermissions(path, java.nio.file.attribute.PosixFilePermissions.fromString("rw-------"));
        } catch (UnsupportedOperationException e) {
            // Non-POSIX filesystem (e.g. local Windows dev) — best-effort only, not fatal.
        }
    }

    private static String param(HttpServletRequest req, String name, String defaultValue) {
        String value = req.getParameter(name);
        return value == null ? defaultValue : value;
    }

    private void renderSetupForm(HttpServletResponse resp, ConnectionInfo connection, String errorMessage) throws IOException {
        resp.setContentType("text/html;charset=UTF-8");
        if (errorMessage != null) {
            resp.setStatus(HttpServletResponse.SC_BAD_REQUEST);
        }
        String errorBox = errorMessage == null ? "" : "<div class=\"error-box\">" + SetupWizardHtml.escape(errorMessage) + "</div>";
        String tone = errorMessage == null ? SetupWizardHtml.TONE_NEUTRAL : SetupWizardHtml.TONE_ERROR;

        resp.getWriter().print(SetupWizardHtml.render("nspawnmgr — Database setup", tone, """
                <h1>Database setup</h1>
                <p>nspawnmgr couldn't connect to its database. This provisions a brand-new,
                self-hosted Debian database machine — nspawnmgr always manages its own database
                the same way it manages every other machine.</p>
                %s
                <form method="post">
                    <label>Database engine
                        <select name="engine" id="engine" onchange="updateMachineName()">
                            <option value="mysql" %s>MySQL</option>
                            <option value="mariadb" %s>MariaDB</option>
                            <option value="postgresql" %s>PostgreSQL</option>
                        </select>
                    </label>
                    <label>Database machine name <input type="text" name="machineName" id="machineName" value="%s"/></label>
                    <fieldset>
                        <legend>Initial nspawnmgr account (created inside nspawnmgr's own
                        self-hosted machine — this is what you'll log in with)</legend>
                        <label>Username <input type="text" name="nspawnmgrUsername" value="%s"/></label>
                        <label>Password <input type="password" name="nspawnmgrPassword"/></label>
                        <label>Confirm password <input type="password" name="nspawnmgrPasswordConfirm"/></label>
                    </fieldset>
                    <button type="submit">Set up database</button>
                </form>
                <script>
                    var defaultNames = { mysql: 'mysqldb', mariadb: 'mariadb', postgresql: 'postgresdb' };
                    var machineNameTouched = %s;
                    document.getElementById('machineName').addEventListener('input', function () {
                        machineNameTouched = true;
                    });
                    function updateMachineName() {
                        if (machineNameTouched) {
                            return;
                        }
                        document.getElementById('machineName').value = defaultNames[document.getElementById('engine').value];
                    }
                </script>
                """.formatted(errorBox,
                "mysql".equals(connection.engine()) ? "selected" : "",
                "mariadb".equals(connection.engine()) ? "selected" : "",
                "postgresql".equals(connection.engine()) ? "selected" : "",
                SetupWizardHtml.escape(connection.machineName()),
                SetupWizardHtml.escape(connection.nspawnmgrUsername()),
                errorMessage == null ? "false" : "true")));
    }

    private record ConnectionInfo(String engine, String machineName, String nspawnmgrUsername, String nspawnmgrPassword,
                                    String nspawnmgrPasswordConfirm) {

        static ConnectionInfo defaults() {
            return new ConnectionInfo("mysql", "mysqldb", "", "", "");
        }

        static ConnectionInfo fromRequest(HttpServletRequest req) {
            return new ConnectionInfo(param(req, "engine", "mysql"), param(req, "machineName", ""),
                    param(req, "nspawnmgrUsername", ""), param(req, "nspawnmgrPassword", ""),
                    param(req, "nspawnmgrPasswordConfirm", ""));
        }
    }
}
