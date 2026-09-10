package com.nspawnmgr.cli;

import com.nspawnmgr.domain.Container;
import com.nspawnmgr.domain.PrivateUsersMode;
import com.nspawnmgr.domain.Template;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class NspawnSettingsRendererTest {

    private Container containerWithTemplate(PrivateUsersMode privateUsersMode) {
        Template template = new Template();
        template.setPrivateUsersMode(privateUsersMode);
        Container container = new Container();
        container.setTemplate(template);
        return container;
    }

    @Test
    void omitsExecSectionWhenTemplateHasNoPrivateUsersModeOverride() {
        // Null means "follow the host's own systemd-nspawn default" - see PrivateUsersMode's own
        // javadoc for why this isn't forced to IDENTITY blanket-wide.
        String content = NspawnSettingsRenderer.render(containerWithTemplate(null), List.of());

        assertThat(content).doesNotContain("[Exec]").doesNotContain("PrivateUsers");
        assertThat(content).startsWith("[Network]\nBridge=nspawnbr0\n");
    }

    @Test
    void rendersExecSectionWhenTemplateOverridesPrivateUsersMode() {
        String content = NspawnSettingsRenderer.render(containerWithTemplate(PrivateUsersMode.IDENTITY), List.of());

        assertThat(content).startsWith("[Exec]\nPrivateUsers=identity\n[Network]\nBridge=nspawnbr0\n");
    }

    @Test
    void rendersCapNetAdminAndTunBindWhenVpnCapable() {
        Container container = containerWithTemplate(null);
        container.setVpnCapable(true);

        String content = NspawnSettingsRenderer.render(container, List.of());

        assertThat(content).contains("[Exec]\nCapability=CAP_NET_ADMIN\n");
        assertThat(content).contains("[Files]\nBind=/dev/net/tun\n");
    }

    @Test
    void omitsCapNetAdminAndTunBindWhenNotVpnCapable() {
        String content = NspawnSettingsRenderer.render(containerWithTemplate(null), List.of());

        assertThat(content).doesNotContain("CAP_NET_ADMIN").doesNotContain("/dev/net/tun");
    }
}
