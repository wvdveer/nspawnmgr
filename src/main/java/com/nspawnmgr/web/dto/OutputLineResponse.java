package com.nspawnmgr.web.dto;

import java.time.Instant;

public record OutputLineResponse(Instant timestamp, String source, String text) {
}
