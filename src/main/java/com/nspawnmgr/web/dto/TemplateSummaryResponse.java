package com.nspawnmgr.web.dto;

import com.nspawnmgr.domain.TemplateFeatureState;

public record TemplateSummaryResponse(Long id, String name, TemplateFeatureState rdpState) {
}
