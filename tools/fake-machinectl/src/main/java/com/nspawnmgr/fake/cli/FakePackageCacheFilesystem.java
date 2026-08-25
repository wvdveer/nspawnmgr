package com.nspawnmgr.fake.cli;

import com.nspawnmgr.cli.ContainerCliException;
import com.nspawnmgr.cli.DownloadedPackage;
import com.nspawnmgr.cli.PackageCacheFilesystem;
import com.nspawnmgr.config.NspawnProperties;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.List;

/** File copying isn't OS-specific, so this reuses plain NIO logic, same as FakeContainerFilesystemProvisioner. */
@Component
@Profile("dev")
public class FakePackageCacheFilesystem implements PackageCacheFilesystem {

    private final NspawnProperties properties;

    public FakePackageCacheFilesystem(NspawnProperties properties) {
        this.properties = properties;
    }

    @Override
    public void upload(String targetPath, InputStream content) {
        try {
            Path target = Path.of(targetPath);
            Files.createDirectories(target.getParent());
            Files.copy(content, target, StandardCopyOption.REPLACE_EXISTING);
        } catch (IOException e) {
            throw new ContainerCliException("Failed to upload package to " + targetPath, e);
        }
    }

    @Override
    public void delete(String path) {
        try {
            Files.deleteIfExists(Path.of(path));
        } catch (IOException e) {
            throw new ContainerCliException("Failed to delete cached package " + path, e);
        }
    }

    @Override
    public void copyIntoContainer(String sourcePath, String destDir) {
        try {
            Path source = Path.of(sourcePath);
            Path dest = Path.of(destDir);
            Files.createDirectories(dest);
            if (Files.exists(source)) {
                Files.copy(source, dest.resolve(source.getFileName()), StandardCopyOption.REPLACE_EXISTING);
            }
        } catch (IOException e) {
            throw new ContainerCliException("Failed to copy " + sourcePath + " into " + destDir, e);
        }
    }

    @Override
    public void copyIntoPodmanContainer(String sourcePath, String containerName, String destPathInContainer) {
        // No real podman/`podman cp` path in dev mode - writes into the same dev-mode stub
        // directory convention FakeContainerFilesystemProvisioner's own methods already use
        // (properties.machinesDir()/<containerName>/...), just proving the caller's own wiring.
        try {
            Path source = Path.of(sourcePath);
            Path dest = Path.of(properties.machinesDir(), containerName, destPathInContainer);
            Files.createDirectories(dest.getParent());
            if (Files.exists(source)) {
                Files.copy(source, dest, StandardCopyOption.REPLACE_EXISTING);
            }
        } catch (IOException e) {
            throw new ContainerCliException("Failed to copy " + sourcePath + " into " + containerName + ":" + destPathInContainer, e);
        }
    }

    @Override
    public List<DownloadedPackage> listFiles(String dir) {
        // No real "auto" cache dir gets populated in dev mode - FakeContainerFilesystemProvisioner's
        // own downloadPackagesIntoContainer fabricates plausible results without writing anything to
        // disk (see that class's own comment) - so this only needs to prove the wiring works, not
        // return anything meaningful. Still lists whatever's really there, so it's not a total no-op
        // if something else happened to have written files to this path.
        Path path = Path.of(dir);
        if (!Files.isDirectory(path)) {
            return List.of();
        }
        List<DownloadedPackage> result = new ArrayList<>();
        try (var stream = Files.list(path)) {
            for (Path file : stream.filter(Files::isRegularFile).toList()) {
                result.add(new DownloadedPackage(file.getFileName().toString(), Files.size(file)));
            }
        } catch (IOException e) {
            return List.of();
        }
        return result;
    }
}
