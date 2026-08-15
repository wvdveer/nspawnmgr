package com.nspawnmgr.fake.cli;

import com.nspawnmgr.cli.ContainerCliExecutor;
import com.nspawnmgr.cli.ContainerReadinessChecker;
import com.nspawnmgr.domain.Container;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

/** Always ready on the first poll tick — there's no real sshd/xrdp to actually reach in dev mode. */
@Component
@Profile("dev")
public class FakeContainerReadinessChecker implements ContainerReadinessChecker {

    private final ContainerCliExecutor cliExecutor;

    public FakeContainerReadinessChecker(ContainerCliExecutor cliExecutor) {
        this.cliExecutor = cliExecutor;
    }

    @Override
    public Readiness check(Container container, String privateKeyPem, String accountName, boolean checkRdp) {
        return new Readiness(true, checkRdp, cliExecutor.getInternalAddress(container.getName()));
    }
}
