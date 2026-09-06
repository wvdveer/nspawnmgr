package com.nspawnmgr.web.dto;

import javax.validation.constraints.NotEmpty;

import java.util.List;

public record ReorderKeySequencesRequest(@NotEmpty List<Long> orderedIds) {
}
