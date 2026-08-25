package com.nspawnmgr.cli;

/** The real host-side systemd unit's own state for one in-progress package download - distinct
 *  from {@code PackageDownloadState}, which also has ABORTED (a user-initiated stop, tracked
 *  service-side, not something the unit's own exit status can distinguish from any other kill). */
public enum PackageDownloadUnitStatus {
    RUNNING, SUCCEEDED, FAILED, NOT_FOUND
}
