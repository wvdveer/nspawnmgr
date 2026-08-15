package com.nspawnmgr.web.dto;

import java.util.List;

public record ScriptRunResponse(Long scriptId, int exitCode, List<OutputLineResponse> lines) {
}
