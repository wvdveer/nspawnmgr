package com.nspawnmgr.fake.cli;

import com.nspawnmgr.cli.ContainerCliException;
import com.nspawnmgr.cli.ContainerFilesystemProvisioner;
import com.nspawnmgr.cli.DownloadedPackage;
import com.nspawnmgr.cli.FileTreeCopier;
import com.nspawnmgr.cli.NspawnSettingsRenderer;
import com.nspawnmgr.config.NspawnProperties;
import com.nspawnmgr.domain.Container;
import com.nspawnmgr.domain.ContainerBackend;
import com.nspawnmgr.domain.ContainerPortMapping;
import com.nspawnmgr.domain.MinimalTemplateFlavor;
import com.nspawnmgr.domain.PackageManager;
import com.nspawnmgr.domain.Template;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;

/** File copying isn't OS-specific, so this reuses the same NIO logic as the real provisioner. */
@Component
@Profile("dev")
public class FakeContainerFilesystemProvisioner implements ContainerFilesystemProvisioner {

    private final NspawnProperties properties;

    public FakeContainerFilesystemProvisioner(NspawnProperties properties) {
        this.properties = properties;
    }

    @Override
    public void cloneTemplate(Template template, String machineName, char[] sudoPasswordOverride) {
        // Dev mode keeps templates as plain directory stubs (no real tar.gz), unlike the real
        // implementation - see site/templates/README.md. Only the folder nesting (backend
        // subdirectory) mirrors production; the leaf stays a directory, not a tarball.
        Path source = Path.of(properties.templatesDir(), template.getBackend().templateSubdirectory(),
                template.getSourcePath());
        Path destination = Path.of(properties.machinesDir(), machineName);
        try {
            Files.createDirectories(destination);
            if (Files.exists(source)) {
                FileTreeCopier.copyRecursive(source, destination);
            }
        } catch (IOException e) {
            throw new ContainerCliException("Failed to clone template " + template.getName() + " to " + destination, e);
        }
    }

    @Override
    public void writeNspawnSettings(Container container, List<ContainerPortMapping> customMappings) {
        Path settingsFile = Path.of(properties.settingsDir(), container.getName() + ".nspawn");
        String content = NspawnSettingsRenderer.render(container, customMappings);
        try {
            Files.createDirectories(settingsFile.getParent());
            Files.writeString(settingsFile, content);
        } catch (IOException e) {
            throw new ContainerCliException("Failed to write .nspawn settings for " + container.getName(), e);
        }
    }

    @Override
    public void createMinimalTemplate(MinimalTemplateFlavor flavor, char[] sudoPasswordOverride) {
        // No real download in dev — site/templates/nspawn/<flavor>-minimal already exists as a
        // git-tracked stub tree (see site/templates/README.md), so this just makes sure some
        // directory is there, without the real implementation's "must not already exist" safety
        // check, which is specifically about not clobbering a real admin-prepared rootfs and
        // doesn't apply here.
        Path target = Path.of(properties.templatesDir(), ContainerBackend.SYSTEMD_NSPAWN.templateSubdirectory(),
                flavor.templateName());
        try {
            Files.createDirectories(target);
        } catch (IOException e) {
            throw new ContainerCliException("Failed to create " + flavor.templateName() + " template at " + target, e);
        }
    }

    @Override
    public void packMachineAsTemplate(String machineName, ContainerBackend backend, String templateName, char[] sudoPasswordOverride) {
        if (backend == ContainerBackend.QEMU) {
            // A QEMU "template" is a single stub file, not a directory tree - same stub convention
            // createQemuDisk's own fake below already uses.
            Path source = Path.of(properties.machinesDir(), "qemu-disks", machineName + ".qcow2.stub");
            Path target = Path.of(properties.templatesDir(), backend.templateSubdirectory(), templateName + ".qcow2.stub");
            try {
                Files.createDirectories(target.getParent());
                if (Files.exists(source)) {
                    Files.copy(source, target);
                }
            } catch (IOException e) {
                throw new ContainerCliException("Failed to pack " + machineName + " as template " + templateName, e);
            }
            return;
        }
        // Dev mode keeps templates as plain directory stubs (no real tar.gz), same convention
        // cloneTemplate above already follows - copies whatever's really sitting in the machine's
        // own dev-mode stub directory (itself only ever populated by cloneTemplate's own copy, so
        // this may well copy nothing if the machine was never actually "cloned" from a stub).
        Path source = Path.of(properties.machinesDir(), machineName);
        Path target = Path.of(properties.templatesDir(), backend.templateSubdirectory(), templateName);
        try {
            Files.createDirectories(target);
            if (Files.exists(source)) {
                FileTreeCopier.copyRecursive(source, target);
            }
        } catch (IOException e) {
            throw new ContainerCliException("Failed to pack " + machineName + " as template " + templateName, e);
        }
    }

