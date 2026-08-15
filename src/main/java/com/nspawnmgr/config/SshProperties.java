package com.nspawnmgr.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Connection details for the local sudo-capable account that RealContainerCliExecutor /
 * RealContainerFilesystemProvisioner SSH into, since Tomcat itself runs as a non-privileged user
 * with no sudo access.
 *
 * <p>Transport (SSH login) authenticates via {@code privateKeyPath} if set, else via
 * {@code password} — these are independent of container-creation "approval mode", which is
 * derived purely from whether {@code password} is blank ({@link #approvalRequired()}). A blank
 * password with no private key configured leaves nothing to authenticate the SSH session itself
 * with; see {@code SshPropertiesValidator}, which fails startup in that case.
 */
@ConfigurationProperties(prefix = "nspawnmgr.ssh")
public record SshProperties(
        String host,
        int port,
        String username,
        String password,
        String privateKeyPath,
        long connectTimeoutMs,
        boolean strictHostKeyChecking
) {
    /** Blank password selects container-creation approval mode (see docs/administrator-guide.md §3). */
    public boolean approvalRequired() {
        return password == null || password.isBlank();
    }
}
