package com.nspawnmgr.cli;

/**
 * Tells the host's dnsmasq to pick up freshly-written config. Confirmed live (yoga,
 * 2026-08-06): dnsmasq only re-reads {@code addn-hosts} on SIGHUP or restart — there is no
 * automatic/inotify-based reload for it, unlike {@code dhcp-hostsdir} — so {@link
 * com.nspawnmgr.service.ContainerDnsSyncService} writing the file alone is not enough; every write
 * needs a matching {@link #reload()} or dnsmasq keeps answering from whatever it read at its own
 * last start.
 */
public interface DnsReloader {

    /** SIGHUP-equivalent (see {@code nspawnmgr-reload-dnsmasq.sh}) — picks up a changed {@code
     *  addn-hosts} file, but NOT the {@code server=} lines {@link #restart()} is for; confirmed
     *  live, dnsmasq only parses those at process startup. */
    void reload();

    /**
     * Full dnsmasq restart — needed for the upstream-servers file (see {@code
     * ContainerDnsSyncService#syncUpstreamServersFile}): {@code server=} is a structural directive
     * dnsmasq only parses at process startup, confirmed live (same as {@code domain=}/{@code
     * expand-hosts}/{@code local=}) — unlike {@code addn-hosts}, {@link #reload()}'s SIGHUP does
     * NOT pick up a changed {@code server=} line. Heavier than {@link #reload()} (briefly drops
     * every container's DNS), so only called when the upstream-servers file actually changed, not
     * on every sync tick.
     */
    void restart();
}
