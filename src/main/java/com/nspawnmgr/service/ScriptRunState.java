package com.nspawnmgr.service;

/**
 * Deliberately 3 states, not 4: whether a finished run's exitCode -1 means "timed out" or "the
 * process itself exited -1" is already ambiguous today (see ContainerScriptService) and that's out
 * of scope here - only "was this explicitly aborted by the user" is new, worth surfacing on its own.
 */
public enum ScriptRunState {
    RUNNING, COMPLETED, ABORTED
}
