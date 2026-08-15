package com.nspawnmgr.domain;

/**
 * The one-click "Set up X-minimal" options on the Templates admin page — each downloads a real,
 * working minirootfs and bakes openssh-server into it, so a fresh install has something usable
 * immediately without needing an admin to prepare a template by hand first. Shown independently on
 * the admin page: setting one up doesn't hide the others — an admin can set up any or all three.
 *
 * <p>FEDORA/ARCH are unverified: no RPM/Fedora/RHEL or Arch host exists anywhere in this project's
 * test environment (unlike DEBIAN, which has been confirmed live multiple times) - built to each
 * distro's own documented bootstrap conventions as carefully as possible, but never exercised
 * against a real container. Arch in particular is the most speculative of the three: pacman's
 * cross-root install story (mirrorlist configuration, keyring population) is far less standardized
 * than DNF's --installroot, which itself is simpler and better-trodden than APT's --root (which
 * this host's own apt outright rejected - see nspawnmgr-create-debian-template.sh).
 */
public enum MinimalTemplateFlavor {
    DEBIAN("debian-minimal", "Minimal Debian base image (apt)", PackageManager.APT),
    FEDORA("fedora-minimal", "Minimal Fedora base image (dnf)", PackageManager.DNF),
    ARCH("arch-minimal", "Minimal Arch Linux base image (pacman)", PackageManager.PACMAN);

    private final String templateName;
    private final String description;
    private final PackageManager packageManager;

    MinimalTemplateFlavor(String templateName, String description, PackageManager packageManager) {
        this.templateName = templateName;
        this.description = description;
        this.packageManager = packageManager;
    }

    public String templateName() {
        return templateName;
    }

    public String description() {
        return description;
    }

    public PackageManager packageManager() {
        return packageManager;
    }
}
