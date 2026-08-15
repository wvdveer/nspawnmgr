package com.nspawnmgr.fakeguac;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "fake-guacamole")
public record FakeGuacamoleProperties(String adminUsername, String adminPassword, String dataSource) {
}
