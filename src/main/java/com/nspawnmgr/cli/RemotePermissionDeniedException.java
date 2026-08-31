package com.nspawnmgr.cli;

/** The remote SSH/SFTP account itself does not have OS-level permission to access the requested
 *  path - distinct from {@link ContainerCliException} (a connection/protocol-level failure) so
 *  {@code ApiExceptionHandler} can map it to a clear 403 with its own message instead of a bare
 *  500. Expected to happen routinely now that Files/SFTP browsing isn't capped at the connecting
 *  account's own home directory - browsing into another user's home or a root-owned path is a
 *  normal thing to attempt, not a bug. */
public class RemotePermissionDeniedException extends RuntimeException {
    public RemotePermissionDeniedException(String message, Throwable cause) {
        super(message, cause);
    }
}
