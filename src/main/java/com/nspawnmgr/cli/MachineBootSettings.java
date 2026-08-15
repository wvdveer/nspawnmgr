package com.nspawnmgr.cli;

/**
 * A machine's own host-boot-time settings, queried live from the host rather than trusted from
 * nspawnmgr's own database — an admin may toggle these directly via {@code systemctl}, bypassing
 * the app entirely, so nspawnmgr must never assume its own last-known value is still correct. See
 * {@code ContainerCliExecutor#getBootSettings}.
 *
 * @param autoStart          whether this machine is enabled to start automatically when the HOST
 *                           itself boots ({@code systemctl is-enabled systemd-nspawn@<name>.service}).
 * @param requiresMachineName the other machine this one currently requires already started before
 *                            it starts, or {@code null} if none is set.
 */
public record MachineBootSettings(boolean autoStart, String requiresMachineName) {
}