    @Override
    public void cloneQemuTemplate(Template template, String containerName, char[] sudoPasswordOverride) {
        Path source = Path.of(properties.templatesDir(), template.getBackend().templateSubdirectory(),
                template.getSourcePath() + ".qcow2.stub");
        Path destination = Path.of(properties.machinesDir(), "qemu-disks", containerName + ".qcow2.stub");
        try {
            Files.createDirectories(destination.getParent());
            if (Files.exists(source)) {
                Files.copy(source, destination);
            } else {
                Files.writeString(destination, "fake qcow2 cloned from " + template.getName());
            }
        } catch (IOException e) {
            throw new ContainerCliException("Failed to clone QEMU template " + template.getName() + " to " + containerName, e);
        }
    }

    @Override
    public void pullPodmanTemplate(String pullReference, String templateName, char[] sudoPasswordOverride) {
        // No real podman/network path in dev mode - same "just make sure a stub directory exists"
        // posture as createMinimalTemplate above, proving out the caller's own wiring rather than
        // actually pulling anything.
        Path target = Path.of(properties.templatesDir(), ContainerBackend.PODMAN.templateSubdirectory(), templateName);
        try {
            Files.createDirectories(target);
        } catch (IOException e) {
            throw new ContainerCliException("Failed to pull '" + pullReference + "' as template " + templateName, e);
        }
    }

    @Override
    public void convertNspawnTemplateToPodman(String nspawnTemplateName, String newTemplateName, char[] sudoPasswordOverride) {
        // Same stub-copy posture as packMachineAsTemplate above - copies whatever's really sitting
        // in the source template's own dev-mode stub directory, if anything.
        Path source = Path.of(properties.templatesDir(), ContainerBackend.SYSTEMD_NSPAWN.templateSubdirectory(), nspawnTemplateName);
        Path target = Path.of(properties.templatesDir(), ContainerBackend.PODMAN.templateSubdirectory(), newTemplateName);
        try {
            Files.createDirectories(target);
            if (Files.exists(source)) {
                FileTreeCopier.copyRecursive(source, target);
            }
        } catch (IOException e) {
            throw new ContainerCliException("Failed to convert " + nspawnTemplateName + " to podman template " + newTemplateName, e);
        }
    }

    @Override
    public void convertPodmanTemplateToNspawn(String podmanTemplateName, String newTemplateName, char[] sudoPasswordOverride) {
        Path source = Path.of(properties.templatesDir(), ContainerBackend.PODMAN.templateSubdirectory(), podmanTemplateName);
        Path target = Path.of(properties.templatesDir(), ContainerBackend.SYSTEMD_NSPAWN.templateSubdirectory(), newTemplateName);
        try {
            Files.createDirectories(target);
            if (Files.exists(source)) {
                FileTreeCopier.copyRecursive(source, target);
            }
        } catch (IOException e) {
            throw new ContainerCliException("Failed to convert " + podmanTemplateName + " to nspawn template " + newTemplateName, e);
        }
    }

    @Override
    public void createPodmanContainer(String templateSourcePath, String containerName, String command, char[] sudoPasswordOverride) {
        // No real podman load/create path in dev mode - same "just make sure a stub directory
        // exists" posture as cloneTemplate/createMinimalTemplate above, copying whatever's really
        // sitting in the source podman template's own dev-mode stub directory, if anything. command
        // is unused here (nothing to actually run in dev mode) - this only needs to prove out the
        // caller's own wiring, not simulate a real podman create's command handling.
        Path source = Path.of(properties.templatesDir(), ContainerBackend.PODMAN.templateSubdirectory(), templateSourcePath);
        Path target = Path.of(properties.machinesDir(), containerName);
        try {
            Files.createDirectories(target);
            if (Files.exists(source)) {
                FileTreeCopier.copyRecursive(source, target);
            }
        } catch (IOException e) {
            throw new ContainerCliException("Failed to create podman container '" + containerName + "' from template " + templateSourcePath, e);
        }
    }

