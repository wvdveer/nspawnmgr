package com.nspawnmgr.service;

import com.nspawnmgr.config.AuthProperties;
import com.nspawnmgr.config.DnsProperties;
import com.nspawnmgr.config.GuacamoleProperties;
import com.nspawnmgr.config.HostProperties;
import com.nspawnmgr.config.NspawnProperties;
import com.nspawnmgr.config.ProvisioningProperties;
import com.nspawnmgr.config.SshProperties;
import com.nspawnmgr.domain.AppSettings;
import com.nspawnmgr.domain.User;
import com.nspawnmgr.repository.AppSettingsRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Regression coverage for the DNS-upstream-servers setting (live-editable via /admin/settings,
 * synced out to dnsmasq by ContainerDnsSyncService): must fall back to the static config default
 * when there's no override, must reject anything that isn't a comma-separated list of IP
 * literals (dnsmasq's own server= directive can't resolve a hostname - it IS the resolver), and
 * must accept a real-world corporate-DNS-server override.
 */
class SettingsServiceTest {

    private AppSettingsRepository appSettingsRepository;
    private AppSettings row;
    private SettingsService service;

    @BeforeEach
    void setUp() {
        appSettingsRepository = mock(AppSettingsRepository.class);
        row = new AppSettings();
        row.setId(1L);
        when(appSettingsRepository.findById(1L)).thenReturn(Optional.of(row));
        when(appSettingsRepository.save(org.mockito.ArgumentMatchers.any())).thenAnswer(invocation -> invocation.getArgument(0));

        DnsProperties dnsProperties = new DnsProperties("/tmp/dns-hosts", "/tmp/dns-upstream.conf", "1.1.1.1,9.9.9.9");
        service = new SettingsService(appSettingsRepository,
                new GuacamoleProperties(null, null, null, null, null),
                new HostProperties(null, null),
                new AuthProperties(null, null, null, null, null, null, null, 0, 0, null,
                        System.getProperty("java.io.tmpdir") + "/nspawnmgr-test-auth.properties"),
                new ProvisioningProperties(null, 0),
                new SshProperties(null, 0, null, null, null, 0, false),
                new NspawnProperties(null, null, null, null),
                dnsProperties);
    }

    @Test
    void fallsBackToStaticDefaultWhenNoOverrideSet() {
        assertThat(service.dnsUpstreamServers()).isEqualTo("1.1.1.1,9.9.9.9");
    }

    @Test
    void acceptsACommaSeparatedListOfIpLiterals() {
        User admin = new User("admin-external-id");
        admin.setId(1L);

        AppSettings saved = service.update(updateWithDns("10.0.0.53,10.0.0.54"), admin);

        assertThat(saved.getDnsUpstreamServers()).isEqualTo("10.0.0.53,10.0.0.54");
        assertThat(service.dnsUpstreamServers()).isEqualTo("10.0.0.53,10.0.0.54");
    }

    @Test
    void acceptsAnIpv6Literal() {
        User admin = new User("admin-external-id");
        admin.setId(1L);

        AppSettings saved = service.update(updateWithDns("2606:4700:4700::1111"), admin);

        assertThat(saved.getDnsUpstreamServers()).isEqualTo("2606:4700:4700::1111");
    }

    @Test
    void rejectsAHostnameInsteadOfAnIpLiteral() {
        User admin = new User("admin-external-id");
        admin.setId(1L);

        assertThatThrownBy(() -> service.update(updateWithDns("dns.corp.example.com"), admin))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("DNS upstream servers");
    }

    @Test
    void rejectsATrailingComma() {
        User admin = new User("admin-external-id");
        admin.setId(1L);

        assertThatThrownBy(() -> service.update(updateWithDns("1.1.1.1,"), admin))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void qemuVncPortRangeFallsBackToDefaultWhenNoOverrideSet() {
        assertThat(service.qemuVncPortRangeStart()).isEqualTo(5900);
        assertThat(service.qemuVncPortRangeEnd()).isEqualTo(5999);
    }

    @Test
    void acceptsAValidQemuVncPortRange() {
        User admin = new User("admin-external-id");
        admin.setId(1L);

        AppSettings saved = service.update(updateWithQemuVncPortRange(6000, 6099), admin);

        assertThat(saved.getQemuVncPortRangeStart()).isEqualTo(6000);
        assertThat(saved.getQemuVncPortRangeEnd()).isEqualTo(6099);
        assertThat(service.qemuVncPortRangeStart()).isEqualTo(6000);
        assertThat(service.qemuVncPortRangeEnd()).isEqualTo(6099);
    }

    @Test
    void rejectsAQemuVncPortRangeBelow5900() {
        User admin = new User("admin-external-id");
        admin.setId(1L);

        assertThatThrownBy(() -> service.update(updateWithQemuVncPortRange(5000, 5099), admin))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("5900");
    }

    @Test
    void rejectsAQemuVncPortRangeWhereStartIsAfterEnd() {
        User admin = new User("admin-external-id");
        admin.setId(1L);

        assertThatThrownBy(() -> service.update(updateWithQemuVncPortRange(6099, 6000), admin))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("start must not be after end");
    }

    @Test
    void rejectsAQemuVncPortRangeWithOnlyOneEndSet() {
        User admin = new User("admin-external-id");
        admin.setId(1L);

        assertThatThrownBy(() -> service.update(updateWithQemuVncPortRange(6000, null), admin))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("both start and end");
    }

    private static SettingsService.SettingsUpdate updateWithQemuVncPortRange(Integer start, Integer end) {
        return new SettingsService.SettingsUpdate(
                null, null, null,
                null, null, null,
                null, null, null,
                null, null, null,
                null, null,
                null, null, null,
                null, null,
                null, null, null, null,
                null, null, null,
                null, null, null,
                null, null, null,
                null, start, end);
    }

    private static SettingsService.SettingsUpdate updateWithDns(String dnsUpstreamServers) {
        return new SettingsService.SettingsUpdate(
                null, null, null,
                null, null, null,
                null, null, null,
                null, null, null,
                null, null,
                null, null, null,
                null, null,
                null, null, null, null,
                null, null, null,
                null, null, null,
                null, null, null,
                dnsUpstreamServers, null, null);
    }
}
