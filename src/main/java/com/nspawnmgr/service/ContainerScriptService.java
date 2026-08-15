package com.nspawnmgr.service;

import com.nspawnmgr.cli.AbortableScriptRun;
import com.nspawnmgr.cli.ContainerCliException;
import com.nspawnmgr.cli.ContainerCliExecutor;
import com.nspawnmgr.cli.ScriptRunResult;
import com.nspawnmgr.domain.AuditAction;
import com.nspawnmgr.domain.AuditTargetType;
import com.nspawnmgr.domain.Container;
import com.nspawnmgr.domain.ContainerScript;
import com.nspawnmgr.domain.User;
import com.nspawnmgr.repository.ContainerScriptRepository;
import org.springframework.core.task.TaskExecutor;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.RejectedExecutionException;

/**
 * Named, reusable scripts an owner (or a shared user — see ContainerShare) defines for a container
 * and runs on demand via {@link ContainerCliExecutor#runScript}/{@link ContainerCliExecutor#startScript}.
 * Authored by whoever already has full interactive root-shell access to this same container
 * (Guacamole), so running one grants no new privilege — see docs/administrator-guide.md's
 * trust-boundary section.
 *
 * <p>{@link #run} is deliberately not {@code @Transactional}: it can take up to {@link #RUN_TIMEOUT}
 * (a real SSH round trip to the container), and holding a DB transaction open for that long would be
 * wasteful/risky — save first (short transaction), then run.
 *
 * <p>{@link #startRun} is the abortable counterpart used by the interactive UI: it tracks in-flight
 * (and briefly, recently-finished) runs in an in-memory {@link #activeRuns} map, purely for the
 * lifetime of this process — nothing here is persisted, same posture as {@link
 * com.nspawnmgr.security.IdentityCache}. This is deliberate: runs are bounded to {@link #RUN_TIMEOUT}
 * (2 minutes), so losing this state on restart is an acceptable tradeoff for not needing a new table.
 */
@Service
public class ContainerScriptService {

    private static final Duration RUN_TIMEOUT = Duration.ofMinutes(2);
    private static final Duration RUN_RETENTION = Duration.ofMinutes(10);

    private final ContainerScriptRepository scriptRepository;
    private final ContainerCliExecutor cliExecutor;
    private final TaskExecutor taskExecutor;
    private final AuditLogService auditLogService;
    private final Map<String, ActiveRun> activeRuns = new ConcurrentHashMap<>();

    public ContainerScriptService(ContainerScriptRepository scriptRepository, ContainerCliExecutor cliExecutor,
                                   TaskExecutor taskExecutor, AuditLogService auditLogService) {
        this.scriptRepository = scriptRepository;
        this.cliExecutor = cliExecutor;
        this.taskExecutor = taskExecutor;
        this.auditLogService = auditLogService;
    }

    public List<ContainerScript> list(Container container) {
        return scriptRepository.findByContainerOrderByName(container);
    }

    public ContainerScript get(Container container, Long scriptId) {
        return requireOwnScript(container, scriptId);
    }

    @Transactional
    public ContainerScript create(Container container, String name, String scriptBody) {
        requireUniqueName(container, name, null);
        return scriptRepository.save(new ContainerScript(container, name, scriptBody));
    }

    @Transactional
    public ContainerScript update(Container container, Long scriptId, String name, String scriptBody) {
        ContainerScript script = requireOwnScript(container, scriptId);
        requireUniqueName(container, name, scriptId);
        script.setName(name);
        script.setScriptBody(scriptBody);
        script.setUpdatedAt(Instant.now());
        return script;
    }

    @Transactional
    public void delete(Container container, Long scriptId) {
        scriptRepository.delete(requireOwnScript(container, scriptId));
    }

    /** Runs whatever body is currently stored for {@code scriptBody} against the container. */
    public ScriptRunResult run(Container container, String scriptBody) {
        return cliExecutor.runScript(container.getName(), scriptBody, RUN_TIMEOUT);
    }

