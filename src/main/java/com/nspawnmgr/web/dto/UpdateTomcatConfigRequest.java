package com.nspawnmgr.web.dto;

public record UpdateTomcatConfigRequest(
        int httpPort, boolean httpsEnabled, Integer httpsPort, String certificateFile, String certificateKeyFile) {
}
