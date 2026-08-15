package com.nspawnmgr.fake.cli;

import com.nspawnmgr.cli.ContainerOutboundAccessManager;
import com.nspawnmgr.domain.ContainerOutboundAllowlistEntry;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Instant;
import java.util.List;
import java.util.stream.Collectors;

/** Stands in for host firewall management on dev machines: just logs the intended action. */
@Component
@Profile("dev")
public class FakeContainerOutboundAccessManager implements ContainerOutboundAccessManager {

    private final Path logFile;

    public FakeContainerOutboundAccessManager() {
        this.logFile = Path.of(System.getProperty("java.io.tmpdir"), "nspawnmgr-dev", "fake-machinectl.log");
    }

    @Override
    public void sync(String machineName, boolean outboundEnabled, List<ContainerOutboundAllowlistEntry> allowlist) {
        String entries = allowlist.stream()
                .map(e -> e.getDestinationHost() + ":" + e.getDestinationPort() + "/" + e.getProtocol())
                .collect(Collectors.joining(","));
        log("outbound-sync " + machineName + " enabled=" + outboundEnabled + " allowlist=[" + entries + "]");
    }

    private void log(String line) {
        try {
            Files.createDirectories(logFile.getParent());
            Files.writeString(logFile, Instant.now() + " " + line + System.lineSeparator(),
                    StandardOpenOption.CREATE, StandardOpenOption.APPEND);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
