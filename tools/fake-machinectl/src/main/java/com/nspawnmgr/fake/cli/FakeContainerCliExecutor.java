package com.nspawnmgr.fake.cli;

import com.nspawnmgr.cli.AbortableScriptRun;
import com.nspawnmgr.cli.CommandResult;
import com.nspawnmgr.cli.ContainerCliException;
import com.nspawnmgr.cli.ContainerCliExecutor;
import com.nspawnmgr.cli.MachineBootSettings;
import com.nspawnmgr.cli.MachineStatus;
import com.nspawnmgr.cli.OutputLine;
import com.nspawnmgr.cli.OutputSource;
import com.nspawnmgr.cli.ScriptRunResult;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.stream.Collectors;

/**
 * Stands in for machinectl/systemd-run on Windows dev machines: tracks machine state in memory
 * and appends every invocation to a log file so site/scripts can assert what "would have" run.
 */
@Component
@Profile("dev")
public class FakeContainerCliExecutor implements ContainerCliExecutor {

    private final Map<String, MachineStatus> machines = new ConcurrentHashMap<>();
    /** machine name -> usernames "inside" it — lets the container-users feature be exercised in dev without real SSH. */
    private final Map<String, Set<String>> containerUsers = new ConcurrentHashMap<>();
    /** machine name -> whether it's "enabled" to auto-start on host boot - stands in for real systemctl state. */
    private final Map<String, Boolean> autoStart = new ConcurrentHashMap<>();
    /** machine name -> the other machine it "requires" started first, if any. */
    private final Map<String, String> requiresMachine = new ConcurrentHashMap<>();
    private final Path logFile;

    /** Never created through the app itself - stands in for a machine an admin built by hand
     *  directly on the host, so the dev stack always has something real for "Discover" to find. */
    private static final String HAND_BUILT_MACHINE = "hand-built-1";

    public FakeContainerCliExecutor() {
        this.logFile = Path.of(System.getProperty("java.io.tmpdir"), "nspawnmgr-dev", "fake-machinectl.log");
        machines.put(HAND_BUILT_MACHINE, MachineStatus.RUNNING);
    }

    @Override
    public void start(String machineName) {
        machines.put(machineName, MachineStatus.RUNNING);
        log("start " + machineName);
    }

    @Override
    public void stopGraceful(String machineName) {
        machines.put(machineName, MachineStatus.STOPPED);
        log("poweroff " + machineName);
    }

    @Override
    public void stopForce(String machineName) {
        machines.put(machineName, MachineStatus.STOPPED);
        log("terminate " + machineName);
    }

    @Override
    public void restart(String machineName) {
        machines.put(machineName, MachineStatus.RUNNING);
        log("reboot " + machineName);
    }

    @Override
    public void remove(String machineName) {
        machines.remove(machineName);
        log("remove " + machineName);
    }

    @Override
    public void pause(String machineName) {
        // No real cgroup freezer to touch in dev mode - MachineStatus itself doesn't change either
        // way in reality (systemctl freeze suspends processes, it doesn't change machinectl's own
        // reported status), so this only needs to prove the caller's wiring.
        log("freeze systemd-nspawn@" + machineName + ".service");
    }

    @Override
    public void resume(String machineName) {
        log("thaw systemd-nspawn@" + machineName + ".service");
    }

    @Override
    public MachineStatus status(String machineName) {
        return machines.getOrDefault(machineName, MachineStatus.NOT_FOUND);
    }

    @Override
    public CommandResult runInMachine(String machineName, List<String> command, Duration timeout,
                                        char[] sudoPasswordOverride, String stdinPayload) {
        log("exec[" + machineName + "] " + String.join(" ", command));
        if (!command.isEmpty() && "useradd".equals(command.get(0))) {
            usersOf(machineName).add(command.get(command.size() - 1));
        } else if (!command.isEmpty() && "chpasswd".equals(command.get(0)) && stdinPayload != null) {
            int colon = stdinPayload.indexOf(':');
            if (colon > 0) {
                usersOf(machineName).add(stdinPayload.substring(0, colon));
            }
        }
        return new CommandResult(0, "", "");
    }

