package com.nspawnmgr.service;

import com.nspawnmgr.cli.ContainerCliException;
import com.nspawnmgr.cli.ContainerCliExecutor;
import com.nspawnmgr.cli.ContainerPortProbe;
import com.nspawnmgr.domain.Container;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Resolves an EXTERNAL host's RUNNING/STOPPED display state with a single TCP reachability check
 * (reusing {@link ContainerPortProbe}, the same plain-connect check ContainerAccessService already
 * uses for managed containers) against whichever of its configured SSH/RDP/VNC ports is enabled,
 * plus (same cache cycle) a resolved display address via {@link ContainerCliExecutor#resolveHostname}
 * - both cached for one minute per host so the Machines grid and a host's own detail page don't each
 * trigger a fresh probe/resolve on every request. A host with no port enabled at all has nothing to
 * probe - reported as reachable rather than guessed at, matching the pre-existing default before this
 * check existed.
 *
 * <p>Resolution deliberately goes through the same SSH-based {@code resolveHostname} every real
 * connection already uses (see {@code ContainerSessionService}'s own javadoc) rather than a plain
 * client-side DNS lookup - nspawnmgr itself typically runs inside its own network-isolated
 * self-hosted container, with no visibility into a private LAN's own name resolution the way the
 * bare host's own network stack has, so a client-side lookup would silently fail or resolve
 * differently for exactly the hostnames this matters most for. Falls back to the raw configured
 * hostname (unresolved) on any failure - a card showing the admin's own typed value is still more
 * useful than a blank one, and matches this service's existing "never blank/guessed" posture for
 * reachability above.
 *
 * <p>Never mutates/persists anything - callers apply the result to their own already-detached
 * {@link Container} instance's in-memory state for display only (see ContainerPageController),
 * never through a repository save.
 */
@Service
public class HostLivenessService {

    private static final Logger log = LoggerFactory.getLogger(HostLivenessService.class);
    private static final Duration CACHE_TTL = Duration.ofMinutes(1);

    private final ContainerPortProbe portProbe;
    private final ContainerCliExecutor cliExecutor;
    private final Map<Long, CachedResult> cache = new ConcurrentHashMap<>();

    public HostLivenessService(ContainerPortProbe portProbe, ContainerCliExecutor cliExecutor) {
        this.portProbe = portProbe;
        this.cliExecutor = cliExecutor;
    }

    public boolean isReachable(Container host) {
        return checked(host).reachable;
    }

    /** The host's configured hostname re-resolved to a real address for display, or the raw
     *  configured value unresolved if resolution fails - see this class's own javadoc for why a
     *  plain client-side lookup isn't used instead. */
    public String resolvedAddress(Container host) {
        return checked(host).resolvedAddress;
    }

    private CachedResult checked(Container host) {
        Instant now = Instant.now();
        CachedResult cached = cache.get(host.getId());
        if (cached != null && cached.expiresAt.isAfter(now)) {
            return cached;
        }
        CachedResult result = new CachedResult(probe(host), resolve(host), now.plus(CACHE_TTL));
        cache.put(host.getId(), result);
        return result;
    }

    private boolean probe(Container host) {
        Integer port = firstConfiguredPort(host);
        if (port == null) {
            return true;
        }
        return portProbe.isOpen(host.getHostname(), port);
    }

    private String resolve(Container host) {
        try {
            return cliExecutor.resolveHostname(host.getHostname());
        } catch (ContainerCliException e) {
            log.debug("Could not resolve host '{}' ({}) for display - showing the configured value unresolved: {}",
                    host.getName(), host.getHostname(), e.getMessage());
            return host.getHostname();
        }
    }

    private Integer firstConfiguredPort(Container host) {
        if (host.getExternalSshPort() != null) {
            return host.getExternalSshPort();
        }
        if (host.getExternalRdpPort() != null) {
            return host.getExternalRdpPort();
        }
        return host.getExternalVncPort();
    }

    private record CachedResult(boolean reachable, String resolvedAddress, Instant expiresAt) {
    }
}
