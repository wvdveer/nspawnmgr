package com.nspawnmgr.cli;

/**
 * Plain TCP reachability check against a container's internal address - no credentials involved,
 * unlike {@link ContainerReadinessChecker} (which authenticates over SSH with a container's own
 * nspawnmgr-issued key). Used by ContainerAccessService to confirm a port is actually listening
 * before wiring up a prompt-credentials Guacamole connection for a container nspawnmgr has no
 * generated account for.
 */
public interface ContainerPortProbe {

    boolean isOpen(String host, int port);
}
