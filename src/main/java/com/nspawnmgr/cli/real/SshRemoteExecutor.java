package com.nspawnmgr.cli.real;

import com.nspawnmgr.cli.AbortableScriptRun;
import com.nspawnmgr.cli.CommandResult;
import com.nspawnmgr.cli.ContainerCliException;
import com.nspawnmgr.cli.OutputLine;
import com.nspawnmgr.cli.OutputSource;
import com.nspawnmgr.cli.ScriptRunResult;
import com.nspawnmgr.service.SettingsService;
import net.schmizz.sshj.SSHClient;
import net.schmizz.sshj.common.IOUtils;
import net.schmizz.sshj.connection.ConnectionException;
import net.schmizz.sshj.connection.channel.direct.Session;
import net.schmizz.sshj.transport.verification.PromiscuousVerifier;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

import java.io.BufferedReader;
import java.io.Closeable;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.nio.ByteBuffer;
import java.nio.CharBuffer;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.stream.Collectors;

/**
 * Runs commands as root on the container host by SSHing into a separate sudo-capable local
 * account. Two exec paths, matching the two privilege tiers described in
 * docs/administrator-guide.md ("The sudo-capable SSH account"):
 *
 * <ul>
 *     <li>{@link #execNoPasswordSudo}: fixed-shape, always-safe commands (container start/stop/
 *     status/delete, outbound-access sync) that must never block on a password — relies on a
 *     {@code NOPASSWD} sudoers grant for exactly these commands.</li>
 *     <li>{@link #execWithSudoPassword}: creation-time-only commands that run template-authored
 *     content as root inside a fresh container — requires a password, resupplied fresh on every
 *     call (no reliance on sudo's timestamp/ticket caching).</li>
 * </ul>
 *
 * <p>A fresh SSH connection is opened, authenticated, and torn down per call rather than pooled/
 * reused: these are infrequent operations, not a hot path, and a connection-per-call keeps this
 * class free of shared mutable state under concurrent Tomcat request threads.
 *
 * <p>Host key checking is off by default ({@code strict-host-key-checking: false}) since the
 * target is always the loopback interface of the same box Tomcat runs on; set it to true and rely
 * on the Tomcat account's {@code ~/.ssh/known_hosts} if that stops being true.
 */
@Component
@Profile("!dev")
public class SshRemoteExecutor {

    private final SettingsService settingsService;

    public SshRemoteExecutor(SettingsService settingsService) {
        this.settingsService = settingsService;
    }

    /** Runs {@code command} under {@code sudo -n} — never prompts, relies on a NOPASSWD sudoers grant. */
    public CommandResult execNoPasswordSudo(Duration timeout, List<String> command) {
        return execNoPasswordSudo(timeout, command, null);
    }

    /** As {@link #execNoPasswordSudo(Duration, List)}, writing {@code stdinPayload} to the command's stdin. */
    public CommandResult execNoPasswordSudo(Duration timeout, List<String> command, String stdinPayload) {
        return run(timeout, buildNoPasswordSudoCommandLine(command), command, stdinPayload, null);
    }

    /**
     * Runs {@code command} under {@code sudo -S}, feeding {@code sudoPassword} down the exec
     * channel's stdin fresh on every call. {@code stdinPayload}, if non-null, is written
     * immediately after the password line (e.g. file content for a remote {@code cat > file}).
     */
    public CommandResult execWithSudoPassword(Duration timeout, List<String> command, String stdinPayload,
                                                char[] sudoPassword) {
        return run(timeout, buildSudoCommandLine(command), command, stdinPayload, sudoPassword);
    }

    /**
     * As {@link #execNoPasswordSudo(Duration, List, String)}, but streams {@code stdinWriter}'s
     * output to the command's stdin incrementally instead of writing one pre-built String payload -
     * needed for package uploads, which can be multiple GB (see RealPackageCacheFilesystem#upload's
     * own comment for why buffering the whole thing first - in memory, or as one giant Base64
     * String - caused a real OutOfMemoryError live). {@code stdinWriter} is handed the raw stdin
     * {@link OutputStream} to write to however it needs (e.g. wrapped in a
     * {@code Base64.getEncoder().wrap(...)} before streaming a source file into it) - closing it is
     * this method's job, not the writer's.
     *
     * <p>Unlike {@link #run}, the stdout/stderr reader threads are started <em>before</em> the stdin
     * write begins, not after. {@code run()}'s "write the whole (small) payload, then start readers"
     * ordering only works because that payload is small enough to fit in the SSH channel's
     * flow-control window without the remote side needing to drain its own output first - for a
     * multi-GB streamed write that's a real deadlock risk (this thread blocks on window space while
     * the remote process blocks on an unread stdout/stderr buffer, each waiting on the other).
     * Starting readers first eliminates that regardless of how much either side ever writes.
     */
    public CommandResult execNoPasswordSudoStreamingStdin(Duration timeout, List<String> command, StdinWriter stdinWriter) {
        try (SSHClient ssh = connect()) {
            try (Session session = ssh.startSession()) {
                try (Session.Command cmd = session.exec(buildNoPasswordSudoCommandLine(command))) {
                    StringBuilder stdout = new StringBuilder();
                    StringBuilder stderr = new StringBuilder();
                    Thread stdoutReader = startStreamReader(cmd.getInputStream(), stdout);
                    Thread stderrReader = startStreamReader(cmd.getErrorStream(), stderr);
                    try (OutputStream stdin = cmd.getOutputStream()) {
                        stdinWriter.writeTo(stdin);
                    }
                    ConnectionException timeoutCause = null;
                    try {
                        cmd.join(timeout.toMillis(), TimeUnit.MILLISECONDS);
                    } catch (ConnectionException e) {
                        timeoutCause = e;
                    }
                    joinQuietly(stdoutReader, Duration.ofSeconds(5));
                    joinQuietly(stderrReader, Duration.ofSeconds(5));
                    if (timeoutCause != null) {
                        throw new ContainerCliException("Command timed out: " + describe(command), timeoutCause);
                    }
                    Integer exitStatus = cmd.getExitStatus();
                    return new CommandResult(exitStatus == null ? -1 : exitStatus, stdout.toString(), stderr.toString());
                }
            }
        } catch (IOException e) {
            throw new ContainerCliException("SSH command failed: " + describe(command), e);
        }
    }

