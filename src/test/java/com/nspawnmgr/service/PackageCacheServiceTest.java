package com.nspawnmgr.service;

import com.nspawnmgr.cli.CommandResult;
import com.nspawnmgr.cli.ContainerCliExecutor;
import com.nspawnmgr.cli.ContainerFilesystemProvisioner;
import com.nspawnmgr.cli.DownloadedPackage;
import com.nspawnmgr.cli.PackageCacheFilesystem;
import com.nspawnmgr.domain.CachedPackage;
import com.nspawnmgr.domain.Container;
import com.nspawnmgr.domain.PackageManager;
import com.nspawnmgr.domain.Template;
import com.nspawnmgr.domain.User;
import com.nspawnmgr.repository.CachedPackageRepository;
import com.nspawnmgr.repository.ContainerRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class PackageCacheServiceTest {

    private CachedPackageRepository repository;
    private PackageCacheFilesystem filesystem;
    private ContainerCliExecutor cliExecutor;
    private ContainerFilesystemProvisioner filesystemProvisioner;
    private SettingsService settingsService;
    private ContainerRepository containerRepository;
    private PackageCacheService service;

    @BeforeEach
    void setUp() {
        repository = mock(CachedPackageRepository.class);
        filesystem = mock(PackageCacheFilesystem.class);
        cliExecutor = mock(ContainerCliExecutor.class);
        filesystemProvisioner = mock(ContainerFilesystemProvisioner.class);
        settingsService = mock(SettingsService.class);
        containerRepository = mock(ContainerRepository.class);
        when(settingsService.nspawnMachinesDir()).thenReturn("/var/lib/machines");
        when(filesystemProvisioner.missingDependenciesFor(any(), any(), any(), any())).thenReturn(List.of());
        service = new PackageCacheService(repository, filesystem, cliExecutor, filesystemProvisioner,
                settingsService, containerRepository);
        when(repository.save(any())).thenAnswer(invocation -> invocation.getArgument(0));
    }

    private User user() {
        User user = new User("ext-id");
        user.setId(1L);
        return user;
    }

    /** Also stubs containerRepository.findByIdWithTemplate(id) to return itself, matching the
     *  fresh-refetch installOnContainer does within its own transaction. */
    private Container containerWithPackageManager(PackageManager pm) {
        Template template = new Template();
        template.setPackageManager(pm);
        Container container = new Container();
        container.setId(9L);
        container.setName("my-container");
        container.setTemplate(template);
        when(containerRepository.findByIdWithTemplate(9L)).thenReturn(Optional.of(container));
        return container;
    }

    @Test
    void uploadSanitizesFilenameAndPersists() {
        CachedPackage saved = service.upload(PackageManager.APT, "../../etc/passwd.deb",
                new ByteArrayInputStream("content".getBytes()), "content".getBytes().length, "a description", user());

        assertThat(saved.getOriginalFilename()).isEqualTo("../../etc/passwd.deb");
        assertThat(saved.getStoredFilename()).endsWith("_passwd.deb");
        assertThat(saved.getStoredFilename()).doesNotContain("/");
        assertThat(saved.getSizeBytes()).isEqualTo("content".getBytes().length);
        verify(filesystem).upload(anyString(), any(InputStream.class));
    }

    @Test
    void installOnContainerRejectsPackageManagerMismatch() {
        CachedPackage cachedPackage = new CachedPackage(PackageManager.DNF, "foo.rpm", "1_foo.rpm", null, user(), 10);
        when(repository.findById(5L)).thenReturn(Optional.of(cachedPackage));
        Container container = containerWithPackageManager(PackageManager.APT);

        assertThatThrownBy(() -> service.installOnContainer(container, 5L, null))
                .isInstanceOf(IllegalArgumentException.class);

        verify(filesystem, never()).copyIntoContainer(any(), any());
        verify(cliExecutor, never()).runInMachine(any(), any(), any(), any(), any());
    }

    @Test
    void installOnContainerCopiesAndRunsLocalInstallCommand() {
        CachedPackage cachedPackage = new CachedPackage(PackageManager.APT, "foo.deb", "1_foo.deb", null, user(), 10);
        when(repository.findById(5L)).thenReturn(Optional.of(cachedPackage));
        Container container = containerWithPackageManager(PackageManager.APT);
        when(cliExecutor.runInMachine(eq("my-container"), any(), any(), any(), any()))
                .thenReturn(new CommandResult(0, "installed", ""));

        CommandResult result = service.installOnContainer(container, 5L, null);

        assertThat(result.exitCode()).isEqualTo(0);
        verify(filesystem).copyIntoContainer(anyString(), anyString());
        verify(cliExecutor).runInMachine(eq("my-container"), any(),
                eq(List.of("sh", "-c", "DEBIAN_FRONTEND=noninteractive apt-get install -y /root/nspawnmgr-packages/1_foo.deb")),
                any(), any());
    }

    @Test
    void installOnContainerSkipsDependencyPreFetchWhenNothingIsMissing() {
        CachedPackage cachedPackage = new CachedPackage(PackageManager.APT, "foo.deb", "1_foo.deb", null, user(), 10);
        when(repository.findById(5L)).thenReturn(Optional.of(cachedPackage));
        Container container = containerWithPackageManager(PackageManager.APT);
        when(cliExecutor.runInMachine(any(), any(), any(), any(), any())).thenReturn(new CommandResult(0, "installed", ""));

        service.installOnContainer(container, 5L, null);

        verify(filesystemProvisioner).missingDependenciesFor(eq(PackageManager.APT), eq("my-container"), anyString(), any());
        verify(filesystemProvisioner, never()).downloadPackagesIntoContainer(any(), any(), any(), any());
    }

    @Test
    void installOnContainerPreFetchesAndRegistersMissingDependencies() {
        User uploader = user();
        CachedPackage cachedPackage = new CachedPackage(PackageManager.APT, "foo.deb", "1_foo.deb", null, uploader, 10);
        when(repository.findById(5L)).thenReturn(Optional.of(cachedPackage));
        Container container = containerWithPackageManager(PackageManager.APT);
        when(filesystemProvisioner.missingDependenciesFor(eq(PackageManager.APT), eq("my-container"), anyString(), any()))
                .thenReturn(List.of("libfoo1", "libbar2"));
        when(filesystemProvisioner.downloadPackagesIntoContainer(eq(PackageManager.APT), eq("my-container"), eq(List.of("libfoo1", "libbar2")), any()))
                .thenReturn(List.of(new DownloadedPackage("libfoo1_1.0_amd64.deb", 100L),
                        new DownloadedPackage("libbar2_2.0_amd64.deb", 200L)));
        when(cliExecutor.runInMachine(any(), any(), any(), any(), any())).thenReturn(new CommandResult(0, "installed", ""));

        service.installOnContainer(container, 5L, null);

        verify(repository).existsByPackageManagerAndStoredFilename(PackageManager.APT, "auto-libfoo1_1.0_amd64.deb");
        verify(repository).existsByPackageManagerAndStoredFilename(PackageManager.APT, "auto-libbar2_2.0_amd64.deb");
    }

    @Test
    void installOnContainerAlsoPreFetchesAndRegistersMissingDnfDependencies() {
        User uploader = user();
        CachedPackage cachedPackage = new CachedPackage(PackageManager.DNF, "foo.rpm", "1_foo.rpm", null, uploader, 10);
        when(repository.findById(5L)).thenReturn(Optional.of(cachedPackage));
        Container container = containerWithPackageManager(PackageManager.DNF);
        when(filesystemProvisioner.missingDependenciesFor(eq(PackageManager.DNF), eq("my-container"), anyString(), any()))
                .thenReturn(List.of("libfoo-devel"));
        when(filesystemProvisioner.downloadPackagesIntoContainer(eq(PackageManager.DNF), eq("my-container"), eq(List.of("libfoo-devel")), any()))
                .thenReturn(List.of(new DownloadedPackage("libfoo-devel-1.0.x86_64.rpm", 300L)));
        when(cliExecutor.runInMachine(any(), any(), any(), any(), any())).thenReturn(new CommandResult(0, "installed", ""));

        service.installOnContainer(container, 5L, null);

        verify(repository).existsByPackageManagerAndStoredFilename(PackageManager.DNF, "auto-libfoo-devel-1.0.x86_64.rpm");
        verify(cliExecutor).runInMachine(eq("my-container"), any(),
                eq(List.of("sh", "-c", "dnf install -y /root/nspawnmgr-packages/1_foo.rpm $(ls /var/cache/dnf/*.rpm 2>/dev/null)")), any(), any());
    }

    @Test
    void installOnContainerNeverSimulatesForApk() {
        CachedPackage cachedPackage = new CachedPackage(PackageManager.APK, "foo.apk", "1_foo.apk", null, user(), 10);
        when(repository.findById(5L)).thenReturn(Optional.of(cachedPackage));
        Container container = containerWithPackageManager(PackageManager.APK);
        when(cliExecutor.runInMachine(any(), any(), any(), any(), any())).thenReturn(new CommandResult(0, "installed", ""));

        service.installOnContainer(container, 5L, null);

        verify(filesystemProvisioner, never()).missingDependenciesFor(any(), any(), any(), any());
        verify(filesystemProvisioner, never()).downloadPackagesIntoContainer(any(), any(), any(), any());
    }

    @Test
    void installOnContainerAlsoPreFetchesAndRegistersMissingPacmanDependencies() {
        User uploader = user();
        CachedPackage cachedPackage = new CachedPackage(PackageManager.PACMAN, "foo.pkg.tar.zst", "1_foo.pkg.tar.zst", null, uploader, 10);
        when(repository.findById(5L)).thenReturn(Optional.of(cachedPackage));
        Container container = containerWithPackageManager(PackageManager.PACMAN);
        when(filesystemProvisioner.missingDependenciesFor(eq(PackageManager.PACMAN), eq("my-container"), anyString(), any()))
                .thenReturn(List.of("libfoo"));
        when(filesystemProvisioner.downloadPackagesIntoContainer(eq(PackageManager.PACMAN), eq("my-container"), eq(List.of("libfoo")), any()))
                .thenReturn(List.of(new DownloadedPackage("libfoo-1.0-1-x86_64.pkg.tar.zst", 400L)));
        when(cliExecutor.runInMachine(any(), any(), any(), any(), any())).thenReturn(new CommandResult(0, "installed", ""));

        service.installOnContainer(container, 5L, null);

        verify(repository).existsByPackageManagerAndStoredFilename(PackageManager.PACMAN, "auto-libfoo-1.0-1-x86_64.pkg.tar.zst");
        verify(cliExecutor).runInMachine(eq("my-container"), any(),
                eq(List.of("sh", "-c", "pacman -U --noconfirm /root/nspawnmgr-packages/1_foo.pkg.tar.zst")), any(), any());
    }

    @Test
    void registerAutoFetchedIfAbsentInsertsRowWithoutTouchingFilesystem() {
        User owner = user();

        service.registerAutoFetchedIfAbsent(PackageManager.APT, "xfce4_4.18-2_amd64.deb", 12345L, owner);

        verify(repository).existsByPackageManagerAndStoredFilename(PackageManager.APT, "auto-xfce4_4.18-2_amd64.deb");
        verify(repository).save(any());
        verify(filesystem, never()).upload(any(), any());
    }

    @Test
    void registerAutoFetchedIfAbsentIsIdempotent() {
        when(repository.existsByPackageManagerAndStoredFilename(PackageManager.APT, "auto-xfce4_4.18-2_amd64.deb"))
                .thenReturn(true);

        service.registerAutoFetchedIfAbsent(PackageManager.APT, "xfce4_4.18-2_amd64.deb", 12345L, user());

        verify(repository, never()).save(any());
    }

    @Test
    void deleteRemovesRowAndFile() {
        CachedPackage cachedPackage = new CachedPackage(PackageManager.APT, "foo.deb", "1_foo.deb", null, user(), 10);
        when(repository.findById(5L)).thenReturn(Optional.of(cachedPackage));

        service.delete(5L);

        verify(filesystem).delete(anyString());
        verify(repository).delete(cachedPackage);
    }

    @Test
    void deleteRejectsWhileMountedAsIso() {
        CachedPackage cachedPackage = new CachedPackage(PackageManager.ISO, "image.iso", "1_image.iso", null, user(), 10);
        when(repository.findById(5L)).thenReturn(Optional.of(cachedPackage));
        when(containerRepository.existsByMountedIso_Id(5L)).thenReturn(true);

        assertThatThrownBy(() -> service.delete(5L)).isInstanceOf(IllegalStateException.class);

        verify(filesystem, never()).delete(any());
        verify(repository, never()).delete(any());
    }

    @Test
    void hostPathUsesUploadedCacheConvention() {
        CachedPackage cachedPackage = new CachedPackage(PackageManager.ISO, "image.iso", "1_image.iso", null, user(), 10);

        assertThat(service.hostPath(cachedPackage)).isEqualTo("/var/cache/nspawnmgr/packages/iso/uploaded/1_image.iso");
    }

    @Test
    void listAutoFetchedDelegatesToFilesystemForApt() {
        when(filesystem.listFiles("/var/cache/nspawnmgr/packages/apt/auto"))
                .thenReturn(List.of(new DownloadedPackage("libfoo1_1.0_amd64.deb", 100L)));

        assertThat(service.listAutoFetched(PackageManager.APT))
                .containsExactly(new DownloadedPackage("libfoo1_1.0_amd64.deb", 100L));
    }

    @Test
    void listAutoFetchedDelegatesToFilesystemForDnfAndPacman() {
        when(filesystem.listFiles("/var/cache/nspawnmgr/packages/dnf/auto"))
                .thenReturn(List.of(new DownloadedPackage("libfoo-devel-1.0.x86_64.rpm", 200L)));
        when(filesystem.listFiles("/var/cache/nspawnmgr/packages/pacman/auto"))
                .thenReturn(List.of(new DownloadedPackage("libfoo-1.0-1-x86_64.pkg.tar.zst", 300L)));

        assertThat(service.listAutoFetched(PackageManager.DNF)).hasSize(1);
        assertThat(service.listAutoFetched(PackageManager.PACMAN)).hasSize(1);
    }

    @Test
    void listAutoFetchedIsEmptyForApkAndIsoWithoutTouchingFilesystem() {
        assertThat(service.listAutoFetched(PackageManager.APK)).isEmpty();
        assertThat(service.listAutoFetched(PackageManager.ISO)).isEmpty();

        verify(filesystem, never()).listFiles(any());
    }
}
