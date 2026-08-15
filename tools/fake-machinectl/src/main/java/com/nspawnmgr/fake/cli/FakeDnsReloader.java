package com.nspawnmgr.fake.cli;

import com.nspawnmgr.cli.DnsReloader;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

/** No real dnsmasq in dev mode - nothing to reload. */
@Component
@Profile("dev")
public class FakeDnsReloader implements DnsReloader {

    @Override
    public void reload() {
        // no-op
    }

    @Override
    public void restart() {
        // no-op
    }
}
