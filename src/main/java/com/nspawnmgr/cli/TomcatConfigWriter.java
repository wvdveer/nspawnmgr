package com.nspawnmgr.cli;

/**
 * Commits a fully-rebuilt {@code server.xml} (or any other Tomcat-owned config file) to disk. Real
 * implementation goes over SSH as the sudo-capable account, reusing the existing
 * {@code nspawnmgr-write-file.sh} wrapper script (already NOPASSWD-granted, already used for
 * writing {@code .nspawn} settings files — no new wrapper script or sudoers entry needed); the dev
 * profile swaps in a fake that writes the local file directly.
 */
public interface TomcatConfigWriter {

    void write(String path, String content);
}
