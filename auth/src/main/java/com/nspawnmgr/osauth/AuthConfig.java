package com.nspawnmgr.osauth;

import javax.servlet.ServletContext;
import java.io.FileInputStream;
import java.io.IOException;
import java.util.Properties;

public final class AuthConfig {

    private static final String DEFAULT_COOKIE_NAME = "nspawnmgr_session";
    private static final String DEFAULT_AUTH_BACKEND = "pam";
    private static final String DEFAULT_SETTINGS_FILE = "/etc/nspawnmgr/auth-live/auth-settings.properties";

    private AuthConfig() {
    }

    public static String cookieName(ServletContext context) {
        return setting(context, "cookie.name", "COOKIE_NAME", DEFAULT_COOKIE_NAME);
    }

    /** "pam" (default — local Linux accounts on this host) or "smb" (a remote Windows machine/domain). */
    public static String authBackend(ServletContext context) {
        return setting(context, "auth.backend", "AUTH_BACKEND", DEFAULT_AUTH_BACKEND);
    }

    /** Required when auth.backend=smb: hostname/IP of the target Windows machine or domain controller. */
    public static String smbServer(ServletContext context) {
        return setting(context, "smb.server", "SMB_SERVER", "");
    }

    /** Optional when auth.backend=smb: NetBIOS/AD domain; blank authenticates against the target's local accounts. */
    public static String smbDomain(ServletContext context) {
        return setting(context, "smb.domain", "SMB_DOMAIN", "");
    }

    /**
     * Optional when auth.backend=pam: a Unix group users must belong to in order to log in.
     * Blank (the default) means no gate — anyone with valid credentials may log in, preserving
     * existing behavior.
     */
    public static String requiredGroup(ServletContext context) {
        return setting(context, "auth.required-group", "AUTH_REQUIRED_GROUP", "");
    }

    /**
     * Optional when auth.backend=smb: the name of a share on smb.server that users must have
     * access to (via ordinary share/NTFS permissions) in order to log in. Blank (the default)
     * means no gate. Deliberately a share-access check rather than a group-membership query:
     * querying Windows group membership remotely requires SAM access most non-admin accounts
     * don't have by default (RestrictRemoteSAM), whereas share access is just a normal, ACL-gated
     * SMB operation any authenticated user can be granted without any special privilege.
     */
    public static String smbRequiredShare(ServletContext context) {
        return setting(context, "smb.required-share", "SMB_REQUIRED_SHARE", "");
    }

    /**
     * Live overrides written by nspawnmgr's /admin/settings page win over everything else — this
     * is the whole point of the shared file, and it's checked fresh on every call (no caching) so
     * a settings-page save takes effect on the very next login/userinfo/logout request, with no
     * restart of either webapp.
     */
    private static String setting(ServletContext context, String initParam, String systemProperty, String defaultValue) {
        String fromFile = fromSharedFile(context, initParam);
        if (fromFile != null && !fromFile.isBlank()) {
            return fromFile;
        }
        String fromContext = context.getInitParameter(initParam);
        if (fromContext != null && !fromContext.isBlank()) {
            return fromContext;
        }
        return System.getProperty(systemProperty, defaultValue);
    }

    /**
     * Reads {@code key} from the shared settings file nspawnmgr's SettingsService writes on every
     * settings-page save. Missing/unreadable file (the normal case for manual/dev/test deployments
     * that never wire up the settings page) is not an error — just means "no override", falling
     * through to today's context-param/system-property behavior unchanged.
     */
    private static String fromSharedFile(ServletContext context, String key) {
        String path = settingsFilePath(context);
        Properties props = new Properties();
        try (FileInputStream in = new FileInputStream(path)) {
            props.load(in);
        } catch (IOException e) {
            return null;
        }
        return props.getProperty(key);
    }

    private static String settingsFilePath(ServletContext context) {
        String fromContext = context.getInitParameter("auth.settings-file");
        if (fromContext != null && !fromContext.isBlank()) {
            return fromContext;
        }
        return System.getProperty("AUTH_SETTINGS_FILE", DEFAULT_SETTINGS_FILE);
    }
}
