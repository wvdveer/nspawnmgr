package com.nspawnmgr.rootwizard;

import net.schmizz.sshj.SSHClient;
import net.schmizz.sshj.common.IOUtils;
import net.schmizz.sshj.connection.ConnectionException;
import net.schmizz.sshj.connection.channel.direct.Session;
import net.schmizz.sshj.transport.verification.PromiscuousVerifier;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.nio.ByteBuffer;
import java.nio.CharBuffer;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;
import java.util.stream.Collectors;

/**
 * A minimal, Spring-free mirror of {@code cli.real.SshRemoteExecutor}, for the places that need to
 * run a privileged command over the sudo-capable SSH account before Spring exists yet:
 * {@link DatabaseSetupWizardServlet}'s self-hosted database machine provisioning (creation-time,
 * PASSWORD-tier — see {@code nspawnmgr-bootstrap-db-machine.sh}) and its "finish setup" reload
 * (NOPASSWD-tier, touching nspawnmgr's context XML via {@code nspawnmgr-write-file.sh}). Reads the
 * same SSH_* environment variables/system properties {@code application.yml}'s
 * {@code nspawnmgr.ssh.*} placeholders would, via the same system-property-then-env-var-then-default
 * resolution {@link DbConnectionSettings} already uses, so both bootstrap classes stay consistent
 * with each other and with the real (post-Spring) SshProperties bean once the main app is actually
 * up — including {@code SSH_HOST}, which the self-hosted {@code nspawnmgr} container's own copy of
 * {@code nspawnmgr.env} points at the host's bridge address rather than {@code 127.0.0.1} (see
 * {@code nspawnmgr-bootstrap-app-machine.sh}), so this class needs no changes of its own to reach
 * back out to the host correctly from inside that container.
 */
final class PreSpringSshClient {

    private PreSpringSshClient() {
    }

    record CommandResult(int exitStatus, String stdout, String stderr) {
        boolean succeeded() {
            return exitStatus == 0;
        }
    }

    /** Runs {@code command} under {@code sudo -n} on the sudo-capable account, writing {@code stdinPayload} (if any) to its stdin. */
    static CommandResult runNoPasswordSudo(List<String> command, String stdinPayload) throws IOException {
        return run("sudo -n " + quoteAll(command), stdinPayload, null, command, connectTimeoutMs() * 6);
    }

    /**
     * Runs {@code command} under {@code sudo -S}, feeding the stored sudo password down the exec
     * channel's stdin first, fresh on every call (no reliance on sudo's timestamp/ticket caching) —
     * same posture {@code SshRemoteExecutor.execWithSudoPassword} takes in the main module.
     * Creation-time-only commands (provisioning the self-hosted database machine) go through this
     * rather than {@link #runNoPasswordSudo}, matching every other creation-time operation in this
     * project's sudoers convention. {@code timeoutMs} is caller-supplied rather than the default
     * {@link #connectTimeoutMs()}-derived one — provisioning a whole new machine (package install +
     * first-boot database initialization) genuinely needs minutes, not the ~30s default.
     *
     * <p>Known limitation, unlike the main module's own {@code SshRemoteExecutor}: stdout/stderr
     * are read fully (blocking until the remote side closes them) before {@code cmd.join(timeoutMs,
     * ...)} is ever reached, so a remote command that hangs without closing its output streams
     * would hang here regardless of {@code timeoutMs} — {@code SshRemoteExecutor} fixed this exact
     * issue with concurrent reader threads (see its own comment) after hitting it live with a
     * network-stalled {@code apt-get}. Not replicated here yet: {@code nspawnmgr-bootstrap-db-
     * machine.sh} always exits and closes its channel on its own timeout paths, so this is a real
     * but currently-unexercised gap, not a known-live bug.
     */
    static CommandResult runWithSudoPassword(List<String> command, long timeoutMs) throws IOException {
        char[] sudoPassword = resolveKey("SSH_PASSWORD", "").toCharArray();
        return run("sudo -S -p '' " + quoteAll(command), null, sudoPassword, command, timeoutMs);
    }

