package com.nspawnmgr.osauth;

import javax.servlet.ServletContext;

public interface OsAuthenticator {

    /** Checks credentials and (if configured) required-group membership — see AuthResult. */
    AuthResult authenticate(String username, char[] password);

    /**
     * Which backend to use is a deployment choice, not something to detect from the JVM's own
     * OS — this webapp always runs on Linux (deployed alongside nspawnmgr/Guacamole in the same
     * Tomcat), but the accounts being authenticated might be local Linux accounts on that host
     * (PAM) or a remote Windows machine/domain's accounts reached over SMB. See AuthConfig.
     */
    static OsAuthenticator configured(ServletContext context) {
        String backend = AuthConfig.authBackend(context);
        if ("smb".equalsIgnoreCase(backend)) {
            return new SmbOsAuthenticator(AuthConfig.smbServer(context), AuthConfig.smbDomain(context),
                    AuthConfig.smbRequiredShare(context));
        }
        return new LinuxPamOsAuthenticator(AuthConfig.requiredGroup(context));
    }
}
