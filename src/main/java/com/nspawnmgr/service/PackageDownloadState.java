package com.nspawnmgr.service;

/** Service-level state for one admin-initiated URL download - distinct from
 *  {@code PackageDownloadUnitStatus}, the raw systemd-unit-level signal this is derived from.
 *  ABORTED is tracked here (not derivable from the unit's own exit status, which can't tell a
 *  user-requested stop apart from any other kill) the same way {@code ScriptRunState} does. */
public enum PackageDownloadState {
    RUNNING, COMPLETED, FAILED, ABORTED
}
