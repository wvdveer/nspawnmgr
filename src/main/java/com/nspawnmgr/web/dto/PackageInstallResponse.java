package com.nspawnmgr.web.dto;

public record PackageInstallResponse(int exitCode, String stdout, String stderr) {
}
