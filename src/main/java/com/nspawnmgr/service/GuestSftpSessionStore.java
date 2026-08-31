package com.nspawnmgr.service;

import org.springframework.stereotype.Component;

import javax.servlet.http.HttpSession;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Holds SFTP credentials the user typed into the Files "Connect" prompt for a QEMU VM or an
 * EXTERNAL host - see {@code ContainerFileBrowserService}'s own javadoc for why: nspawnmgr has no
 * non-interactive credential for either, unlike a container it provisioned itself. Deliberately
 * an {@link HttpSession} attribute, never the database - the credential should not outlive the
 * browser session it was typed into. Keyed by container ID (a plain {@code Map} stored as one
 * session attribute, not one attribute per container) so opening Files for two different
 * containers in separate tabs doesn't clobber each other.
 */
@Component
public class GuestSftpSessionStore {

    private static final String SESSION_ATTRIBUTE = "nspawnmgr.guestSftpCredentials";

    /** {@code password} is never copied further than necessary - callers should zero it once done
     *  using it for a connection, same "best-effort hygiene, not a guarantee" posture
     *  {@code SshRemoteExecutor#writePassword} already takes elsewhere in this codebase. */
    public record Credential(String username, char[] password) {
    }

    /** Stores a clone of {@code password}, never the caller's own array - the connect endpoint
     *  zeroes its own copy in a {@code finally} block right after this call for best-effort
     *  hygiene (see the class javadoc), which would otherwise silently corrupt this same array to
     *  all-null-bytes the moment it's stored here, since a char[] is passed by reference. Confirmed
     *  live: without the clone, every list/download/upload after a successful connect
     *  authenticated with a blanked-out password and failed with "Exhausted available
     *  authentication methods", indistinguishable from a genuinely wrong password. */
    public void put(HttpSession session, Long containerId, String username, char[] password) {
        credentials(session).put(containerId, new Credential(username, password.clone()));
    }

    /** {@code null} if nothing was ever connected for this container in this session, or it was
     *  since {@link #clear}ed. */
    public Credential get(HttpSession session, Long containerId) {
        return credentials(session).get(containerId);
    }

    public void clear(HttpSession session, Long containerId) {
        Credential removed = credentials(session).remove(containerId);
        if (removed != null) {
            java.util.Arrays.fill(removed.password(), '\0');
        }
    }

    @SuppressWarnings("unchecked")
    private Map<Long, Credential> credentials(HttpSession session) {
        Map<Long, Credential> map = (Map<Long, Credential>) session.getAttribute(SESSION_ATTRIBUTE);
        if (map == null) {
            map = new ConcurrentHashMap<>();
            session.setAttribute(SESSION_ATTRIBUTE, map);
        }
        return map;
    }
}
