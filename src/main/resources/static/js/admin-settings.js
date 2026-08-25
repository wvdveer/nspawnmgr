const basePath = document.body.getAttribute('data-base-path').replace(/\/$/, '');

attachPasswordToggle(document.getElementById('sshPassword'));

function intOrNull(id) {
    const value = document.getElementById(id).value;
    return value === '' ? null : parseInt(value, 10);
}

function textOrNull(id) {
    const value = document.getElementById(id).value;
    return value === '' ? null : value;
}

function checkboxValue(id) {
    return document.getElementById(id).checked;
}

document.getElementById('settings-form').addEventListener('submit', async (event) => {
    event.preventDefault();
    const status = document.getElementById('status');
    const body = {
        guacamoleBaseUrl: textOrNull('guacamoleBaseUrl'),
        guacamoleDataSource: textOrNull('guacamoleDataSource'),
        hostPublicAddress: textOrNull('hostPublicAddress'),
        hostExternalHostname: textOrNull('hostExternalHostname'),
        authCookieName: textOrNull('authCookieName'),
        authUserIdUrl: textOrNull('authUserIdUrl'),
        authLoginUrl: textOrNull('authLoginUrl'),
        authUserIdJson: textOrNull('authUserIdJson'),
        authUserUsernameJson: textOrNull('authUserUsernameJson'),
        authUserEmailJson: textOrNull('authUserEmailJson'),
        authUserFullnameJson: textOrNull('authUserFullnameJson'),
        authUserIsAdminJson: textOrNull('authUserIsAdminJson'),
        authCacheTtlSeconds: intOrNull('authCacheTtlSeconds'),
        authHttpTimeoutMs: intOrNull('authHttpTimeoutMs'),
        provisioningAdminAccountName: textOrNull('provisioningAdminAccountName'),
        provisioningRdpPasswordLength: intOrNull('provisioningRdpPasswordLength'),
        sshHost: textOrNull('sshHost'),
        sshPort: intOrNull('sshPort'),
        sshUsername: textOrNull('sshUsername'),
        sshPassword: textOrNull('sshPassword'),
        sshPrivateKeyPath: textOrNull('sshPrivateKeyPath'),
        sshConnectTimeoutMs: intOrNull('sshConnectTimeoutMs'),
        sshStrictHostKeyChecking: checkboxValue('sshStrictHostKeyChecking'),
        nspawnTemplatesDir: textOrNull('nspawnTemplatesDir'),
        nspawnMachinesDir: textOrNull('nspawnMachinesDir'),
        nspawnSettingsDir: textOrNull('nspawnSettingsDir'),
        nspawnPrivilegedScriptsDir: textOrNull('nspawnPrivilegedScriptsDir'),
        dnsUpstreamServers: textOrNull('dnsUpstreamServers'),
        qemuVncPortRangeStart: intOrNull('qemuVncPortRangeStart'),
        qemuVncPortRangeEnd: intOrNull('qemuVncPortRangeEnd'),
    };
    if (document.getElementById('authBackend')) {
        body.authBackend = textOrNull('authBackend');
        body.authSmbServer = textOrNull('authSmbServer');
        body.authSmbDomain = textOrNull('authSmbDomain');
        body.authRequiredGroup = textOrNull('authRequiredGroup');
        body.authSmbRequiredShare = textOrNull('authSmbRequiredShare');
    }
    const response = await fetch(`${basePath}/api/admin/settings`, {
        method: 'PUT',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify(body),
    });
    if (!response.ok) {
        status.textContent = 'Error: ' + await response.text();
        return;
    }
    status.textContent = 'Saved.';
    window.location.reload();
});

document.getElementById('download-report').addEventListener('click', () => {
    window.location.href = `${basePath}/api/admin/settings/report`;
});

document.getElementById('restart-tomcat').addEventListener('click', async () => {
    if (!await window.appDialog.confirm('This restarts Tomcat now, ending your current session. Continue?')) {
        return;
    }
    const restartStatus = document.getElementById('restart-status');
    restartStatus.textContent = 'Restart requested — Tomcat will be back shortly. Redirecting to login in 5 seconds...';
    try {
        await fetch(`${basePath}/api/admin/settings/restart-tomcat`, { method: 'POST' });
    } catch (e) {
        // Expected once Tomcat actually goes down mid-request — fall through to the redirect below regardless.
    }
    setTimeout(() => {
        const cookieName = document.body.getAttribute('data-auth-cookie-name');
        document.cookie = `${cookieName}=; Max-Age=0; path=/`;
        window.location.reload();
    }, 5000);
});

