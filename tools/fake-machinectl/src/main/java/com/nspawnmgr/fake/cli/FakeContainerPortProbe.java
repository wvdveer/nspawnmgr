package com.nspawnmgr.fake.cli;

import com.nspawnmgr.cli.ContainerPortProbe;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

/**
 * Dev stand-in: there's no real network route to FakeContainerCliExecutor's synthetic 10.0.3.x
 * addresses on a Windows dev box, so this can't do a real connect. Always reports the port open so
 * the enable-access flow stays fully exercisable in the dev stack.
 */
@Component
@Profile("dev")
public class FakeContainerPortProbe implements ContainerPortProbe {

    @Override
    public boolean isOpen(String host, int port) {
        return true;
    }
}
