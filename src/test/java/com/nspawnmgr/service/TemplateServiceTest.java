package com.nspawnmgr.service;

import com.nspawnmgr.cli.ContainerFilesystemProvisioner;
import com.nspawnmgr.domain.Container;
import com.nspawnmgr.domain.ContainerBackend;
import com.nspawnmgr.domain.DesktopManager;
import com.nspawnmgr.domain.MinimalTemplateFlavor;
import com.nspawnmgr.domain.PackageManager;
import com.nspawnmgr.domain.Template;
import com.nspawnmgr.repository.ContainerRepository;
import com.nspawnmgr.repository.TemplateRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class TemplateServiceTest {

    private TemplateRepository templateRepository;
    private ContainerRepository containerRepository;
    private ContainerFilesystemProvisioner filesystemProvisioner;
    private SettingsService settingsService;
    private TemplateService service;

    @BeforeEach
    void setUp() {
        templateRepository = mock(TemplateRepository.class);
        containerRepository = mock(ContainerRepository.class);
        filesystemProvisioner = mock(ContainerFilesystemProvisioner.class);
        settingsService = mock(SettingsService.class);
        service = new TemplateService(templateRepository, containerRepository,
                filesystemProvisioner, settingsService);
        when(templateRepository.save(any())).thenAnswer(invocation -> invocation.getArgument(0));
    }

    private Template template(PackageManager packageManager) {
        Template template = new Template();
        template.setBackend(ContainerBackend.SYSTEMD_NSPAWN);
        template.setPackageManager(packageManager);
        return template;
    }

    @Test
    void resolveInstallDesktopCommandReturnsNullForNone() {
        assertThat(service.resolveInstallDesktopCommand(template(PackageManager.APT), DesktopManager.NONE)).isNull();
    }

    @Test
    void resolveInstallDesktopCommandReturnsDefaultPerPackageManager() {
        assertThat(service.resolveInstallDesktopCommand(template(PackageManager.APT), DesktopManager.GNOME))
                .contains("task-gnome-desktop");
        assertThat(service.resolveInstallDesktopCommand(template(PackageManager.APT), DesktopManager.KDE_STANDARD))
                .contains("kde-standard");
        assertThat(service.resolveInstallDesktopCommand(template(PackageManager.APT), DesktopManager.XFCE4))
                .contains("xfce4");
    }

    @Test
    void resolveInstallVncCommandPrefersTemplateOverride() {
        Template template = template(PackageManager.APT);
        template.setInstallVncCommand("custom-vnc-install");

        assertThat(service.resolveInstallVncCommand(template)).isEqualTo("custom-vnc-install");
    }

    @Test
    void resolveInstallVncCommandFallsBackToDefaultWhenNoOverride() {
        assertThat(service.resolveInstallVncCommand(template(PackageManager.APT)))
                .contains("tigervnc-standalone-server");
    }

    @Test
    void defaultAptInstallCommandsNeverRunAptGetUpdate() {
        // Confirmed live ("b3" stuck in ERROR, "Command timed out" running exactly this): apt-get
        // update always hits the network from inside the container regardless of what's already
        // cached, which the host-side pre-download step already made unnecessary - see
        // *PackagesToPreDownload.
        assertThat(service.resolveInstallSshCommand(template(PackageManager.APT))).doesNotContain("apt-get update");
        assertThat(service.resolveInstallXrdpCommand(template(PackageManager.APT))).doesNotContain("apt-get update");
        assertThat(service.resolveInstallVncCommand(template(PackageManager.APT))).doesNotContain("apt-get update");
        assertThat(service.resolveInstallDesktopCommand(template(PackageManager.APT), DesktopManager.XFCE4))
                .doesNotContain("apt-get update");
    }

    @Test
    void sshPackagesToPreDownloadEmptyForApk() {
        // APK is the one package manager genuinely excluded from pre-fetch entirely (see
        // PackageManager#forDependencyPreFetch) - its own local install already resolves
        // dependencies on its own.
        assertThat(service.sshPackagesToPreDownload(template(PackageManager.APK))).isEmpty();
    }

    @Test
    void sshPackagesToPreDownloadEmptyWhenOverridden() {
        Template template = template(PackageManager.APT);
        template.setInstallSshCommand("custom-ssh-install");
        assertThat(service.sshPackagesToPreDownload(template)).isEmpty();
    }

    @Test
    void sshPackagesToPreDownloadReturnsPackageForApt() {
        assertThat(service.sshPackagesToPreDownload(template(PackageManager.APT))).containsExactly("openssh-server");
    }

    @Test
    void sshPackagesToPreDownloadReturnsPackageForDnf() {
        assertThat(service.sshPackagesToPreDownload(template(PackageManager.DNF))).containsExactly("openssh-server");
    }

    @Test
    void sshPackagesToPreDownloadReturnsPackageForPacman() {
        // Arch doesn't split client/server the way Debian/Fedora do - the package is "openssh", not
        // "openssh-server" (matches DEFAULT_INSTALL_SSH's own pacman command).
        assertThat(service.sshPackagesToPreDownload(template(PackageManager.PACMAN))).containsExactly("openssh");
    }

    @Test
    void xrdpPackagesToPreDownloadEmptyForApk() {
        assertThat(service.xrdpPackagesToPreDownload(template(PackageManager.APK))).isEmpty();
    }

    @Test
    void xrdpPackagesToPreDownloadEmptyWhenOverridden() {
        Template template = template(PackageManager.APT);
        template.setInstallXrdpCommand("custom-xrdp-install");
        assertThat(service.xrdpPackagesToPreDownload(template)).isEmpty();
    }

    @Test
    void xrdpPackagesToPreDownloadReturnsPackageForApt() {
        assertThat(service.xrdpPackagesToPreDownload(template(PackageManager.APT))).containsExactly("xrdp");
    }

    @Test
    void xrdpPackagesToPreDownloadReturnsPackageForDnf() {
        // .x86_64 pinned - confirmed live, a bare "xrdp" pulls in a conflicting i686 openh264
        // dependency chain (Fedora publishes both xrdp.x86_64 and xrdp.i686). xorg-x11-server-Xorg
        // and xorgxrdp added 2026-08-14 - confirmed live neither was ever installed at all, so
        // every RDP login failed with "X server could not be started" regardless of PAM fixes.
        assertThat(service.xrdpPackagesToPreDownload(template(PackageManager.DNF)))
                .containsExactly("xrdp.x86_64", "xorg-x11-server-Xorg.x86_64", "xorgxrdp.x86_64");
    }

    @Test
    void xrdpPackagesToPreDownloadReturnsPackagesForPacman() {
        // Matches DEFAULT_INSTALL_XRDP's own pacman command - needs xorgxrdp alongside xrdp itself.
        assertThat(service.xrdpPackagesToPreDownload(template(PackageManager.PACMAN))).containsExactly("xorgxrdp", "xrdp");
    }

    @Test
    void vncPackagesToPreDownloadEmptyForApk() {
        assertThat(service.vncPackagesToPreDownload(template(PackageManager.APK))).isEmpty();
    }

    @Test
    void vncPackagesToPreDownloadEmptyWhenOverridden() {
        Template template = template(PackageManager.APT);
        template.setInstallVncCommand("custom-vnc-install");
        assertThat(service.vncPackagesToPreDownload(template)).isEmpty();
    }

    @Test
    void vncPackagesToPreDownloadReturnsPackageForApt() {
        assertThat(service.vncPackagesToPreDownload(template(PackageManager.APT))).containsExactly("tigervnc-standalone-server");
    }

    @Test
    void vncPackagesToPreDownloadReturnsPackageForDnf() {
        // Matches DEFAULT_INSTALL_VNC's own dnf command - a different package name than APT's.
        assertThat(service.vncPackagesToPreDownload(template(PackageManager.DNF))).containsExactly("tigervnc-x11-server");
    }

    @Test
    void vncPackagesToPreDownloadReturnsPackageForPacman() {
        assertThat(service.vncPackagesToPreDownload(template(PackageManager.PACMAN))).containsExactly("tigervnc", "xterm");
    }

    @Test
    void desktopPackagesToPreDownloadEmptyForNone() {
        assertThat(service.desktopPackagesToPreDownload(template(PackageManager.APT), DesktopManager.NONE)).isEmpty();
    }

    @Test
    void desktopPackagesToPreDownloadEmptyForApk() {
        assertThat(service.desktopPackagesToPreDownload(template(PackageManager.APK), DesktopManager.XFCE4)).isEmpty();
    }

    @Test
    void desktopPackagesToPreDownloadEmptyForGnomeOnDnf() {
        // GNOME/KDE's DNF install command is a comps *group* install, not a plain named-package
        // one - `dnf --downloadonly` (what pre-fetch uses) can't resolve/cache a whole group's
        // membership ahead of time, so this deliberately stays empty (falls through to the direct
        // in-container group install) even though DNF is otherwise pre-fetch-eligible now.
        assertThat(service.desktopPackagesToPreDownload(template(PackageManager.DNF), DesktopManager.GNOME)).isEmpty();
    }

    @Test
    void desktopPackagesToPreDownloadReturnsPackageForApt() {
        assertThat(service.desktopPackagesToPreDownload(template(PackageManager.APT), DesktopManager.XFCE4))
                .containsExactly("xfce4");
    }

    @Test
    void desktopPackagesToPreDownloadReturnsPackageForXfce4OnDnf() {
        // Unlike GNOME/KDE, Xfce is a set of plain named DNF packages (not a comps group) - confirmed
        // live, "Xfce Desktop" isn't a comps group on current Fedora at all, and there's no "xfce4"
        // metapackage either (unlike Debian/Arch). "xfce4-session" alone installs but pulls in no
        // window manager/desktop/panel - confirmed live 2026-08-12 (a real container stuck on a
        // permanent black VNC/RDP screen despite guacd, Xvnc, and the session all reporting success) -
        // and, confirmed live 2026-08-14, no terminal emulator either ("Failed to launch preferred
        // application for category TerminalEmulator") - so all five packages needed for an
        // actually-usable session must be named explicitly.
        assertThat(service.desktopPackagesToPreDownload(template(PackageManager.DNF), DesktopManager.XFCE4))
                .containsExactly("xfce4-session", "xfwm4", "xfdesktop", "xfce4-panel", "xfce4-terminal");
    }

    @Test
    void vncPackagesToPreDownloadPrefersExplicitPackageListOverride() {
        // The actual scenario this override mechanism exists for: a distro renames a package
        // (Fedora's tigervnc-server -> tigervnc-x11-server) and an admin fixes it via configuration.
        Template template = template(PackageManager.DNF);
        template.setVncPreDownloadPackages("tigervnc-x11-server");

        assertThat(service.vncPackagesToPreDownload(template)).containsExactly("tigervnc-x11-server");
    }

    @Test
    void vncPackagesToPreDownloadTrimsAndSkipsBlankEntriesInOverride() {
        Template template = template(PackageManager.DNF);
        template.setVncPreDownloadPackages(" foo , , bar ");

        assertThat(service.vncPackagesToPreDownload(template)).containsExactly("foo", "bar");
    }

    @Test
    void vncPackagesToPreDownloadFallsBackToDefaultWhenOverrideIsBlank() {
        Template template = template(PackageManager.DNF);
        template.setVncPreDownloadPackages("   ");

        assertThat(service.vncPackagesToPreDownload(template)).containsExactly("tigervnc-x11-server");
    }

    @Test
    void vncPackagesToPreDownloadPackageListOverrideWinsEvenWithCommandOverrideToo() {
        // A package-list override should be honored even when the install command is ALSO
        // overridden - only a command-only override (no explicit package list) skips pre-fetch.
        Template template = template(PackageManager.DNF);
        template.setInstallVncCommand("custom-vnc-install");
        template.setVncPreDownloadPackages("tigervnc-x11-server");

        assertThat(service.vncPackagesToPreDownload(template)).containsExactly("tigervnc-x11-server");
    }

    @Test
    void resolveInstallDesktopCommandPrefersTemplateOverride() {
        Template template = template(PackageManager.APT);
        template.setInstallGnomeCommand("custom-gnome-install");

        assertThat(service.resolveInstallDesktopCommand(template, DesktopManager.GNOME)).isEqualTo("custom-gnome-install");
        // Other desktop managers on the same template stay on the default - overrides are per-manager.
        assertThat(service.resolveInstallDesktopCommand(template, DesktopManager.XFCE4)).contains("xfce4");
    }

    @Test
    void desktopPackagesToPreDownloadPrefersTemplateOverride() {
        Template template = template(PackageManager.DNF);
        template.setGnomePreDownloadPackages("gnome-desktop-override");

        // Without the override this would be empty (GNOME/DNF is a comps-group install, see
        // desktopPackagesToPreDownloadEmptyForGnomeOnDnf) - the explicit override still applies.
        assertThat(service.desktopPackagesToPreDownload(template, DesktopManager.GNOME))
                .containsExactly("gnome-desktop-override");
    }

    @Test
    void createMinimalDefaultRejectsWhenThatFlavorAlreadyExists() {
        Template existing = template(PackageManager.APT);
        existing.setName("debian-minimal");
        when(templateRepository.findByName("debian-minimal")).thenReturn(Optional.of(existing));

        assertThatThrownBy(() -> service.createMinimalDefault(MinimalTemplateFlavor.DEBIAN, "pw".toCharArray()))
                .isInstanceOf(IllegalStateException.class);

        verify(filesystemProvisioner, never()).createMinimalTemplate(any(), any());
    }

    @Test
    void createMinimalDefaultDoesNotRequireOtherFlavorsToBeAbsent() {
        // Independent per-flavor gating: fedora-minimal already existing must not block setting up
        // debian-minimal too - confirmed live (via mvn test) this replaced the old "only when zero
        // templates exist at all" gate.
        Template fedoraTemplate = template(PackageManager.DNF);
        fedoraTemplate.setName("fedora-minimal");
        when(templateRepository.findByName("fedora-minimal")).thenReturn(Optional.of(fedoraTemplate));
        when(templateRepository.findByName("debian-minimal")).thenReturn(Optional.empty());
        when(settingsService.sshApprovalRequired()).thenReturn(false);

        Template created = service.createMinimalDefault(MinimalTemplateFlavor.DEBIAN, null);

        assertThat(created.getName()).isEqualTo("debian-minimal");
        assertThat(created.getPackageManager()).isEqualTo(PackageManager.APT);
        verify(filesystemProvisioner).createMinimalTemplate(MinimalTemplateFlavor.DEBIAN, null);
    }

    @Test
    void createMinimalDefaultRequiresSudoPasswordInApprovalMode() {
        when(templateRepository.findByName("arch-minimal")).thenReturn(Optional.empty());
        when(settingsService.sshApprovalRequired()).thenReturn(true);

        assertThatThrownBy(() -> service.createMinimalDefault(MinimalTemplateFlavor.ARCH, null))
                .isInstanceOf(IllegalArgumentException.class);

        verify(filesystemProvisioner, never()).createMinimalTemplate(any(), any());
    }

    @Test
    void createMinimalDefaultUsesEachFlavorsOwnNameAndPackageManager() {
        when(templateRepository.findByName(any())).thenReturn(Optional.empty());
        when(settingsService.sshApprovalRequired()).thenReturn(false);

        Template fedora = service.createMinimalDefault(MinimalTemplateFlavor.FEDORA, null);
        Template arch = service.createMinimalDefault(MinimalTemplateFlavor.ARCH, null);

        assertThat(fedora.getName()).isEqualTo("fedora-minimal");
        assertThat(fedora.getPackageManager()).isEqualTo(PackageManager.DNF);
        assertThat(arch.getName()).isEqualTo("arch-minimal");
        assertThat(arch.getPackageManager()).isEqualTo(PackageManager.PACMAN);
    }

    @Test
    void createMinimalDefaultSetsIdentityPrivateUsersModeOnlyForFedora() {
        // Confirmed live (yoga/fed1): PICK's idmapped root-fs mount breaks unix_chkpwd's
        // /etc/shadow read - fedora-minimal needs IDENTITY, Debian/Arch stay on the host's own
        // systemd default (null) until/unless they hit the same issue.
        when(templateRepository.findByName(any())).thenReturn(Optional.empty());
        when(settingsService.sshApprovalRequired()).thenReturn(false);

        Template fedora = service.createMinimalDefault(MinimalTemplateFlavor.FEDORA, null);
        Template debian = service.createMinimalDefault(MinimalTemplateFlavor.DEBIAN, null);
        Template arch = service.createMinimalDefault(MinimalTemplateFlavor.ARCH, null);

        assertThat(fedora.getPrivateUsersMode()).isEqualTo(com.nspawnmgr.domain.PrivateUsersMode.IDENTITY);
        assertThat(debian.getPrivateUsersMode()).isNull();
        assertThat(arch.getPrivateUsersMode()).isNull();
    }

    @Test
    void listAvailableSourceFilesDelegatesToFilesystemProvisioner() {
        when(filesystemProvisioner.listAvailableSourceFiles(ContainerBackend.SYSTEMD_NSPAWN))
                .thenReturn(List.of("debian-minimal", "my-custom-template"));

        assertThat(service.listAvailableSourceFiles(ContainerBackend.SYSTEMD_NSPAWN))
                .containsExactly("debian-minimal", "my-custom-template");
    }

    /** Also stubs containerRepository.findByIdWithTemplate(id), matching the fresh-refetch
     *  createFromMachine does within its own call (open-in-view is off - see that method's own
     *  comment on why container.getTemplate() can't just be trusted directly). */
    private Container containerFrom(Template originatingTemplate) {
        Container container = new Container();
        container.setId(9L);
        container.setName("b1");
        container.setTemplate(originatingTemplate);
        when(containerRepository.findByIdWithTemplate(9L)).thenReturn(Optional.of(container));
        return container;
    }

    @Test
    void createFromMachinePacksAndRegistersInheritingTheOriginatingTemplatesTraits() {
        Template originating = template(PackageManager.APT);
        originating.setRdpCapable(true);
        originating.setVncCapable(true);
        originating.setPrivateUsersMode(com.nspawnmgr.domain.PrivateUsersMode.IDENTITY);
        Container container = containerFrom(originating);
        when(templateRepository.findByName("snapshot-1")).thenReturn(Optional.empty());
        when(settingsService.sshApprovalRequired()).thenReturn(false);

        Template result = service.createFromMachine("snapshot-1", "a snapshot", container, null);

        verify(filesystemProvisioner).packMachineAsTemplate("b1", ContainerBackend.SYSTEMD_NSPAWN, "snapshot-1", null);
        assertThat(result.getName()).isEqualTo("snapshot-1");
        assertThat(result.getPackageManager()).isEqualTo(PackageManager.APT);
        assertThat(result.isRdpCapable()).isTrue();
        assertThat(result.isVncCapable()).isTrue();
        assertThat(result.getPrivateUsersMode()).isEqualTo(com.nspawnmgr.domain.PrivateUsersMode.IDENTITY);
    }

    @Test
    void createFromMachineRejectsAnAlreadyTakenName() {
        Template existing = template(PackageManager.APT);
        existing.setName("snapshot-1");
        when(templateRepository.findByName("snapshot-1")).thenReturn(Optional.of(existing));
        Container container = containerFrom(template(PackageManager.APT));

        assertThatThrownBy(() -> service.createFromMachine("snapshot-1", null, container, null))
                .isInstanceOf(IllegalArgumentException.class);

        verify(filesystemProvisioner, never()).packMachineAsTemplate(any(), any(), any(), any());
    }

    @Test
    void createFromMachineRequiresSudoPasswordInApprovalMode() {
        when(templateRepository.findByName("snapshot-1")).thenReturn(Optional.empty());
        when(settingsService.sshApprovalRequired()).thenReturn(true);
        Container container = containerFrom(template(PackageManager.APT));

        assertThatThrownBy(() -> service.createFromMachine("snapshot-1", null, container, null))
                .isInstanceOf(IllegalArgumentException.class);

        verify(filesystemProvisioner, never()).packMachineAsTemplate(any(), any(), any(), any());
    }
}
