package com.nspawnmgr.web.dto;

import com.nspawnmgr.domain.ContainerBackend;
import com.nspawnmgr.domain.PackageManager;
import com.nspawnmgr.domain.PrivateUsersMode;

public record TemplateDetailResponse(Long id, String name, String description, String sourcePath,
                                       ContainerBackend backend, PackageManager packageManager,
                                       String installSshCommand, boolean sshPreinstalled, String sshPreDownloadPackages,
                                       String installXrdpCommand, boolean rdpCapable, String xrdpPreDownloadPackages,
                                       String installVncCommand, boolean vncCapable, String vncPreDownloadPackages,
                                       String vncXstartupTemplate, String vncProcessNamePattern,
                                       String installGnomeCommand, String gnomePreDownloadPackages,
                                       String installKdeStandardCommand, String kdeStandardPreDownloadPackages,
                                       String installXfce4Command, String xfce4PreDownloadPackages,
                                       PrivateUsersMode privateUsersMode, boolean active) {
}
