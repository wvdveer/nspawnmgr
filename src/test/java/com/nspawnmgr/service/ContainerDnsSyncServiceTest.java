package com.nspawnmgr.service;

import com.nspawnmgr.cli.DnsReloader;
import com.nspawnmgr.cli.TomcatConfigWriter;
import com.nspawnmgr.config.DnsProperties;
import com.nspawnmgr.domain.Container;
import com.nspawnmgr.domain.ContainerKind;
import com.nspawnmgr.domain.ContainerState;
import com.nspawnmgr.repository.ContainerRepository;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Regression coverage for keeping dnsmasq's two owned files in sync: the addn-hosts file and the
 * upstream-servers file (/etc/dnsmasq.d/nspawnmgr-upstream.conf, from the live-editable
 * SettingsService.dnsUpstreamServers() setting) — both need a write-if-changed, but with
 * DELIBERATELY different follow-up: confirmed live, dnsmasq only parses server= lines at process
 * startup, so the upstream file needs a full DnsReloader.restart(), while addn-hosts is
 * SIGHUP-reloadable via DnsReloader.reload() alone. A failure syncing one file must not skip the
 * other, and must not apply the wrong one's follow-up.
 */
class ContainerDnsSyncServiceTest {

    private static final String HOSTS_FILE = "/etc/nspawnmgr/dns-hosts";
    private static final String UPSTREAM_FILE = "/etc/dnsmasq.d/nspawnmgr-upstream.conf";

    @Test
    void writesBothFilesAndAppliesEachOwnFollowUpOnFirstSync() {
        ContainerRepository containerRepository = mock(ContainerRepository.class);
        TomcatConfigWriter fileWriter = mock(TomcatConfigWriter.class);
        DnsReloader dnsReloader = mock(DnsReloader.class);
        DnsProperties dnsProperties = new DnsProperties(HOSTS_FILE, UPSTREAM_FILE, "1.1.1.1,9.9.9.9");
        SettingsService settingsService = mock(SettingsService.class);

        when(containerRepository.findByKindAndStateAndInternalAddressIsNotNull(ContainerKind.MANAGED, ContainerState.RUNNING))
                .thenReturn(List.of());
        when(settingsService.dnsUpstreamServers()).thenReturn("9.9.9.10,1.0.0.1");

        ContainerDnsSyncService service = new ContainerDnsSyncService(containerRepository, fileWriter, dnsReloader,
                dnsProperties, settingsService);
        service.sync();

        verify(fileWriter).write(UPSTREAM_FILE, "server=9.9.9.10\nserver=1.0.0.1\n");
        verify(dnsReloader, times(1)).reload();
        verify(dnsReloader, times(1)).restart();
    }

    @Test
    void doesNotRewriteOrReapplyWhenNothingChanged() {
        ContainerRepository containerRepository = mock(ContainerRepository.class);
        TomcatConfigWriter fileWriter = mock(TomcatConfigWriter.class);
        DnsReloader dnsReloader = mock(DnsReloader.class);
        DnsProperties dnsProperties = new DnsProperties(HOSTS_FILE, UPSTREAM_FILE, "1.1.1.1,9.9.9.9");
        SettingsService settingsService = mock(SettingsService.class);

        when(containerRepository.findByKindAndStateAndInternalAddressIsNotNull(any(), any())).thenReturn(List.of());
        when(settingsService.dnsUpstreamServers()).thenReturn("1.1.1.1,9.9.9.9");

        ContainerDnsSyncService service = new ContainerDnsSyncService(containerRepository, fileWriter, dnsReloader,
                dnsProperties, settingsService);
        service.sync();
        service.sync();

        verify(fileWriter, times(1)).write(eq(UPSTREAM_FILE), any());
        verify(dnsReloader, times(1)).reload();
        verify(dnsReloader, times(1)).restart();
    }

    @Test
    void aFailedUpstreamWriteDoesNotPreventTheHostsFileFromBeingSyncedOrRestartDnsmasq() {
        ContainerRepository containerRepository = mock(ContainerRepository.class);
        TomcatConfigWriter fileWriter = mock(TomcatConfigWriter.class);
        DnsReloader dnsReloader = mock(DnsReloader.class);
        DnsProperties dnsProperties = new DnsProperties(HOSTS_FILE, UPSTREAM_FILE, "1.1.1.1,9.9.9.9");
        SettingsService settingsService = mock(SettingsService.class);

        Container running = new Container();
        running.setName("b1");
        running.setKind(ContainerKind.MANAGED);
        running.setState(ContainerState.RUNNING);
        running.setInternalAddress("10.0.3.5");
        when(containerRepository.findByKindAndStateAndInternalAddressIsNotNull(ContainerKind.MANAGED, ContainerState.RUNNING))
                .thenReturn(List.of(running));
        when(settingsService.dnsUpstreamServers()).thenReturn("1.1.1.1,9.9.9.9");
        org.mockito.Mockito.doThrow(new RuntimeException("SSH hiccup")).when(fileWriter).write(eq(UPSTREAM_FILE), any());

        ContainerDnsSyncService service = new ContainerDnsSyncService(containerRepository, fileWriter, dnsReloader,
                dnsProperties, settingsService);
        service.sync();

        verify(fileWriter).write(HOSTS_FILE, "10.0.3.5 b1\n");
        // The hosts-file write succeeded and triggered its own reload(); the failed upstream-file
        // write is caught and logged inside its own writeIfChanged call, so restart() is never
        // reached - reload() must not be substituted for it, or a real server= change would look
        // "handled" without ever actually taking effect on a live host.
        verify(dnsReloader, times(1)).reload();
        verify(dnsReloader, never()).restart();
    }

