package com.nspawnmgr.service;

import com.nspawnmgr.cli.ContainerPortProbe;
import com.nspawnmgr.domain.Container;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Resolves an EXTERNAL host's RUNNING/STOPPED display state with a single TCP reachability check
 * (reusing {@link ContainerPortProbe}, the same plain-connect check ContainerAccessService already
 * uses for managed containers) against whichever of its configured SSH/RDP/VNC ports is enabled,
 * cached for one minute per host so the Machines grid and a host's own detail page don't each
 * trigger a fresh probe on every request. A host with no port enabled at all has nothing to probe -
 * reported as reachable rather than guessed at, matching the pre-existing default before this
 * check existed.
 *
 * <p>Never mutates/persists anything - callers apply the result to their own already-detached
 * {@link Container} instance's in-memory state for display only (see ContainerPageController),
 * never through a repository save.
 */
@Service
public class HostLivenessService {

    private static final Duration CACHE_TTL = Duration.ofMinutes(1);

    private final ContainerPortProbe portProbe;
    private final Map<Long, CachedResult> cache = new ConcurrentHashMap<>();

    public HostLivenessService(ContainerPortProbe portProbe) {
        this.portProbe = portProbe;
    }

    public boolean isReachable(Container host) {
        Instant now = Instant.now();
        CachedResult cached = cache.get(host.getId());
        if (cached != null && cached.expiresAt.isAfter(now)) {
            return cached.reachable;
        }
        boolean reachable = probe(host);
        cache.put(host.getId(), new CachedResult(reachable, now.plus(CACHE_TTL)));
        return reachable;
    }

    private boolean probe(Container host) {
        Integer port = firstConfiguredPort(host);
        if (port == null) {
            return true;
        }
        return portProbe.isOpen(host.getHostname(), port);
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

    private record CachedResult(boolean reachable, Instant expiresAt) {
    }
}
