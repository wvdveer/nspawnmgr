package com.nspawnmgr.service;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Golden-value coverage for the two riskiest multi-line entries in provisioning-commands.yml (the
 *  most transcription-error-prone ones, moved out of ProvisioningService's own Java string literals)
 *  - each expected value below was retyped independently from the pre-refactor source, not copied
 *  from the YAML file, so a real mismatch between the two would actually be caught. */
class ProvisioningCommandsTest {

    private final ProvisioningCommands commands = new ProvisioningCommands();

    @Test
    void resolvesAKnownCommand() {
        assertThat(commands.get("rdp.gnome.enable-gdm")).isEqualTo("systemctl enable --now gdm");
    }

    @Test
    void throwsForAnUnknownKey() {
        assertThatThrownBy(() -> commands.get("no.such.key"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("no.such.key");
    }

    @Test
    void vncX11XsessionDesktopFileMatchesThePreRefactorLiteral() {
        String expected = "cat > /usr/share/xsessions/nspawnmgr.desktop <<'NSPAWNMGR_XSESSION'\n"
                + "[Desktop Entry]\nName=nspawnmgr\nExec=%s\nType=Application\nDesktopNames=nspawnmgr\n"
                + "NSPAWNMGR_XSESSION\n"
                + "if [ -f /usr/lib/systemd/system/vncserver@.service ] || [ -f /lib/systemd/system/vncserver@.service ]; then "
                + "mkdir -p /etc/tigervnc && printf 'session=nspawnmgr\\n' > /etc/tigervnc/vncserver-config-mandatory; fi";
        assertThat(commands.get("vnc.x11.write-xsession-desktop-file")).isEqualTo(expected);
    }

    @Test
    void vncWaylandInstallUnitMatchesThePreRefactorLiteral() {
        String expected = "cat > /etc/systemd/system/%3$s <<'NSPAWNMGR_WAYLAND_VNC_UNIT'\n"
                + "[Unit]\n"
                + "Description=nspawnmgr Wayland VNC session (%4$s + wayvnc)\n"
                + "\n"
                + "[Service]\n"
                + "Type=simple\n"
                + "User=%1$s\n"
                + "PAMName=login\n"
                + "Environment=WLR_BACKENDS=headless\n"
                + "Environment=WLR_LIBINPUT_NO_DEVICES=1\n"
                + "Environment=WLR_HEADLESS_OUTPUTS=1\n"
                + "ExecStart=/bin/sh -c '%4$s & SOCK=; for i in $(seq 1 100); do SOCK=$(ls \"$XDG_RUNTIME_DIR\" 2>/dev/null | grep -E \"^wayland-[0-9]+$\" | head -1); [ -n \"$SOCK\" ] && [ -S \"$XDG_RUNTIME_DIR/$SOCK\" ] && break; sleep 0.1; done; WAYLAND_DISPLAY=\"$SOCK\" exec wayvnc 0.0.0.0 %2$d'\n"
                + "Restart=no\n"
                + "\n"
                + "[Install]\n"
                + "WantedBy=multi-user.target\n"
                + "NSPAWNMGR_WAYLAND_VNC_UNIT\n"
                + "systemctl daemon-reload && systemctl enable %3$s && systemctl restart %3$s";
        assertThat(commands.get("vnc.wayland.install-unit")).isEqualTo(expected);
    }

    @Test
    void podFixPamLoginuidMatchesThePreRefactorLiteral() {
        String expected = "[ -f /etc/pam.d/sshd ] && sed -i 's/^session\\s\\+required\\s\\+pam_loginuid.so/session optional pam_loginuid.so/' /etc/pam.d/sshd || true";
        assertThat(commands.get("pod.fix-pam-loginuid")).isEqualTo(expected);
    }
}