    // Takes ~6 real seconds (in 200ms ticks, checking the abort flag each time) rather than
    // returning instantly, so the dev-stack/harness Abort button has an actual window to click
    // during - the old instant version gave Abort nothing to exercise. Synthetic timestamps still
    // give the completed-run case a >1s gap between lines, for the ">1s gap -> new timestamp
    // heading" UI grouping rule.
    @Override
    public AbortableScriptRun startScript(String machineName, String scriptBody, Duration timeout) {
        log("script[" + machineName + "] " + scriptBody.lines().count() + " line(s)");
        AtomicBoolean aborted = new AtomicBoolean(false);
        CountDownLatch done = new CountDownLatch(1);
        Instant first = Instant.now();
        Thread worker = new Thread(() -> {
            for (int i = 0; i < 30 && !aborted.get(); i++) {
                try {
                    Thread.sleep(200);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    break;
                }
            }
            done.countDown();
        }, "fake-script-" + machineName);
        worker.setDaemon(true);
        worker.start();
        return new AbortableScriptRun() {
            @Override
            public ScriptRunResult await() {
                try {
                    done.await();
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
                if (aborted.get()) {
                    return new ScriptRunResult(-1, List.of(
                            new OutputLine(first, OutputSource.STDOUT, "(fake) running script on " + machineName + "..."),
                            new OutputLine(Instant.now(), OutputSource.STDERR, "(fake) aborted")));
                }
                return new ScriptRunResult(0, List.of(
                        new OutputLine(first, OutputSource.STDOUT, "(fake) running script on " + machineName + "..."),
                        new OutputLine(first.plusSeconds(2), OutputSource.STDOUT, "(fake) done"),
                        new OutputLine(first.plusSeconds(2), OutputSource.STDERR, "(fake) no real container involved in dev mode")));
            }

            @Override
            public void abort() {
                if (aborted.compareAndSet(false, true)) {
                    worker.interrupt();
                }
            }
        };
    }

    @Override
    public CommandResult listContainerUsers(String machineName) {
        String passwdLines = usersOf(machineName).stream()
                .map(username -> username + ":x:1000:1000::/home/" + username + ":/bin/bash")
                .collect(Collectors.joining("\n"));
        return new CommandResult(0, passwdLines, "");
    }

    @Override
    public String getInternalAddress(String machineName) {
        return "10.0.3." + (Math.floorMod(machineName.hashCode(), 250) + 2);
    }

    @Override
    public List<String> listMachineImageNames() {
        return List.copyOf(machines.keySet());
    }

    /** Real DNS resolution via the JVM's own resolver - genuinely works cross-platform on a Windows
     *  dev machine, unlike every other method here, so there's no need to fake this one. */
    @Override
    public String resolveHostname(String hostname) {
        try {
            return java.net.InetAddress.getByName(hostname).getHostAddress();
        } catch (java.net.UnknownHostException e) {
            throw new ContainerCliException("Could not resolve hostname '" + hostname + "': " + e.getMessage(), e);
        }
    }

    @Override
    public MachineBootSettings getBootSettings(String machineName) {
        return new MachineBootSettings(autoStart.getOrDefault(machineName, false), requiresMachine.get(machineName));
    }

    @Override
    public void setAutoStart(String machineName, boolean autoStart) {
        this.autoStart.put(machineName, autoStart);
    }

    @Override
    public void setRequiresMachine(String machineName, String requiresMachineName) {
        if (requiresMachineName == null || requiresMachineName.isBlank()) {
            requiresMachine.remove(machineName);
        } else {
            requiresMachine.put(machineName, requiresMachineName);
        }
    }

    /** Seeds a canned user on first access per machine, so the users panel has something to show immediately. */
    private Set<String> usersOf(String machineName) {
        return containerUsers.computeIfAbsent(machineName, name -> {
            Set<String> seeded = ConcurrentHashMap.newKeySet();
            seeded.add("devuser");
            return seeded;
        });
    }

    private void log(String line) {
        try {
            Files.createDirectories(logFile.getParent());
            Files.writeString(logFile, Instant.now() + " " + line + System.lineSeparator(),
                    StandardOpenOption.CREATE, StandardOpenOption.APPEND);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
