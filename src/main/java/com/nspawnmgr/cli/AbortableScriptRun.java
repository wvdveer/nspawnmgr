package com.nspawnmgr.cli;

/**
 * A script run started via {@link ContainerCliExecutor#startScript}, still in flight. Lets a
 * different thread than the one blocked in {@link #await()} request that the run be killed.
 */
public interface AbortableScriptRun {

    /** Blocks until the run completes, times out, or is aborted — same semantics as runScript. */
    ScriptRunResult await();

    /**
     * Best-effort request to kill the run. Safe to call from a different thread than the one
     * inside {@link #await()}; safe to call more than once or after the run has already finished
     * (a no-op past the first call).
     */
    void abort();
}
