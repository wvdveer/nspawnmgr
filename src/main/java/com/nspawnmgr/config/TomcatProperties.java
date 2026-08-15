package com.nspawnmgr.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "nspawnmgr.tomcat")
public record TomcatProperties(String logDir) {
}
