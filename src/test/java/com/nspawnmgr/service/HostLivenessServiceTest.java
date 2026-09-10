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
import static org.mockito.Mockito.mock;
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
