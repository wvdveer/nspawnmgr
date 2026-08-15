package com.nspawnmgr.cli;

/**
 * Restarts the Tomcat instance nspawnmgr itself runs in — used by the "Restart Tomcat" button on
 * /admin/settings, e.g. after changing {@code nspawnmgr.cli.executor}, which can't take effect
 * live. Real implementation runs this over SSH as the sudo-capable account (see
 * SshRemoteExecutor); the dev profile swaps in a no-op fake from tools/fake-machinectl.
 */
public interface TomcatRestartService {

    /**
     * Fires the restart and returns — does not wait for Tomcat to actually go down (it can't: the
     * request handling this call is itself served by the Tomcat instance being restarted).
     */
    void restart();
}
