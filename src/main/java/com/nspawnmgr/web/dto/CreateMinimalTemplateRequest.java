package com.nspawnmgr.web.dto;

/** {@code sudoPassword} is only required in admin-approval mode (no stored sudo secret configured). */
public record CreateMinimalTemplateRequest(String sudoPassword) {
}
