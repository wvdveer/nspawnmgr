package com.nspawnmgr.web.dto;

import com.nspawnmgr.domain.ContainerBackend;
import com.nspawnmgr.domain.PackageManager;
import com.nspawnmgr.domain.PrivateUsersMode;
import com.nspawnmgr.domain.TemplateFeatureState;

public record TemplateDetailResponse(Long id, String name, String description, String sourcePath,
                                       ContainerBackend backend, PackageManager packageManager,
                                       String installSshCommand, TemplateFeatureState sshState, String sshPreDownloadPackages,
                                       String installXrdpCommand, TemplateFeatureState rdpState, String xrdpPreDownloadPackages,
                                       String installVncCommand, TemplateFeatureState vncState, String vncPreDownloadPackages,
                                       String vncXstartupTemplate, String vncProcessNamePattern,
                                       String installGnomeCommand, String gnomePreDownloadPackages,
                                       String installKdeStandardCommand, String kdeStandardPreDownloadPackages,
                                       String installXfce4Command, String xfce4PreDownloadPackages,
                                       PrivateUsersMode privateUsersMode, boolean active) {
}