    @Test
    void aFailedUpstreamRestartIsLoggedButDoesNotAffectTheHostsFileReload() {
        ContainerRepository containerRepository = mock(ContainerRepository.class);
        TomcatConfigWriter fileWriter = mock(TomcatConfigWriter.class);
        DnsReloader dnsReloader = mock(DnsReloader.class);
        DnsProperties dnsProperties = new DnsProperties(HOSTS_FILE, UPSTREAM_FILE, "1.1.1.1,9.9.9.9");
        SettingsService settingsService = mock(SettingsService.class);

        when(containerRepository.findByKindAndStateAndInternalAddressIsNotNull(any(), any())).thenReturn(List.of());
        when(settingsService.dnsUpstreamServers()).thenReturn("1.1.1.1,9.9.9.9");
        org.mockito.Mockito.doThrow(new RuntimeException("dnsmasq restart failed")).when(dnsReloader).restart();

        ContainerDnsSyncService service = new ContainerDnsSyncService(containerRepository, fileWriter, dnsReloader,
                dnsProperties, settingsService);
        service.sync();

        verify(dnsReloader, times(1)).reload();
        verify(dnsReloader, times(1)).restart();
    }

    /**
     * Regression coverage for publishing the host's own external hostname into the addn-hosts file,
     * pointing at nspawnbr0's fixed bridge address - a managed container otherwise has no route to
     * anything the host forwards back in (e.g. a custom port mapping) at all.
     */
    @Test
    void publishesTheExternalHostnamePointingAtTheBridgeAddress() {
        ContainerRepository containerRepository = mock(ContainerRepository.class);
        TomcatConfigWriter fileWriter = mock(TomcatConfigWriter.class);
        DnsReloader dnsReloader = mock(DnsReloader.class);
        DnsProperties dnsProperties = new DnsProperties(HOSTS_FILE, UPSTREAM_FILE, "1.1.1.1,9.9.9.9");
        SettingsService settingsService = mock(SettingsService.class);

        Container running = new Container();
        running.setName("b1");
        running.setKind(ContainerKind.MANAGED);
        running.setState(ContainerState.RUNNING);
        running.setInternalAddress("10.0.3.5");
        when(containerRepository.findByKindAndStateAndInternalAddressIsNotNull(ContainerKind.MANAGED, ContainerState.RUNNING))
                .thenReturn(List.of(running));
        when(settingsService.dnsUpstreamServers()).thenReturn("1.1.1.1,9.9.9.9");
        when(settingsService.hostExternalHostname()).thenReturn("yoga");

        ContainerDnsSyncService service = new ContainerDnsSyncService(containerRepository, fileWriter, dnsReloader,
                dnsProperties, settingsService);
        service.sync();

        verify(fileWriter).write(HOSTS_FILE, "10.0.3.5 b1\n10.100.0.1 yoga\n");
    }

    /** "localhost" is application.yml's own ${HOST_EXTERNAL_HOSTNAME:localhost} default - means
     *  never actually detected/configured (setup-sudo-account.sh always overwrites it with a real
     *  detected hostname during install), so it must not itself get mapped to the bridge address. */
    @Test
    void doesNotPublishTheUnconfiguredDefaultExternalHostname() {
        ContainerRepository containerRepository = mock(ContainerRepository.class);
        TomcatConfigWriter fileWriter = mock(TomcatConfigWriter.class);
        DnsReloader dnsReloader = mock(DnsReloader.class);
        DnsProperties dnsProperties = new DnsProperties(HOSTS_FILE, UPSTREAM_FILE, "1.1.1.1,9.9.9.9");
        SettingsService settingsService = mock(SettingsService.class);

        when(containerRepository.findByKindAndStateAndInternalAddressIsNotNull(any(), any())).thenReturn(List.of());
        when(settingsService.dnsUpstreamServers()).thenReturn("1.1.1.1,9.9.9.9");
        when(settingsService.hostExternalHostname()).thenReturn("localhost");

        ContainerDnsSyncService service = new ContainerDnsSyncService(containerRepository, fileWriter, dnsReloader,
                dnsProperties, settingsService);
        service.sync();

        verify(fileWriter).write(HOSTS_FILE, "\n");
    }
}
