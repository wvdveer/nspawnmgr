package com.nspawnmgr.cli.real;

import com.nspawnmgr.cli.CommandResult;
import com.nspawnmgr.cli.ContainerCliException;
import com.nspawnmgr.cli.DnsReloader;
import com.nspawnmgr.service.SettingsService;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

import java.nio.file.Path;
import java.time.Duration;
import java.util.List;

@Component
@Profile("!dev")
public class RealDnsReloader implements DnsReloader {

    private final SettingsService settingsService;
    private final SshRemoteExecutor ssh;

    public RealDnsReloader(SettingsService settingsService, SshRemoteExecutor ssh) {
        this.settingsService = settingsService;
        this.ssh = ssh;
    }

    @Override
    public void reload() {
        run("nspawnmgr-reload-dnsmasq.sh", "reload");
    }

    @Override
    public void restart() {
        run("nspawnmgr-restart-dnsmasq.sh", "restart");
    }

    private void run(String scriptName, String verb) {
        String scriptPath = Path.of(settingsService.nspawnPrivilegedScriptsDir(), scriptName).toString();
        CommandResult result = ssh.execNoPasswordSudo(Duration.ofSeconds(15), List.of(scriptPath));
        if (!result.success()) {
            throw new ContainerCliException("Failed to " + verb + " dnsmasq: " + result.stderr());
        }
    }
}