// --- Guacamole: structured guacamole.properties editor ---
// Field definitions (labels/help text/defaults) come from the server (GuacamolePropertiesSchema,
// sourced from the Guacamole manual) so this file doesn't duplicate ~65 field descriptions.

function guacFieldId(key) {
    return 'guac-' + key;
}

function renderGuacField(field, values) {
    const current = Object.prototype.hasOwnProperty.call(values, field.key) ? values[field.key] : null;
    const id = guacFieldId(field.key);
    const help = field.helpText ? `<p class="hint">${escapeHtml(field.helpText)}</p>` : '';

    if (field.type === 'CHECKBOX') {
        const checked = (current !== null ? current === 'true' : field.defaultValue === 'true') ? 'checked' : '';
        return `<div class="field field-inline">
            <input type="checkbox" id="${id}" ${checked}/>
            <label for="${id}">${escapeHtml(field.label)}</label>
        </div>${help}`;
    }

    if (field.type === 'SELECT') {
        const options = field.options.map((opt) => {
            const selected = (current !== null ? current === opt : field.defaultValue === opt) ? 'selected' : '';
            const optLabel = opt === '' ? '(default)' : opt;
            return `<option value="${escapeHtml(opt)}" ${selected}>${escapeHtml(optLabel)}</option>`;
        }).join('');
        return `<div class="field">
            <label for="${id}">${escapeHtml(field.label)}</label>
            <select id="${id}">${options}</select>
        </div>${help}`;
    }

    const inputType = field.type === 'PASSWORD' ? 'password' : (field.type === 'NUMBER' ? 'number' : 'text');
    const value = current !== null ? current : '';
    const placeholder = field.defaultValue ? `placeholder="${escapeHtml(field.defaultValue)}"` : '';
    return `<div class="field">
        <label for="${id}">${escapeHtml(field.label)}</label>
        <input type="${inputType}" id="${id}" value="${escapeHtml(value)}" ${placeholder}/>
    </div>${help}`;
}

function renderGuacGroups(groups, values) {
    return groups.map((group) => `
        <div class="panel-section">
            <h3>${escapeHtml(group.title)}</h3>
            ${group.fields.map((field) => renderGuacField(field, values)).join('')}
        </div>
    `).join('');
}

function collectGuacGroups(groups) {
    const collected = {};
    for (const group of groups) {
        for (const field of group.fields) {
            const el = document.getElementById(guacFieldId(field.key));
            if (!el) {
                continue;
            }
            if (field.type === 'CHECKBOX') {
                // Always write the literal state: several of these default to true, so "unchecked"
                // must persist as an explicit false rather than being omitted (which would just
                // mean "inherit the extension's own default").
                collected[field.key] = el.checked ? 'true' : 'false';
            } else if (el.value !== '') {
                collected[field.key] = el.value;
            }
        }
    }
    return collected;
}

function escapeHtml(value) {
    const div = document.createElement('div');
    div.textContent = value == null ? '' : String(value);
    return div.innerHTML;
}

let guacamoleConfig = null;

// Matches install-guacamole-auth-jdbc.sh's TARGET_DIR — see that script and
// docs/administrator-guide.md §7 for why this fixed, version-independent path is where
// guacamole-auth-jdbc ends up, whether installed by the .deb's postinst or run by hand.
const GUACAMOLE_AUTH_JDBC_DIR = '/etc/guacamole/guacamole-auth-jdbc';

function defaultSchemaScriptsDirectory(dbType) {
    return `${GUACAMOLE_AUTH_JDBC_DIR}/${dbType}/schema`;
}

// Only overwrites the field when it's still empty or still holds one of our own defaults (i.e.
// the admin hasn't typed a custom path) — so switching the database type keeps the suggestion in
// sync without clobbering a deliberate override.
function updateSchemaScriptsDirectoryDefault(dbType) {
    const field = document.getElementById('guacamoleSchemaScriptsDirectory');
    if (!field) {
        return;
    }
    const isOurDefault = field.value === '' || field.value === defaultSchemaScriptsDirectory('mysql')
        || field.value === defaultSchemaScriptsDirectory('postgresql');
    if (isOurDefault) {
        field.value = defaultSchemaScriptsDirectory(dbType);
    }
}

// Guacamole/database field groups are rendered as HTML strings (renderGuacField), so any
// PASSWORD-type field among them needs its toggle attached after insertion, not at page load
// like the static sshPassword field above.
function attachPasswordTogglesWithin(container) {
    container.querySelectorAll('input[type="password"]').forEach(attachPasswordToggle);
}

