package com.nspawnmgr.service;

import com.nspawnmgr.cli.DnsReloader;
import com.nspawnmgr.cli.TomcatConfigWriter;
import com.nspawnmgr.config.DnsProperties;
import com.nspawnmgr.domain.Container;
import com.nspawnmgr.domain.ContainerKind;
import com.nspawnmgr.domain.ContainerState;
import com.nspawnmgr.repository.ContainerRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Collectors;

/**
 * Keeps two dnsmasq-owned files (see packaging/nspawnmgr-deb/dnsmasq-nspawnmgr.conf) in sync with
 * live, admin-editable state: the {@code addn-hosts} file (every currently-RUNNING MANAGED
 * container's internal address, so containers can resolve each other by their nspawnmgr name —
 * plus one fixed entry for the host's own external hostname, see {@link #externalHostnameLine()})
 * and the {@code /etc/dnsmasq.d/nspawnmgr-upstream.conf} upstream-servers file (auto-included by
 * dnsmasq's own {@code conf-dir=}, see {@link SettingsService#dnsUpstreamServers()}). Reuses {@link
 * TomcatConfigWriter} as-is for both (despite its name, it's already a fully generic "write
 * arbitrary content to a root-owned path" primitive) — no new privileged script or sudoers entry
 * needed for the writes themselves.
 *
 * <p>Filtering by {@code state = RUNNING} alone is enough to keep the hosts file correct: a
 * stopped or mid-restart container is naturally excluded even though its last-known {@code
 * internalAddress} is still sitting in the DB (see {@link Container#getInternalAddress()}), and a
 * deleted container's row is just gone — no need to hook {@code ContainerLifecycleService}'s
 * stop/delete paths at all. The upstream-servers file, and the external-hostname line, only ever
 * change when an admin saves /admin/settings — polling them here (rather than writing them straight
 * from {@code SettingsService.update()}) keeps every dnsmasq-file write, and the SSH-based
 * privileged write it requires, in this one place.
 *
 * <p>Writing a file alone isn't enough, and the two files need genuinely different follow-up —
 * confirmed live (yoga, 2026-08-06), dnsmasq only re-reads {@code addn-hosts} on SIGHUP or
 * restart, never automatically, so every hosts-file write is followed by a {@link
 * DnsReloader#reload()} (SIGHUP). {@code server=} lines are different again — also confirmed live,
 * dnsmasq only parses those at process startup, so SIGHUP alone does NOT pick them up; every
 * upstream-servers write is instead followed by a full {@link DnsReloader#restart()}. Getting this
 * wrong is silent, not a hard failure: the file on disk would look correct while dnsmasq kept
 * answering from whatever it read at its own last real start.
 */
@Component
public class ContainerDnsSyncService {

    private static final Logger log = LoggerFactory.getLogger(ContainerDnsSyncService.class);

    /** nspawnbr0's own fixed, non-admin-configurable address (see 70-nspawnmgr-bridge.network) -
     *  same convention already relied on host-side by nspawnmgr-bootstrap-app-machine.sh/
     *  nspawnmgr-resolve-hostname.sh. Every container's only route back to the host itself (e.g. a
     *  custom port mapping's host-forwarded port) goes through this address, which is why the
     *  host's own external hostname is published pointing here rather than at its real LAN address -
     *  a container has no other route to that address at all. */
    private static final String BRIDGE_ADDRESS = "10.100.0.1";

    /** Static default from application.yml's own ${HOST_EXTERNAL_HOSTNAME:localhost} - means "never
     *  actually detected/configured" (setup-sudo-account.sh always overwrites this with a real
     *  detected hostname during install), so it's excluded below rather than mapping "localhost"
     *  itself to the bridge address inside every container's DNS. */
    private static final String UNSET_EXTERNAL_HOSTNAME = "localhost";

    private final ContainerRepository containerRepository;
    private final TomcatConfigWriter fileWriter;
    private final DnsReloader dnsReloader;
    private final DnsProperties dnsProperties;
    private final SettingsService settingsService;
    private final AtomicReference<String> lastWrittenHosts = new AtomicReference<>();
    private final AtomicReference<String> lastWrittenUpstream = new AtomicReference<>();

    public ContainerDnsSyncService(ContainerRepository containerRepository, TomcatConfigWriter fileWriter,
                                    DnsReloader dnsReloader, DnsProperties dnsProperties, SettingsService settingsService) {
        this.containerRepository = containerRepository;
        this.fileWriter = fileWriter;
        this.dnsReloader = dnsReloader;
        this.dnsProperties = dnsProperties;
        this.settingsService = settingsService;
    }

    @Scheduled(fixedDelay = 15000)
    public void sync() {
        syncHostsFile();
        syncUpstreamServersFile();
    }

    private void syncHostsFile() {
        List<Container> running = containerRepository.findByKindAndStateAndInternalAddressIsNotNull(
                ContainerKind.MANAGED, ContainerState.RUNNING);
        String containerLines = running.stream()
                .sorted(Comparator.comparing(Container::getName))
                .map(c -> c.getInternalAddress() + " " + c.getName())
                .collect(Collectors.joining("\n", "", "\n"));
        String content = containerLines + externalHostnameLine();
        writeIfChanged(dnsProperties.hostsFile(), content, lastWrittenHosts, dnsReloader::reload, "reload");
    }

    /**
     * Publishes the host's own external hostname (see {@link SettingsService#hostExternalHostname()}
     * — detected at install time by {@code setup-sudo-account.sh}, live-editable afterward on
     * /admin/settings) pointing at {@link #BRIDGE_ADDRESS}, so a managed container can resolve it —
     * matters for reaching anything the host forwards back in (e.g. a custom port mapping), which a
     * container otherwise has no route to at all. Empty string (nothing appended) when it's still
     * the unconfigured default.
     */
    private String externalHostnameLine() {
        String hostname = settingsService.hostExternalHostname();
        if (hostname == null || hostname.isBlank() || UNSET_EXTERNAL_HOSTNAME.equals(hostname)) {
            return "";
        }
        return BRIDGE_ADDRESS + " " + hostname + "\n";
    }

    private void syncUpstreamServersFile() {
        String content = Arrays.stream(settingsService.dnsUpstreamServers().split(","))
                .map(String::strip)
                .filter(server -> !server.isEmpty())
                .map(server -> "server=" + server)
                .collect(Collectors.joining("\n", "", "\n"));
        writeIfChanged(dnsProperties.upstreamServersFile(), content, lastWrittenUpstream, dnsReloader::restart, "restart");
    }

    private void writeIfChanged(String path, String content, AtomicReference<String> lastWritten,
                                 Runnable applyToRunningDnsmasq, String applyVerb) {
        if (content.equals(lastWritten.get())) {
            return;
        }
        try {
            fileWriter.write(path, content);
            lastWritten.set(content);
        } catch (Exception e) {
            log.warn("Failed to sync {}: {}", path, e.getMessage(), e);
            return;
        }
        try {
            applyToRunningDnsmasq.run();
        } catch (Exception e) {
            log.warn("Wrote {} but failed to {} dnsmasq - it won't pick up the change until its next "
                    + "restart: {}", path, applyVerb, e.getMessage(), e);
        }
    }
}
