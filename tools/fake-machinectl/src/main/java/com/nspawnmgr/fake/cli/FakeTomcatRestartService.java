package com.nspawnmgr.fake.cli;

import com.nspawnmgr.cli.TomcatRestartService;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

/** No-op stand-in for the dev profile — there's no real sudo account/systemd to restart. */
@Component
@Profile("dev")
public class FakeTomcatRestartService implements TomcatRestartService {

    @Override
    public void restart() {
        // No-op — lets the settings-page button's full click -> response -> logout flow be
        // exercised in the dev stack without a real Tomcat restart.
    }
}
