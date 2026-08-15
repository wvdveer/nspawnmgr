package com.nspawnmgr.web.dto;

/** Generic body for network-diagnostics "Fix" endpoints - each requires a fresh sudo password. */
public record SudoPasswordRequest(String sudoPassword) {
}
