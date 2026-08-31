package com.nspawnmgr.web.dto;

/** The connecting user's own home directory (a real, resolved absolute path - e.g.
 *  {@code /home/frank}), for the Files page to show as the browsing root instead of guessing at
 *  what it might be. */
public record ConnectSftpResponse(String homeDirectory) {
}
