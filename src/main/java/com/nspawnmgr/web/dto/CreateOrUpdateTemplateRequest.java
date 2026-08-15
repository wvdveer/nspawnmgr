package com.nspawnmgr.web.dto;

import javax.validation.constraints.NotBlank;

public record CreateOrUpdateTemplateRequest(@NotBlank String name, String description, @NotBlank String sourcePath,
                                              @NotBlank String backend, @NotBlank String packageManager,
                                              String installSshCommand, boolean sshPreinstalled, String sshPreDownloadPackages,
                                              String installXrdpCommand, boolean rdpCapable, String xrdpPreDownloadPackages,
                                              String installVncCommand, boolean vncCapable, String vncPreDownloadPackages,
                                              String vncXstartupTemplate, String vncProcessNamePattern,
                                              String installGnomeCommand, String gnomePreDownloadPackages,
                                              String installKdeStandardCommand, String kdeStandardPreDownloadPackages,
                                              String installXfce4Command, String xfce4PreDownloadPackages,
                                              String privateUsersMode, boolean active) {
}
