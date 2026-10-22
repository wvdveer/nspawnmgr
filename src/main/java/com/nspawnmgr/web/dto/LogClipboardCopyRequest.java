package com.nspawnmgr.web.dto;

/** {@code text} is truncated server-side by ContainerApiController.logClipboardCopy before it's
 *  written to the audit log - no validation annotation here, since over-length input should just be
 *  shortened, not rejected outright (this fires automatically from session.js's onclipboard handler,
 *  with no user-facing error path to show a 400 against). */
public record LogClipboardCopyRequest(String text) {
}