    @Override
    public List<DownloadedPackage> downloadPackagesIntoContainer(PackageManager packageManager, String machineName,
                                                                   List<String> packages, char[] sudoPasswordOverride, Duration timeout) {
        // timeout is unused (and no real apt/dnf/pacman/network path exists in dev mode at all) -
        // this only needs to prove out the caller's own wiring (RDP/VNC/desktop-manager container
        // creation still reaching RUNNING, plus the resulting packages being registered in the
        // admin package cache), not actually fetch anything. A plausible-looking result per
        // requested package is enough for that.
        String extension = switch (packageManager) {
            case DNF -> "-1.0.x86_64.rpm";
            case PACMAN -> "-1.0-1-x86_64.pkg.tar.zst";
            default -> "_1.0_amd64.deb";
        };
        return packages.stream()
                .map(pkg -> new DownloadedPackage(pkg + extension, 1024L))
                .toList();
    }

    @Override
    public List<String> missingDependenciesFor(PackageManager packageManager, String machineName,
                                                 String localPackageHostPath, char[] sudoPasswordOverride) {
        // No real apt/dnf/dependency-resolution path exists in dev mode - report "nothing missing"
        // so PackageCacheService.installOnContainer's caller still exercises the overall flow
        // (copy, simulate, skip download-and-register since the list is empty, run the install
        // command), matching downloadPackagesIntoContainer's own dev-mode posture of "prove the
        // wiring, don't simulate real package resolution".
        return List.of();
    }

    @Override
    public String mountPodmanContainer(String containerName) {
        // No real `podman mount` in dev mode - the same stub directory createPodmanContainer above
        // already writes into is returned directly, so Files-page browsing in dev mode sees exactly
        // what a fake pod creation actually produced.
        Path target = Path.of(properties.machinesDir(), containerName);
        try {
            Files.createDirectories(target);
        } catch (IOException e) {
            throw new ContainerCliException("Failed to mount podman container '" + containerName + "'", e);
        }
        return target.toString();
    }

    @Override
    public void createQemuDisk(String containerName, int diskSizeGb, char[] sudoPasswordOverride) {
        // No real qemu-img in dev mode - just prove the wiring by writing a marker file where the
        // real qcow2 would live (see writeQemuUnit's own stub for the matching unit-file marker).
        Path disk = Path.of(properties.machinesDir(), "qemu-disks", containerName + ".qcow2.stub");
        try {
            Files.createDirectories(disk.getParent());
            Files.writeString(disk, "fake qcow2, " + diskSizeGb + "GB");
        } catch (IOException e) {
            throw new ContainerCliException("Failed to create QEMU disk for '" + containerName + "'", e);
        }
    }

    @Override
    public void writeQemuUnit(Container container, String isoHostPath) {
        String containerName = container.getName();
        Path unit = Path.of(properties.machinesDir(), "qemu-units", containerName + ".service.stub");
        try {
            Files.createDirectories(unit.getParent());
            Files.writeString(unit, "fake unit, vncPort=" + container.getQemuVncPort()
                    + ", iso=" + (isoHostPath == null ? "(none)" : isoHostPath)
                    + ", cpuModel=" + container.getQemuCpuModel()
                    + ", cpuCount=" + container.getQemuCpuCount()
                    + ", memoryMb=" + container.getQemuMemoryMb()
                    + ", nicModel=" + container.getQemuNicModel());
        } catch (IOException e) {
            throw new ContainerCliException("Failed to write QEMU unit for '" + containerName + "'", e);
        }
    }

    @Override
    public List<String> listAvailableSourceFiles(ContainerBackend backend) {
        // Dev mode keeps templates as plain directory stubs, not .tar.gz files (see cloneTemplate
        // above and site/templates/README.md) - list those stub directory names directly rather
        // than looking for a .tar.gz extension that never exists here.
        Path dir = Path.of(properties.templatesDir(), backend.templateSubdirectory());
        if (!Files.isDirectory(dir)) {
            return List.of();
        }
        try (var stream = Files.list(dir)) {
            return stream.filter(Files::isDirectory).map(p -> p.getFileName().toString()).sorted().toList();
        } catch (IOException e) {
            return List.of();
        }
    }

    @Override
    public void deleteMachineFiles(String machineName) {
        Path machineDir = Path.of(properties.machinesDir(), machineName);
        Path settingsFile = Path.of(properties.settingsDir(), machineName + ".nspawn");
        try {
            FileTreeCopier.deleteRecursiveIfExists(machineDir);
            Files.deleteIfExists(settingsFile);
        } catch (IOException e) {
            throw new ContainerCliException("Failed to delete files for machine " + machineName, e);
        }
    }
}
