package com.nspawnmgr.web.dto;

import javax.validation.constraints.NotBlank;

/** Credentials typed into the Files page's own "Connect" prompt for a QEMU VM or an EXTERNAL host
 *  - see {@code GuestSftpSessionStore}'s own javadoc for why this is never persisted. */
public record ConnectSftpRequest(
        @NotBlank String username,
        @NotBlank String password
) {
}
