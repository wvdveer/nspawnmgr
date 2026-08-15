package com.nspawnmgr.osauth;

import org.jvnet.libpam.PAM;
import org.jvnet.libpam.PAMException;
import org.jvnet.libpam.UnixUser;

/** Authenticates against the local Linux OS via PAM, using the libpam4j JNA-based wrapper. */
public class LinuxPamOsAuthenticator implements OsAuthenticator {

    private static final String PAM_SERVICE_NAME = "login";

    private final String requiredGroup;

    public LinuxPamOsAuthenticator(String requiredGroup) {
        this.requiredGroup = requiredGroup == null ? "" : requiredGroup;
    }

    @Override
    public AuthResult authenticate(String username, char[] password) {
        PAM pam;
        try {
            pam = new PAM(PAM_SERVICE_NAME);
        } catch (PAMException e) {
            return new AuthResult.InvalidCredentials();
        }
        try {
            UnixUser user = pam.authenticate(username, new String(password));
            // Unix group names are case-sensitive, unlike Windows account/group names.
            if (!requiredGroup.isBlank() && !user.getGroups().contains(requiredGroup)) {
                return new AuthResult.NotAuthorized("membership in the '" + requiredGroup + "' group");
            }
            String gecos = user.getGecos();
            return new AuthResult.Success(gecos != null && !gecos.isBlank() ? gecos : username);
        } catch (PAMException e) {
            return new AuthResult.InvalidCredentials();
        } finally {
            pam.dispose();
        }
    }
}
