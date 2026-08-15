package com.nspawnmgr.cli;

import com.nspawnmgr.domain.ContainerOutboundAllowlistEntry;

import java.util.List;

/**
 * Applies/removes host firewall rules so a running container's outbound internet access matches
 * its persisted Container.outboundEnabled flag - plus, while that flag is false, punches through
 * any specific destinations in its outbound allowlist (e.g. 127.0.0.1 to reach another co-located
 * service without granting general internet access). The allowlist has no effect when
 * outboundEnabled is true; everything is already reachable in that case.
 */
public interface ContainerOutboundAccessManager {

    void sync(String machineName, boolean outboundEnabled, List<ContainerOutboundAllowlistEntry> allowlist);
}
