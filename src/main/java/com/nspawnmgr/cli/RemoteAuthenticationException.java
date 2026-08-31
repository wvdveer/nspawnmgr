package com.nspawnmgr.cli;

/** The remote SSH server rejected the given username/password - distinct from
 *  {@link ContainerCliException} (a network/protocol-level failure) so {@code ApiExceptionHandler}
 *  can map it to a clear 401 with an actionable message instead of a bare "failed to establish SSH
 *  connection" that reads like a network/firewall problem. Expected to happen routinely (a typo, or
 *  a guest OS that disables root password login by default), not a bug. */
public class RemoteAuthenticationException extends RuntimeException {
    public RemoteAuthenticationException(String message, Throwable cause) {
        super(message, cause);
    }
}