    /**
     * Same PASSWORD-tier {@code sudo -S} posture as {@link #runWithSudoPassword}, but for
     * long-running provisioning commands (creating the self-hosted database machine) whose progress
     * the setup wizard's progress page streams to the admin "as it occurs" rather than only once the
     * whole thing finishes. Unlike {@link #run}, stdout and stderr are drained concurrently on
     * separate threads as they arrive (matching the fix {@code SshRemoteExecutor} already applies in
     * the main module - see {@link #runWithSudoPassword}'s javadoc for the deadlock this avoids),
     * with each stdout line handed to {@code onLine} as soon as it's read.
     */
    static CommandResult runWithSudoPasswordStreaming(List<String> command, long timeoutMs, Consumer<String> onLine)
            throws IOException {
        char[] sudoPassword = resolveKey("SSH_PASSWORD", "").toCharArray();
        String commandLine = "sudo -S -p '' " + quoteAll(command);
        try (SSHClient ssh = connect()) {
            try (Session session = ssh.startSession()) {
                try (Session.Command cmd = session.exec(commandLine)) {
                    try (OutputStream stdin = cmd.getOutputStream()) {
                        writePassword(stdin, sudoPassword);
                    }
                    StringBuilder stderrBuffer = new StringBuilder();
                    Thread stderrReader = new Thread(() -> {
                        try (BufferedReader reader = new BufferedReader(
                                new InputStreamReader(cmd.getErrorStream(), StandardCharsets.UTF_8))) {
                            String line;
                            while ((line = reader.readLine()) != null) {
                                stderrBuffer.append(line).append('\n');
                            }
                        } catch (IOException ignored) {
                            // Stream closes when the remote command exits - nothing to recover from here.
                        }
                    }, "nspawnmgr-ssh-stderr-reader");
                    stderrReader.setDaemon(true);
                    stderrReader.start();

                    StringBuilder stdoutBuffer = new StringBuilder();
                    try (BufferedReader reader = new BufferedReader(
                            new InputStreamReader(cmd.getInputStream(), StandardCharsets.UTF_8))) {
                        String line;
                        while ((line = reader.readLine()) != null) {
                            stdoutBuffer.append(line).append('\n');
                            onLine.accept(line);
                        }
                    }
                    stderrReader.join(timeoutMs);
                    try {
                        cmd.join(timeoutMs, TimeUnit.MILLISECONDS);
                    } catch (ConnectionException e) {
                        throw new IOException("Command timed out: " + String.join(" ", command), e);
                    }
                    Integer exitStatus = cmd.getExitStatus();
                    return new CommandResult(exitStatus == null ? -1 : exitStatus, stdoutBuffer.toString(), stderrBuffer.toString());
                }
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("Interrupted while waiting for streamed command output", e);
        }
    }

    private static CommandResult run(String commandLine, String stdinPayload, char[] sudoPassword, List<String> command,
                                       long timeoutMs) throws IOException {
        try (SSHClient ssh = connect()) {
            try (Session session = ssh.startSession()) {
                try (Session.Command cmd = session.exec(commandLine)) {
                    try (OutputStream stdin = cmd.getOutputStream()) {
                        if (sudoPassword != null) {
                            writePassword(stdin, sudoPassword);
                        }
                        if (stdinPayload != null) {
                            stdin.write(stdinPayload.getBytes(StandardCharsets.UTF_8));
                        }
                    }
                    String stdout = IOUtils.readFully(cmd.getInputStream()).toString();
                    String stderr = IOUtils.readFully(cmd.getErrorStream()).toString();
                    try {
                        cmd.join(timeoutMs, TimeUnit.MILLISECONDS);
                    } catch (ConnectionException e) {
                        throw new IOException("Command timed out: " + String.join(" ", command), e);
                    }
                    Integer exitStatus = cmd.getExitStatus();
                    return new CommandResult(exitStatus == null ? -1 : exitStatus, stdout, stderr);
                }
            }
        }
    }

    private static String quoteAll(List<String> command) {
        return command.stream().map(PreSpringSshClient::quote).collect(Collectors.joining(" "));
    }

    /** Same byte handling as SshRemoteExecutor.writePassword — zeroed out once written (best-effort hygiene). */
    private static void writePassword(OutputStream stdin, char[] password) throws IOException {
        CharBuffer charBuffer = CharBuffer.allocate(password.length + 1);
        charBuffer.put(password).put('\n').flip();
        ByteBuffer byteBuffer = StandardCharsets.UTF_8.encode(charBuffer);
        byte[] bytes = new byte[byteBuffer.remaining()];
        byteBuffer.get(bytes);
        try {
            stdin.write(bytes);
        } finally {
            Arrays.fill(bytes, (byte) 0);
        }
    }

    private static SSHClient connect() throws IOException {
        SSHClient ssh = new SSHClient();
        String host = resolveKey("SSH_HOST", "127.0.0.1");
        int port = Integer.parseInt(resolveKey("SSH_PORT", "22"));
        boolean strict = Boolean.parseBoolean(resolveKey("SSH_STRICT_HOST_KEY_CHECKING", "false"));
        if (strict) {
            ssh.loadKnownHosts();
        } else {
            ssh.addHostKeyVerifier(new PromiscuousVerifier());
        }
        ssh.setConnectTimeout((int) connectTimeoutMs());
        ssh.connect(host, port);
        String username = resolveKey("SSH_USERNAME", "");
        String privateKeyPath = resolveKey("SSH_PRIVATE_KEY_PATH", "");
        try {
            if (!privateKeyPath.isBlank()) {
                ssh.authPublickey(username, privateKeyPath);
            } else {
                ssh.authPassword(username, resolveKey("SSH_PASSWORD", ""));
            }
        } catch (IOException e) {
            ssh.close();
            throw e;
        }
        return ssh;
    }

    private static long connectTimeoutMs() {
        return Long.parseLong(resolveKey("SSH_CONNECT_TIMEOUT_MS", "5000"));
    }

    private static String quote(String value) {
        return "'" + value.replace("'", "'\\''") + "'";
    }

    // Same precedence DbConnectionSettings uses: system property, then env var, then default.
    // Doesn't read the wizard's own db-config file (irrelevant here - these are SSH settings, not
    // database ones) or nspawnmgr.env directly, since systemd's EnvironmentFile= already injects
    // that file's contents as real process environment variables by the time this code runs.
    private static String resolveKey(String key, String defaultValue) {
        String fromSystemProperty = System.getProperty(key);
        if (fromSystemProperty != null) {
            return fromSystemProperty;
        }
        String fromEnv = System.getenv(key);
        if (fromEnv != null) {
            return fromEnv;
        }
        return defaultValue;
    }

    static String privilegedScriptsDir() {
        return resolveKey("NSPAWN_PRIVILEGED_SCRIPTS_DIR", "/usr/lib/nspawnmgr/privileged");
    }

    /** Same env-var/system-property/default resolution as {@link #privilegedScriptsDir()}, for the
     *  systemd-nspawn subdirectory of TEMPLATES_DIR (see {@code ContainerBackend.SYSTEMD_NSPAWN
     *  .templateSubdirectory()} in the main module, and {@code nspawnmgr-bootstrap-app-machine.sh},
     *  which leaves the debian-minimal tarball there). */
    static String templatesNspawnDir() {
        return resolveKey("NSPAWN_TEMPLATES_DIR", "/var/lib/nspawnmgr/templates") + "/nspawn";
    }
}
