package com.nspawnmgr.web.dto;

import javax.validation.constraints.NotBlank;

public record CreateOrUpdateTemplateRequest(@NotBlank String name, String description, @NotBlank String sourcePath,
                                              @NotBlank String backend, String packageManager,
                                              String installSshCommand, String sshState, String sshPreDownloadPackages,
                                              String installXrdpCommand, String rdpState, String xrdpPreDownloadPackages,
                                              String installVncCommand, String vncState, String vncPreDownloadPackages,
                                              String vncXstartupTemplate, String vncProcessNamePattern,
                                              String installGnomeCommand, String gnomePreDownloadPackages,
                                              String installKdeStandardCommand, String kdeStandardPreDownloadPackages,
                                              String installXfce4Command, String xfce4PreDownloadPackages,
                                              String privateUsersMode, boolean active) {
}
