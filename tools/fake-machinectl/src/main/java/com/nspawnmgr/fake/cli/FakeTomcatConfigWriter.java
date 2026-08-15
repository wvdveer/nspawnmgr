package com.nspawnmgr.fake.cli;

import com.nspawnmgr.cli.TomcatConfigWriter;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;

/** Writes the file directly — dev's Tomcat runs as the same OS user as the JVM, no SSH/sudo needed. */
@Component
@Profile("dev")
public class FakeTomcatConfigWriter implements TomcatConfigWriter {

    @Override
    public void write(String path, String content) {
        try {
            Path target = Path.of(path);
            if (target.getParent() != null) {
                Files.createDirectories(target.getParent());
            }
            Files.writeString(target, content);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
