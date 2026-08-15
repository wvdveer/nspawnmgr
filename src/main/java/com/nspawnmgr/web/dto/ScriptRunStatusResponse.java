package com.nspawnmgr.web.dto;

import java.util.List;

public record ScriptRunStatusResponse(String runId, String state, Integer exitCode, List<OutputLineResponse> lines) {
}
