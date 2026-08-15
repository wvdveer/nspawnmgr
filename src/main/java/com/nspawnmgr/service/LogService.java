package com.nspawnmgr.service;

import com.nspawnmgr.config.TomcatProperties;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.regex.Pattern;

/**
 * Reads (and lets an admin delete) Tomcat's own log files — no sudo/wrapper-script needed, since
 * nspawnmgr's own webapp process already runs as the same {@code tomcat} OS user that owns
 * {@code logDir} (see tomcat9.service's {@code User=tomcat}).
 *
 * <p>Handles two on-disk shapes, since only the packaged {@code .deb} (via tomcat9.service's
 * rotatelogs pipe) produces dated files at all:
 * <ul>
 *     <li>Real install: {@code catalina.out.<date>.log} files — the lexicographically-last one
 *     (dates sort correctly as strings) is "current," everything else is "rotated out."</li>
 *     <li>Dev/CI stack ({@code catalina.sh start}, no rotatelogs): a single plain
 *     {@code catalina.out}, never rotated — treated as "current" with no rotated logs at all.</li>
 * </ul>
 */
@Service
public class LogService {

    private static final Pattern ROTATED_PATTERN = Pattern.compile("^catalina\\.out\\.\\d{4}-\\d{2}-\\d{2}\\.log$");
    private static final String PLAIN_CURRENT_LOG = "catalina.out";
    private static final int DEFAULT_TAIL_LINES = 100;

    private final TomcatProperties tomcatProperties;

    public LogService(TomcatProperties tomcatProperties) {
        this.tomcatProperties = tomcatProperties;
    }

    public List<String> tailCurrentLog(int lines) {
        List<String> all = readLines(currentLogFile());
        int from = Math.max(0, all.size() - lines);
        return all.subList(from, all.size());
    }

    public List<String> tailCurrentLog() {
        return tailCurrentLog(DEFAULT_TAIL_LINES);
    }

    public String currentLogFull() {
        return String.join("\n", readLines(currentLogFile()));
    }

    /** Newest-first; empty if there's nothing rotated out yet (including the dev/CI plain-file case). */
    public List<String> listRotatedLogs() {
        List<Path> dated = datedLogFiles();
        List<String> rotated = new ArrayList<>();
        for (int i = dated.size() - 2; i >= 0; i--) {
            rotated.add(dated.get(i).getFileName().toString());
        }
        return rotated;
    }

    public String readRotatedLog(String filename) {
        return String.join("\n", readLines(validatedRotatedLogPath(filename)));
    }

    public void deleteRotatedLog(String filename) {
        Path path = validatedRotatedLogPath(filename);
        try {
            Files.delete(path);
        } catch (IOException e) {
            throw new UncheckedIOException("Failed to delete " + filename, e);
        }
    }

    private Path validatedRotatedLogPath(String filename) {
        if (!ROTATED_PATTERN.matcher(filename).matches()) {
            // filename is a user-controlled URL path segment — reject anything that isn't exactly
            // the expected shape before it ever reaches the filesystem, same reasoning as the
            // container-username validation earlier this session (path traversal via a crafted name).
            throw new IllegalArgumentException("Invalid log filename: " + filename);
        }
        return logDir().resolve(filename);
    }

    private Path currentLogFile() {
        List<Path> dated = datedLogFiles();
        if (!dated.isEmpty()) {
            return dated.get(dated.size() - 1);
        }
        Path plain = logDir().resolve(PLAIN_CURRENT_LOG);
        if (Files.exists(plain)) {
            return plain;
        }
        throw new IllegalStateException("No current Tomcat log found in " + logDir());
    }

    /** Ascending by filename (= chronological, since the date suffix string-sorts correctly). */
    private List<Path> datedLogFiles() {
        Path dir = logDir();
        if (!Files.isDirectory(dir)) {
            return List.of();
        }
        try (var stream = Files.list(dir)) {
            return stream
                    .filter(p -> ROTATED_PATTERN.matcher(p.getFileName().toString()).matches())
                    .sorted(Comparator.comparing(p -> p.getFileName().toString()))
                    .toList();
        } catch (IOException e) {
            throw new UncheckedIOException("Failed to list " + dir, e);
        }
    }

    private List<String> readLines(Path path) {
        try {
            return Files.readAllLines(path);
        } catch (IOException e) {
            throw new UncheckedIOException("Failed to read " + path, e);
        }
    }

    private Path logDir() {
        return Path.of(tomcatProperties.logDir());
    }
}
