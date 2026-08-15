package com.nspawnmgr.osauth;

import jcifs.CIFSContext;
import jcifs.config.PropertyConfiguration;
import jcifs.context.BaseContext;
import jcifs.smb.NtlmPasswordAuthenticator;
import jcifs.smb.SmbAuthException;
import jcifs.smb.SmbFile;

import java.io.IOException;
import java.util.Properties;

/**
 * Authenticates against a remote Windows machine (or domain controller) over SMB. This webapp is
 * deployed on Linux — alongside nspawnmgr and Guacamole in the same Tomcat — so it can no longer
 * call a local Windows API the way an older LogonUserW-based approach did; SMB session setup
 * against a configured target server is the equivalent check for a remote/network account.
 * Connects to the target's IPC$ share, which every Windows machine exposes, purely to force the
 * authentication handshake — no file access is actually needed.
 */
public class SmbOsAuthenticator implements OsAuthenticator {

    private final String server;
    private final String domain;
    private final String requiredShare;

    public SmbOsAuthenticator(String server, String domain, String requiredShare) {
        if (server == null || server.isBlank()) {
            throw new IllegalStateException("auth.backend=smb requires smb.server (SMB_SERVER) to be configured");
        }
        this.server = server;
        this.domain = domain == null ? "" : domain;
        this.requiredShare = requiredShare == null ? "" : requiredShare;
    }

    @Override
    public AuthResult authenticate(String username, char[] password) {
        try {
            CIFSContext baseContext = new BaseContext(new PropertyConfiguration(new Properties()));
            var credentials = new NtlmPasswordAuthenticator(domain, username, new String(password));
            CIFSContext context = baseContext.withCredentials(credentials);
            SmbFile probe = new SmbFile("smb://" + server + "/IPC$/", context);
            probe.connect();

            if (!requiredShare.isBlank() && !hasAccessToRequiredShare(context)) {
                return new AuthResult.NotAuthorized("access to the '" + requiredShare + "' share on " + server);
            }
            return new AuthResult.Success(username);
        } catch (SmbAuthException e) {
            return new AuthResult.InvalidCredentials();
        } catch (IOException e) {
            throw new IllegalStateException("Failed to reach SMB server '" + server + "'", e);
        }
    }

    /**
     * Deliberately a share-access check rather than a group-membership query: querying Windows
     * group membership remotely needs SAM access (SAMR/LSA over DCE-RPC) that Windows restricts to
     * Administrators by default (RestrictRemoteSAM) — confirmed against a real machine, where this
     * failed with DCERPC_FAULT_ACCESS_DENIED even for a local admin account, because local admin
     * accounts also get a filtered, non-elevated token over the network by default
     * (LocalAccountTokenFilterPolicy). A share-access check has no such restriction: it's a normal,
     * ACL-gated SMB operation any authenticated user can be granted access to.
     */
    private boolean hasAccessToRequiredShare(CIFSContext context) {
        try {
            SmbFile share = new SmbFile("smb://" + server + "/" + requiredShare + "/", context);
            // connect() alone only proves the SMB session/credentials are valid - it doesn't tree
            // connect to this specific share, so it never actually exercises the share's ACL
            // (confirmed: a user with no share permissions still passed a connect()-only check).
            // list() forces a real tree connect + directory enumeration, which Windows denies for
            // a user without share/NTFS read access.
            share.list();
            return true;
        } catch (IOException e) {
            // Access denied, share doesn't exist, etc. - fail closed rather than surface a
            // confusing hard error (the server itself is known reachable: the IPC$ probe above
            // already succeeded).
            return false;
        }
    }
}