function renderGuacamoleDbFields() {
    const dbType = document.getElementById('guacamoleDatabaseType').value;
    const groups = dbType === 'postgresql' ? guacamoleConfig.postgresqlGroups : guacamoleConfig.mysqlGroups;
    const container = document.getElementById('guacamole-db-fields');
    container.innerHTML = renderGuacGroups(groups, guacamoleConfig.values);
    attachPasswordTogglesWithin(container);
    updateSchemaScriptsDirectoryDefault(dbType);
}

async function loadGuacamoleConfig() {
    const status = document.getElementById('guacamole-status');
    const response = await fetch(`${basePath}/api/admin/settings/guacamole-properties`);
    if (!response.ok) {
        status.textContent = 'Could not load current configuration: ' + await response.text();
        return;
    }
    guacamoleConfig = await response.json();
    status.textContent = guacamoleConfig.fileExists
        ? `Existing file found at ${guacamoleConfig.path}, last modified ${guacamoleConfig.lastModified}.`
        : `No file found yet at ${guacamoleConfig.path} — it will be created on first save.`;
    const guacdFields = document.getElementById('guacamole-guacd-fields');
    guacdFields.innerHTML = renderGuacGroups(guacamoleConfig.guacdGroups, guacamoleConfig.values);
    attachPasswordTogglesWithin(guacdFields);
    document.getElementById('guacamoleDatabaseType').value = guacamoleConfig.databaseType;
    renderGuacamoleDbFields();
    updateDataSourceMismatchState();
}

function updateDataSourceMismatchState() {
    const dataSourceField = document.getElementById('guacamoleDataSource');
    if (!dataSourceField || !guacamoleConfig) {
        return;
    }
    const mismatch = dataSourceField.value !== guacamoleConfig.databaseType;
    dataSourceField.classList.toggle('field-error', mismatch);
}

const guacamoleDataSourceField = document.getElementById('guacamoleDataSource');
if (guacamoleDataSourceField) {
    guacamoleDataSourceField.addEventListener('input', updateDataSourceMismatchState);
}
const guacamoleDataSourceRefreshButton = document.getElementById('guacamoleDataSource-refresh');
if (guacamoleDataSourceRefreshButton) {
    guacamoleDataSourceRefreshButton.addEventListener('click', () => {
        if (!guacamoleConfig) {
            return;
        }
        document.getElementById('guacamoleDataSource').value = guacamoleConfig.databaseType;
        updateDataSourceMismatchState();
    });
}

const guacamoleDbTypeSelect = document.getElementById('guacamoleDatabaseType');
if (guacamoleDbTypeSelect) {
    loadGuacamoleConfig();
    guacamoleDbTypeSelect.addEventListener('change', renderGuacamoleDbFields);

    document.getElementById('guacamole-save').addEventListener('click', async () => {
        const status = document.getElementById('guacamole-status');
        const dbType = guacamoleDbTypeSelect.value;
        const dbGroups = dbType === 'postgresql' ? guacamoleConfig.postgresqlGroups : guacamoleConfig.mysqlGroups;
        const values = {
            ...collectGuacGroups(guacamoleConfig.guacdGroups),
            ...collectGuacGroups(dbGroups),
        };
        const response = await fetch(`${basePath}/api/admin/settings/guacamole-properties`, {
            method: 'PUT',
            headers: { 'Content-Type': 'application/json' },
            body: JSON.stringify({ databaseType: dbType, values }),
        });
        if (!response.ok) {
            status.textContent = 'Error: ' + await response.text();
            return;
        }
        status.textContent = 'Saved. Remember: Guacamole will not see this until Tomcat is restarted.';
        loadGuacamoleConfig();
    });

    document.getElementById('guacamole-test').addEventListener('click', async () => {
        const status = document.getElementById('guacamole-test-status');
        const dbType = guacamoleDbTypeSelect.value;
        const fieldValue = (key, fallback) => {
            const el = document.getElementById(guacFieldId(`${dbType}-${key}`));
            return el && el.value !== '' ? el.value : fallback;
        };
        const hostname = fieldValue('hostname', 'localhost');
        const port = parseInt(fieldValue('port', dbType === 'postgresql' ? '5432' : '3306'), 10);
        const database = fieldValue('database', '');
        const username = fieldValue('username', '');
        const password = fieldValue('password', '');

        status.textContent = 'Testing...';
        const testResponse = await fetch(`${basePath}/api/admin/settings/guacamole-properties/test-database`, {
            method: 'POST',
            headers: { 'Content-Type': 'application/json' },
            body: JSON.stringify({ databaseType: dbType, hostname, port, database, username, password }),
        });
        if (!testResponse.ok) {
            status.textContent = 'Error: ' + await testResponse.text();
            return;
        }
        const result = await testResponse.json();
        if (!result.connected) {
            status.textContent = 'Connection failed: ' + result.error;
            return;
        }
        if (result.schemaExists) {
            status.textContent = 'Connected — Guacamole schema is already set up.';
            return;
        }
        const scriptsDirectory = document.getElementById('guacamoleSchemaScriptsDirectory').value;
        if (!scriptsDirectory) {
            status.textContent = 'Connected, but the Guacamole schema does not appear to be set up yet. '
                + 'Enter a schema scripts directory above and test again to be offered to run it.';
            return;
        }
        if (!await window.appDialog.confirm(`Guacamole's database schema doesn't appear to be set up yet. Run every .sql file in "${scriptsDirectory}" against this database now?`)) {
            status.textContent = 'Connected, but the Guacamole schema does not appear to be set up yet.';
            return;
        }
        status.textContent = 'Running schema scripts...';
        const runResponse = await fetch(`${basePath}/api/admin/settings/guacamole-properties/run-schema-scripts`, {
            method: 'POST',
            headers: { 'Content-Type': 'application/json' },
            body: JSON.stringify({ databaseType: dbType, hostname, port, database, username, password, scriptsDirectory }),
        });
        if (!runResponse.ok) {
            status.textContent = 'Error running schema scripts: ' + await runResponse.text();
            return;
        }
        status.textContent = 'Schema scripts ran successfully — Guacamole\'s database is now set up.';
    });
}