    /** Writes directly to a live SSH command's stdin - see {@link #execNoPasswordSudoStreamingStdin}. */
    @FunctionalInterface
    public interface StdinWriter {
        void writeTo(OutputStream out) throws IOException;
    }

    /**
     * As {@link #execNoPasswordSudo(Duration, List, String)}, but captures stdout/stderr line by
     * line as they arrive (two concurrent reader threads, one per stream) instead of reading each
     * stream fully only after the command exits — needed so each line can be timestamped at the
     * moment it was actually received, for {@code ContainerScriptService}'s run-output display.
     * Unlike the other exec methods here, the SSH connection/session/command are NOT closed
     * before this method returns — their lifetime now spans until {@link AbortableScriptRun#await()}
     * is called (from whatever thread that ends up being), so a different thread can call
     * {@link AbortableScriptRun#abort()} in the meantime. {@code onAbort} runs first on abort — the
     * real kill (e.g. a companion command targeting whatever the remote side actually started),
     * over a separate SSH connection, since closing this channel only stops us waiting on it, it
     * doesn't reliably kill the remote process by itself.
     */
    public AbortableScriptRun startNoPasswordSudoCapturingTimestampedOutput(Duration timeout, List<String> command,
                                                                              String stdinPayload, Runnable onAbort) {
        SSHClient ssh = connect();
        Session session;
        Session.Command cmd;
        try {
            session = ssh.startSession();
            cmd = session.exec(buildNoPasswordSudoCommandLine(command));
            try (OutputStream stdin = cmd.getOutputStream()) {
                if (stdinPayload != null) {
                    stdin.write(stdinPayload.getBytes(StandardCharsets.UTF_8));
                }
            }
        } catch (IOException e) {
            closeQuietly(ssh);
            throw new ContainerCliException("SSH command failed: " + describe(command), e);
        }
        List<OutputLine> lines = Collections.synchronizedList(new ArrayList<>());
        Thread stdoutReader = startLineReader(cmd.getInputStream(), OutputSource.STDOUT, lines);
        Thread stderrReader = startLineReader(cmd.getErrorStream(), OutputSource.STDERR, lines);
        AtomicBoolean aborted = new AtomicBoolean(false);
        Session.Command finalCmd = cmd;
        return new AbortableScriptRun() {
            @Override
            public ScriptRunResult await() {
                boolean timedOut = false;
                try {
                    finalCmd.join(timeout.toMillis(), TimeUnit.MILLISECONDS);
                } catch (ConnectionException e) {
                    timedOut = true;
                } finally {
                    // The process has exited, timed out, or was aborted (which closes this channel)
                    // - either way, its streams should reach EOF shortly; give the reader threads a
                    // bounded window to drain whatever's left rather than losing trailing output.
                    joinQuietly(stdoutReader, Duration.ofSeconds(5));
                    joinQuietly(stderrReader, Duration.ofSeconds(5));
                }
                Integer exitStatus = finalCmd.getExitStatus();
                int exitCode = timedOut || exitStatus == null ? -1 : exitStatus;
                // Insertion order from two independently-scheduled threads isn't guaranteed to
                // exactly match true arrival order under contention - sort explicitly rather than
                // trust it.
                List<OutputLine> sorted = new ArrayList<>(lines);
                sorted.sort(Comparator.comparing(OutputLine::timestamp));
                closeQuietly(finalCmd);
                closeQuietly(session);
                closeQuietly(ssh);
                return new ScriptRunResult(exitCode, List.copyOf(sorted));
            }

            @Override
            public void abort() {
                if (aborted.compareAndSet(false, true)) {
                    onAbort.run();
                    // Closes the channel out from under a possibly-concurrently-blocked join() in
                    // await() on another thread, waking it promptly rather than waiting for the
                    // remote side to notice onAbort's kill (or the timeout) on its own.
                    try {
                        finalCmd.close();
                    } catch (IOException ignored) {
                    }
                }
            }
        };
    }

