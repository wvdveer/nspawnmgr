package com.nspawnmgr.service;

import com.nspawnmgr.cli.ContainerCliException;
import com.nspawnmgr.cli.ContainerCliExecutor;
import com.nspawnmgr.cli.ContainerPortProbe;
import com.nspawnmgr.domain.Container;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class HostLivenessServiceTest {

    private ContainerPortProbe portProbe;
    private ContainerCliExecutor cliExecutor;
    private HostLivenessService service;
    private Container host;

    @BeforeEach
    void setUp() {
        portProbe = mock(ContainerPortProbe.class);
        cliExecutor = mock(ContainerCliExecutor.class);
        service = new HostLivenessService(portProbe, cliExecutor);
        host = Container.external("myhost", null, "myhost.lan");
        host.setId(1L);
        host.setExternalSshPort(22);
    }

    @Test
    void resolvedAddressReturnsTheCliExecutorsResolvedValue() {
        when(portProbe.isOpen(anyString(), anyInt())).thenReturn(true);
        when(cliExecutor.resolveHostname("myhost.lan")).thenReturn("192.168.1.50");

        assertThat(service.resolvedAddress(host)).isEqualTo("192.168.1.50");
    }

    @Test
    void resolvedAddressFallsBackToTheRawConfiguredHostnameOnFailure() {
        when(portProbe.isOpen(anyString(), anyInt())).thenReturn(true);
        when(cliExecutor.resolveHostname("myhost.lan")).thenThrow(new ContainerCliException("boom"));

        assertThat(service.resolvedAddress(host)).isEqualTo("myhost.lan");
    }

    @Test
    void probesTheResolvedAddressNotTheRawConfiguredHostname() {
        // The raw name only resolves on the bare host, not inside nspawnmgr's own isolated container,
        // so a probe against it from the JVM would wrongly report a live host as unreachable.
        when(cliExecutor.resolveHostname("myhost.lan")).thenReturn("192.168.1.50");
        when(portProbe.isOpen("192.168.1.50", 22)).thenReturn(true);

        assertThat(service.isReachable(host)).isTrue();
        verify(portProbe, never()).isOpen(eq("myhost.lan"), anyInt());
    }

    @Test
    void probesTheRawHostnameWhenResolutionFails() {
        when(cliExecutor.resolveHostname("myhost.lan")).thenThrow(new ContainerCliException("boom"));
        when(portProbe.isOpen("myhost.lan", 22)).thenReturn(true);

        assertThat(service.isReachable(host)).isTrue();
    }

    @Test
    void reachableWhenAnyConfiguredPortIsOpenNotJustTheFirst() {
        host.setExternalRdpPort(3389);
        host.setExternalVncPort(5900);
        when(cliExecutor.resolveHostname("myhost.lan")).thenReturn("192.168.1.50");
        when(portProbe.isOpen("192.168.1.50", 22)).thenReturn(false);
        when(portProbe.isOpen("192.168.1.50", 3389)).thenReturn(true);

        assertThat(service.isReachable(host)).isTrue();
    }

    @Test
    void unreachableOnlyWhenEveryConfiguredPortIsClosed() {
        host.setExternalRdpPort(3389);
        when(cliExecutor.resolveHostname("myhost.lan")).thenReturn("192.168.1.50");
        when(portProbe.isOpen(anyString(), anyInt())).thenReturn(false);

        assertThat(service.isReachable(host)).isFalse();
        verify(portProbe).isOpen("192.168.1.50", 22);
        verify(portProbe).isOpen("192.168.1.50", 3389);
    }

    @Test
    void hostWithNoConfiguredPortIsReportedReachable() {
        host.setExternalSshPort(null);

        assertThat(service.isReachable(host)).isTrue();
        verify(portProbe, never()).isOpen(anyString(), anyInt());
    }

    @Test
    void reachabilityAndResolvedAddressAreCachedTogetherForOneMinute() {
        when(portProbe.isOpen(anyString(), anyInt())).thenReturn(true);
        when(cliExecutor.resolveHostname("myhost.lan")).thenReturn("192.168.1.50");

        assertThat(service.isReachable(host)).isTrue();
        assertThat(service.resolvedAddress(host)).isEqualTo("192.168.1.50");
        assertThat(service.resolvedAddress(host)).isEqualTo("192.168.1.50");

        // Both isReachable and the two resolvedAddress calls above hit the same cached entry - the
        // real probe/resolve should only ever run once.
        verify(cliExecutor, times(1)).resolveHostname("myhost.lan");
        verify(portProbe, times(1)).isOpen(anyString(), anyInt());
    }
}
