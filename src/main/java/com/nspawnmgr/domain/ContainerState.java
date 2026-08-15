package com.nspawnmgr.domain;

public enum ContainerState {
    CREATING,
    /** machinectl start succeeded and provisioning finished, but SSH (and RDP, if enabled) aren't confirmed reachable yet. */
    BOOTING,
    STOPPED, RUNNING, ERROR, DELETING,
    /** Frozen via the cgroup freezer (systemctl freeze on the machine's own scope unit) - all
     *  processes suspended in place, nothing torn down. Only reachable from RUNNING, only exits
     *  back to RUNNING via resume. */
    PAUSED,
    /** Container-creation approval mode only: created, awaiting an admin's approve/deny. */
    PENDING_APPROVAL,
    /** Terminal — an admin denied the creation request. No provisioning was ever attempted. */
    DENIED
}