    private static Thread startLineReader(InputStream stream, OutputSource source, List<OutputLine> sink) {
        Thread thread = new Thread(() -> {
            try (BufferedReader reader = new BufferedReader(new InputStreamReader(stream, StandardCharsets.UTF_8))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    sink.add(new OutputLine(Instant.now(), source, line));
                }
            } catch (IOException ignored) {
                // Stream closed underneath us (session torn down) - nothing more to read.
            }
        }, "script-output-" + source);
        thread.setDaemon(true);
        thread.start();
        return thread;
    }

    private static void joinQuietly(Thread thread, Duration wait) {
        try {
            thread.join(wait.toMillis());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private CommandResult run(Duration timeout, String commandLine, List<String> command, String stdinPayload,
                                char[] sudoPassword) {
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
                    // Read stdout/stderr concurrently on separate threads rather than blocking this
                    // thread on IOUtils.readFully() before ever calling cmd.join(timeout, ...) - a
                    // remote command that never closes its stdout (e.g. apt-get hanging because DNS/
                    // network to its package mirror is broken rather than cleanly refused) used to
                    // block here forever, meaning `timeout` was never actually enforced. Confirmed
                    // live: an RDP-enabled container whose apt-get install couldn't reach
                    // deb.debian.org stayed stuck in CREATING indefinitely instead of failing into
                    // ERROR. Same pattern execNoPasswordSudoCapturingTimestampedOutput already uses.
                    StringBuilder stdout = new StringBuilder();
                    StringBuilder stderr = new StringBuilder();
                    Thread stdoutReader = startStreamReader(cmd.getInputStream(), stdout);
                    Thread stderrReader = startStreamReader(cmd.getErrorStream(), stderr);
                    ConnectionException timeoutCause = null;
                    try {
                        cmd.join(timeout.toMillis(), TimeUnit.MILLISECONDS);
                    } catch (ConnectionException e) {
                        timeoutCause = e;
                    }
                    joinQuietly(stdoutReader, Duration.ofSeconds(5));
                    joinQuietly(stderrReader, Duration.ofSeconds(5));
                    if (timeoutCause != null) {
                        throw new ContainerCliException("Command timed out: " + describe(command), timeoutCause);
                    }
                    Integer exitStatus = cmd.getExitStatus();
                    return new CommandResult(exitStatus == null ? -1 : exitStatus, stdout.toString(), stderr.toString());
                }
            }
        } catch (IOException e) {
            throw new ContainerCliException("SSH command failed: " + describe(command), e);
        }
    }

    private static Thread startStreamReader(InputStream stream, StringBuilder sink) {
        Thread thread = new Thread(() -> {
            try {
                sink.append(IOUtils.readFully(stream));
            } catch (IOException ignored) {
                // Stream closed underneath us (session torn down, or we gave up waiting after a
                // timeout) - keep whatever was captured before that.
            }
        }, "ssh-stream-reader");
        thread.setDaemon(true);
        thread.start();
        return thread;
    }

    // Encodes without going through an intermediate String, and zeroes the encoded byte copy
    // once written — best-effort hygiene, not a guarantee (the JVM/network stack may still buffer
    // copies elsewhere).
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

    private SSHClient connect() {
        SSHClient ssh = new SSHClient();
        String host = settingsService.sshHost();
        int port = settingsService.sshPort();
        try {
            if (settingsService.sshStrictHostKeyChecking()) {
                ssh.loadKnownHosts();
            } else {
                ssh.addHostKeyVerifier(new PromiscuousVerifier());
            }
            ssh.setConnectTimeout((int) settingsService.sshConnectTimeoutMs());
            ssh.connect(host, port);
            String privateKeyPath = settingsService.sshPrivateKeyPath();
            if (privateKeyPath != null && !privateKeyPath.isBlank()) {
                ssh.authPublickey(settingsService.sshUsername(), privateKeyPath);
            } else {
                ssh.authPassword(settingsService.sshUsername(), settingsService.sshPassword());
            }
            return ssh;
        } catch (IOException e) {
            closeQuietly(ssh);
            throw new ContainerCliException("Failed to establish SSH connection to " + host + ":" + port, e);
        }
    }

    private static void closeQuietly(Closeable closeable) {
        try {
            closeable.close();
        } catch (IOException ignored) {
        }
    }

    private static String buildNoPasswordSudoCommandLine(List<String> command) {
        return "sudo -n " + command.stream()
                .map(SshRemoteExecutor::quote)
                .collect(Collectors.joining(" "));
    }

    private static String buildSudoCommandLine(List<String> command) {
        // -p '': suppress sudo's own password prompt text, since none of it should leak into the
        // captured stdout/stderr streams.
        return "sudo -S -p '' " + command.stream()
                .map(SshRemoteExecutor::quote)
                .collect(Collectors.joining(" "));
    }

    private static String quote(String value) {
        return "'" + value.replace("'", "'\\''") + "'";
    }

    private static String describe(List<String> command) {
        return String.join(" ", command);
    }
}
