package com.nspawnmgr.web.dto;

import java.util.Map;

public record UpdateGuacamolePropertiesRequest(String databaseType, Map<String, String> values) {
}