// --- Tomcat: HTTP port + HTTPS connector (conf/server.xml) ---

let tomcatConfig = null;

async function loadTomcatConfig() {
    const status = document.getElementById('tomcat-status');
    const response = await fetch(`${basePath}/api/admin/settings/tomcat-config`);
    if (!response.ok) {
        status.textContent = 'Could not load current configuration: ' + await response.text();
        return;
    }
    tomcatConfig = await response.json();
    if (tomcatConfig.error) {
        status.textContent = `Warning: ${tomcatConfig.error} (path: ${tomcatConfig.path})`;
    } else {
        status.textContent = `Read from ${tomcatConfig.path}.`;
    }
    document.getElementById('tomcatHttpPort').value = tomcatConfig.httpPort || '';
    document.getElementById('tomcatHttpsEnabled').value = String(tomcatConfig.httpsEnabled);
    document.getElementById('tomcatHttpsPort').value = tomcatConfig.httpsPort || '';
    document.getElementById('tomcatCertificateFile').value = tomcatConfig.certificateFile || '';
    document.getElementById('tomcatCertificateKeyFile').value = tomcatConfig.certificateKeyFile || '';
}

// Rewrites a URL field's protocol/port (from Tomcat's current config) and hostname (from the
// External hostname field in the Host section above), preserving only the path.
function refreshUrlFromTomcat(id) {
    if (!tomcatConfig) {
        return;
    }
    const field = document.getElementById(id);
    let url;
    try {
        url = new URL(field.value);
    } catch (e) {
        return;
    }
    url.protocol = tomcatConfig.httpsEnabled ? 'https:' : 'http:';
    url.port = String(tomcatConfig.httpsEnabled ? tomcatConfig.httpsPort : tomcatConfig.httpPort);
    const externalHostnameField = document.getElementById('hostExternalHostname');
    if (externalHostnameField && externalHostnameField.value) {
        url.hostname = externalHostnameField.value;
    }
    field.value = url.toString();
}

for (const id of ['guacamoleBaseUrl', 'authUserIdUrl', 'authLoginUrl']) {
    const button = document.getElementById(`${id}-refresh`);
    if (button) {
        button.addEventListener('click', () => refreshUrlFromTomcat(id));
    }
}

const tomcatSaveButton = document.getElementById('tomcat-save');
if (tomcatSaveButton) {
    loadTomcatConfig();
    tomcatSaveButton.addEventListener('click', async () => {
        const status = document.getElementById('tomcat-status');
        const body = {
            httpPort: intOrNull('tomcatHttpPort'),
            httpsEnabled: document.getElementById('tomcatHttpsEnabled').value === 'true',
            httpsPort: intOrNull('tomcatHttpsPort'),
            certificateFile: textOrNull('tomcatCertificateFile'),
            certificateKeyFile: textOrNull('tomcatCertificateKeyFile'),
        };
        const response = await fetch(`${basePath}/api/admin/settings/tomcat-config`, {
            method: 'PUT',
            headers: { 'Content-Type': 'application/json' },
            body: JSON.stringify(body),
        });
        if (!response.ok) {
            status.textContent = 'Error: ' + await response.text();
            return;
        }
        status.textContent = 'Saved. Remember: takes effect only after a Tomcat restart.';
        loadTomcatConfig();
    });
}
