package com.nspawnmgr.fake.cli;

import com.nspawnmgr.cli.ContainerCliException;
import com.nspawnmgr.cli.DownloadedPackage;
import com.nspawnmgr.cli.PackageCacheFilesystem;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.List;

/** File copying isn't OS-specific, so this reuses plain NIO logic, same as FakeContainerFilesystemProvisioner. */
@Component
@Profile("dev")
public class FakePackageCacheFilesystem implements PackageCacheFilesystem {

    @Override
    public void upload(String targetPath, byte[] content) {
        try {
            Path target = Path.of(targetPath);
            Files.createDirectories(target.getParent());
            Files.write(target, content);
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
