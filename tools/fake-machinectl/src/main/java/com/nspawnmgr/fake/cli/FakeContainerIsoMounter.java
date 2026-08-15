package com.nspawnmgr.fake.cli;

import com.nspawnmgr.cli.ContainerIsoMounter;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Instant;

/** Stands in for host loop-mount/machinectl-bind management on dev machines: just logs the intended action. */
@Component
@Profile("dev")
public class FakeContainerIsoMounter implements ContainerIsoMounter {

    private final Path logFile;

    public FakeContainerIsoMounter() {
        this.logFile = Path.of(System.getProperty("java.io.tmpdir"), "nspawnmgr-dev", "fake-machinectl.log");
    }

    @Override
    public void mount(String machineName, String isoHostSourcePath) {
        log("iso-mount " + machineName + " source=" + isoHostSourcePath);
    }

    @Override
    public void unmount(String machineName) {
        log("iso-unmount " + machineName);
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
