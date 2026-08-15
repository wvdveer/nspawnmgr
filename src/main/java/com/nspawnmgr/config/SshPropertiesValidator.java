package com.nspawnmgr.config;

import org.springframework.beans.factory.InitializingBean;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

/**
 * Fails startup fast if neither a password nor a private key is configured for the sudo-capable
 * SSH account — that combination can authenticate the SSH transport with nothing at all, which
 * would otherwise surface only as a confusing connection failure on the first container action.
 */
@Component
@Profile("!dev")
public class SshPropertiesValidator implements InitializingBean {

    private final SshProperties properties;

    public SshPropertiesValidator(SshProperties properties) {
        this.properties = properties;
    }

    @Override
    public void afterPropertiesSet() {
        boolean noPassword = properties.password() == null || properties.password().isBlank();
        boolean noKey = properties.privateKeyPath() == null || properties.privateKeyPath().isBlank();
        if (noPassword && noKey) {
            throw new IllegalStateException(
                    "nspawnmgr.ssh: neither a password nor a private-key-path is configured — the SSH "
                            + "transport to the sudo-capable account has nothing to authenticate with. "
                            + "See docs/administrator-guide.md, \"The sudo-capable SSH account\".");
        }
    }
}
