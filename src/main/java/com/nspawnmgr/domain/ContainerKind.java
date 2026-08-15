package com.nspawnmgr.domain;

/**
 * MANAGED: nspawnmgr creates/provisions/controls the lifecycle (systemd-nspawn container).
 * EXTERNAL: an admin-configured pre-existing host (the bare-metal host itself, or any other
 * machine) that nspawnmgr does not provision or control — see HostService and the /admin/hosts page.
 */
public enum ContainerKind {
    MANAGED, EXTERNAL
}
