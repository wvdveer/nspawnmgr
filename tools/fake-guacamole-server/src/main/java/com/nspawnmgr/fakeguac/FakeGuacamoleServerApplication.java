package com.nspawnmgr.fakeguac;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;
import org.springframework.boot.web.servlet.support.SpringBootServletInitializer;

// Deployed as guacamole.war into the same external Tomcat as nspawnmgr.war (see
// tools/scripts/start-dev-stack.sh / start-real-stack.sh) — same pattern as
// com.nspawnmgr.NspawnmgrApplication.
@SpringBootApplication
@ConfigurationPropertiesScan
public class FakeGuacamoleServerApplication extends SpringBootServletInitializer {

    @Override
    protected SpringApplicationBuilder configure(SpringApplicationBuilder builder) {
        return builder.sources(FakeGuacamoleServerApplication.class);
    }

    public static void main(String[] args) {
        SpringApplication.run(FakeGuacamoleServerApplication.class, args);
    }
}