    /**
     * Starts {@code scriptBody} running asynchronously and returns a run id to poll/abort with —
     * the interactive UI's counterpart to {@link #run}. {@code actingUser} must already be resolved
     * by the caller (e.g. via CurrentUserProvider on the request thread): it's used later from
     * {@link #taskExecutor}'s worker thread, which has no Spring Security context of its own.
     */
    public String startRun(Container container, String scriptBody, User actingUser) {
        String runId = UUID.randomUUID().toString();
        AbortableScriptRun handle = cliExecutor.startScript(container.getName(), scriptBody, RUN_TIMEOUT);
        ActiveRun activeRun = new ActiveRun(container.getId(), handle);
        try {
            taskExecutor.execute(() -> {
                ScriptRunResult result = handle.await();
                activeRun.result = result;
                activeRun.state = activeRun.aborted ? ScriptRunState.ABORTED : ScriptRunState.COMPLETED;
                activeRun.completedAt = Instant.now();
                auditLogService.log(actingUser,
                        activeRun.state == ScriptRunState.ABORTED ? AuditAction.ABORTED : AuditAction.RAN,
                        AuditTargetType.CONTAINER, container.getId(), container.getName(),
                        "script run " + runId + " finished (exit " + result.exitCode() + ")");
            });
        } catch (RejectedExecutionException e) {
            // The pool+queue are exhausted - the SSH connection/reader threads startScript already
            // opened would otherwise leak forever with nothing left to await() them.
            handle.abort();
            throw new ContainerCliException("Too many concurrent script runs; try again shortly", e);
        }
        activeRuns.put(runId, activeRun);
        return runId;
    }

    public ActiveRun getStatus(Container container, String runId) {
        return requireOwnRun(container, runId);
    }

    public void abort(Container container, String runId) {
        ActiveRun activeRun = requireOwnRun(container, runId);
        activeRun.aborted = true;
        activeRun.handle.abort();
    }

    /** Evicts finished runs once they're old enough that nobody's still polling for their result. */
    @Scheduled(fixedDelay = 600000)
    public void evictCompletedRuns() {
        Instant cutoff = Instant.now().minus(RUN_RETENTION);
        activeRuns.values().removeIf(r -> r.state != ScriptRunState.RUNNING
                && r.completedAt != null && r.completedAt.isBefore(cutoff));
    }

    private ActiveRun requireOwnRun(Container container, String runId) {
        ActiveRun activeRun = activeRuns.get(runId);
        if (activeRun == null || !activeRun.containerId.equals(container.getId())) {
            throw new IllegalArgumentException("No such script run: " + runId);
        }
        return activeRun;
    }

    /** Tracks one in-flight (or recently-finished) {@link #startRun} call. */
    public static final class ActiveRun {
        private final Long containerId;
        private final AbortableScriptRun handle;
        private volatile ScriptRunState state = ScriptRunState.RUNNING;
        private volatile ScriptRunResult result;
        private volatile boolean aborted;
        private volatile Instant completedAt;

        private ActiveRun(Long containerId, AbortableScriptRun handle) {
            this.containerId = containerId;
            this.handle = handle;
        }

        public ScriptRunState state() {
            return state;
        }

        public ScriptRunResult result() {
            return result;
        }
    }

    private ContainerScript requireOwnScript(Container container, Long scriptId) {
        return scriptRepository.findByIdAndContainer(scriptId, container)
                .orElseThrow(() -> new IllegalArgumentException("No such script: " + scriptId));
    }

    private void requireUniqueName(Container container, String name, Long excludingId) {
        boolean taken = scriptRepository.findByContainerOrderByName(container).stream()
                .anyMatch(s -> s.getName().equalsIgnoreCase(name) && !s.getId().equals(excludingId));
        if (taken) {
            throw new IllegalStateException("A script named '" + name + "' already exists for this container");
        }
    }
}
