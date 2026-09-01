package com.nspawnmgr.cli.real;

import com.nspawnmgr.cli.CommandResult;
import com.nspawnmgr.cli.ContainerCliException;
import com.nspawnmgr.cli.PackageDownloadExecutor;
import com.nspawnmgr.cli.PackageDownloadUnitStatus;
import com.nspawnmgr.service.SettingsService;
import com.nspawnmgr.service.UserMessages;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

import java.nio.file.Path;
import java.time.Duration;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

@Component
@Profile("!dev")
public class RealPackageDownloadExecutor implements PackageDownloadExecutor {

    private static final Logger log = LoggerFactory.getLogger(RealPackageDownloadExecutor.class);

    private final SettingsService settingsService;
    private final SshRemoteExecutor ssh;
    private final UserMessages messages;

    public RealPackageDownloadExecutor(SettingsService settingsService, SshRemoteExecutor ssh, UserMessages messages) {
        this.settingsService = settingsService;
        this.ssh = ssh;
        this.messages = messages;
    }

    @Override
    public Long probeContentLength(String url) {
        // -L: follow redirects (a Content-Length on an intermediate redirect response is
        // meaningless) - curl reports headers for every hop it follows, so the LAST Content-Length
        // line in the output is the one that matters. Best-effort: a server that doesn't support
        // HEAD, doesn't report a length, or is simply slow to respond just means no known total -
        // never throws, never blocks the actual download on this succeeding.
        CommandResult result = ssh.execNoPasswordSudo(Duration.ofSeconds(10),
                List.of("curl", "-sIL", "--max-time", "8", url));
        if (!result.success()) {
            return null;
        }
        Long lastLength = null;
        for (String line : result.stdout().lines().toList()) {
            if (line.regionMatches(true, 0, "Content-Length:", 0, "Content-Length:".length())) {
                try {
                    lastLength = Long.parseLong(line.substring(line.indexOf(':') + 1).trim());
                } catch (NumberFormatException ignored) {
                    // Malformed header value - keep whatever was already found from an earlier hop.
                }
            }
        }
        return lastLength;
    }

    @Override
    public void start(String downloadId, String url, String targetPath) {
        String scriptPath = Path.of(settingsService.nspawnPrivilegedScriptsDir(), "nspawnmgr-download-package-start.sh").toString();
        CommandResult result = ssh.execNoPasswordSudo(Duration.ofSeconds(15), List.of(scriptPath, downloadId, url, targetPath));
        if (!result.success()) {
            throw new ContainerCliException(messages.get("error.cli.failedToStartDownload", downloadId, result.stderr()));
        }
    }

    @Override
    public long currentBytes(String targetPath) {
        CommandResult result = ssh.execNoPasswordSudo(Duration.ofSeconds(10), List.of("stat", "-c%s", targetPath));
        if (!result.success()) {
            // Not written yet (or already cleaned up after a failure) - not an error, same
            // "empty/absent = not ready" convention as e.g. getInternalAddress.
            return 0;
        }
        try {
            return Long.parseLong(result.stdout().trim());
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    @Override
    public PackageDownloadUnitStatus status(String downloadId) {
        CommandResult result = ssh.execNoPasswordSudo(Duration.ofSeconds(10),
                List.of("systemctl", "show", unit(downloadId), "--property=LoadState,ActiveState,ExecMainStatus"));
        if (!result.success()) {
            return PackageDownloadUnitStatus.NOT_FOUND;
        }
        Map<String, String> values = new HashMap<>();
        for (String line : result.stdout().lines().toList()) {
            int eq = line.indexOf('=');
            if (eq > 0) {
                values.put(line.substring(0, eq).strip(), line.substring(eq + 1).strip());
            }
        }
        if (!"loaded".equals(values.get("LoadState"))) {
            return PackageDownloadUnitStatus.NOT_FOUND;
        }
        String activeState = values.get("ActiveState");
        if ("active".equals(activeState) || "activating".equals(activeState)) {
            return PackageDownloadUnitStatus.RUNNING;
        }
        // ExecMainStatus is only meaningful once the unit has actually exited - "0" for a clean
        // curl run, non-zero for anything else (a 404 via -f, a network failure, a signal from
        // stop()). Missing/unparseable is treated as failure rather than silently reporting success.
        String execMainStatus = values.get("ExecMainStatus");
        if ("failed".equals(activeState) || !"0".equals(execMainStatus)) {
            return PackageDownloadUnitStatus.FAILED;
        }
        return PackageDownloadUnitStatus.SUCCEEDED;
    }

    @Override
    public void stop(String downloadId) {
        CommandResult result = ssh.execNoPasswordSudo(Duration.ofSeconds(10), List.of("systemctl", "stop", unit(downloadId)));
        if (!result.success()) {
            log.warn("Failed to stop download unit {} (exit {}): {}", unit(downloadId), result.exitCode(), result.stderr());
        }
    }

    private static String unit(String downloadId) {
        return "nspawnmgr-download-" + downloadId + ".service";
    }
}
