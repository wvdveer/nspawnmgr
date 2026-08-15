package com.nspawnmgr.service;

import com.nspawnmgr.cli.ContainerFilesystemBrowser;
import com.nspawnmgr.cli.FileEntry;
import com.nspawnmgr.domain.Container;
import org.springframework.stereotype.Service;

import java.nio.file.Path;
import java.util.Comparator;
import java.util.List;
import java.util.regex.Pattern;

/**
 * Browse/upload/download a container's rootfs directory tree - backs the per-container Files page.
 * Owns the one genuinely new security-critical piece this feature needed: validating a
 * user-supplied in-rootfs path before it's ever handed to {@link ContainerFilesystemBrowser}, which
 * (like every privileged-script caller in this codebase) does no validation of its own. Two
 * validation shapes, both "reject known-bad, then verify the resolved result," following
 * {@link LogService}'s {@code validatedRotatedLogPath} precedent style.
 */
@Service
public class ContainerFileBrowserService {

    private static final Pattern SAFE_FILENAME = Pattern.compile("^[^/\\\\\\x00]+$");

    private final SettingsService settingsService;
    private final ContainerFilesystemBrowser browser;

    public ContainerFileBrowserService(SettingsService settingsService, ContainerFilesystemBrowser browser) {
        this.settingsService = settingsService;
        this.browser = browser;
    }

    /** Directories sorted first, then alphabetically within each group. */
    public List<FileEntry> list(Container container, String relativeDir) {
        Path root = rootfsRoot(container);
        String validated = validateRelativePath(root, relativeDir);
        return browser.list(root.toString(), validated).stream()
                .sorted(Comparator.comparing(FileEntry::directory).reversed()
                        .thenComparing(FileEntry::name, String.CASE_INSENSITIVE_ORDER))
                .toList();
    }

    public byte[] download(Container container, String relativePath) {
        Path root = rootfsRoot(container);
        String validated = validateRelativePath(root, relativePath);
        return browser.download(root.toString(), validated);
    }

    /** Rejects a name collision with whatever's already at the target - see
     *  nspawnmgr-upload-rootfs-file.sh, which is the actual (race-free) source of truth for this;
     *  RealContainerFilesystemBrowser/FakeContainerFilesystemBrowser both surface that as an
     *  IllegalArgumentException, which reaches the browser as a clear 400 rather than a silent
     *  overwrite. */
    public void upload(Container container, String relativeDir, String filename, byte[] content) {
        Path root = rootfsRoot(container);
        String validatedDir = validateRelativePath(root, relativeDir);
        String validatedFilename = validateFilename(filename);
        browser.upload(root.toString(), validatedDir, validatedFilename, content);
    }

    private Path rootfsRoot(Container container) {
        return Path.of(settingsService.nspawnMachinesDir(), container.getName());
    }

    /**
     * Validates a user-supplied relative directory/file path before it's ever joined onto the
     * container's rootfs root and handed to a privileged script. Two layers:
     * <ol>
     *     <li>Reject any ".." segment, a leading "/" or "\" (absolute), and null bytes, before
     *     doing any filesystem resolution.</li>
     *     <li>Resolve {@code root.resolve(relativePath).normalize()} and verify the result still
     *     starts with {@code root} - defense in depth against anything the segment check missed,
     *     since this is the first place the app resolves a user-controlled path toward a
     *     privileged filesystem operation and nothing downstream validates it either.</li>
     * </ol>
     * Returns the root-relative form of the validated, normalized path - the caller (and
     * {@link ContainerFilesystemBrowser}) always re-joins this onto the root itself, never does
     * path math with the raw input again.
     */
    private String validateRelativePath(Path root, String relativePath) {
        if (relativePath == null || relativePath.isBlank()) {
            return "";
        }
        if (relativePath.indexOf('\0') >= 0) {
            throw new IllegalArgumentException("Invalid path: contains a null byte");
        }
        if (relativePath.startsWith("/") || relativePath.startsWith("\\")) {
            throw new IllegalArgumentException("Invalid path: must be relative");
        }
        for (String segment : relativePath.split("[/\\\\]")) {
            if (segment.equals("..")) {
                throw new IllegalArgumentException("Invalid path: '..' is not allowed");
            }
        }
        Path resolved = root.resolve(relativePath).normalize();
        if (!resolved.equals(root) && !resolved.startsWith(root)) {
            throw new IllegalArgumentException("Invalid path: escapes the container's filesystem");
        }
        return root.relativize(resolved).toString();
    }

    /** Upload target filename: a single path segment, never a path - permissive on characters
     *  (arbitrary uploaded filenames are legitimate), the only real security surface is that it
     *  must contain no separators. */
    private String validateFilename(String filename) {
        if (filename == null || filename.isBlank() || !SAFE_FILENAME.matcher(filename).matches()
                || filename.equals(".") || filename.equals("..")) {
            throw new IllegalArgumentException("Invalid filename: " + filename);
        }
        return filename;
    }
}
