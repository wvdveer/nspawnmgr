package com.nspawnmgr.cli.real;

import com.nspawnmgr.cli.ContainerCliException;
import com.nspawnmgr.cli.TomcatRestartService;
import com.nspawnmgr.service.SettingsService;
import com.nspawnmgr.service.UserMessages;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

import java.nio.file.Path;
import java.time.Duration;
import java.util.List;

/**
 * Runs the nspawnmgr-restart-tomcat.sh wrapper script (shipped by the .deb, see
 * packaging/nspawnmgr-deb/privileged-scripts/) under the same NOPASSWD sudoers grant as every
 * other routine, always-safe privileged operation. That script issues
 * {@code systemctl restart --no-block tomcat9} — "no-block" queues the restart and returns almost
 * instantly, rather than waiting for tomcat9 to actually go down (which would never return at
 * all, since this very SSH command is served by the Tomcat instance about to restart).
 */
@Component
@Profile("!dev")
public class RealTomcatRestartService implements TomcatRestartService {

    private final SettingsService settingsService;
    private final SshRemoteExecutor ssh;
    private final UserMessages messages;

    public RealTomcatRestartService(SettingsService settingsService, SshRemoteExecutor ssh, UserMessages messages) {
        this.settingsService = settingsService;
        this.ssh = ssh;
        this.messages = messages;
    }

    @Override
    public void restart() {
        String script = Path.of(settingsService.nspawnPrivilegedScriptsDir(), "nspawnmgr-restart-tomcat.sh").toString();
        var result = ssh.execNoPasswordSudo(Duration.ofSeconds(10), List.of(script));
        if (!result.success()) {
            throw new ContainerCliException(messages.get("error.cli.failedToTriggerTomcatRestart", result.stderr()));
        }
    }
}
