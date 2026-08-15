package com.nspawnmgr.service;

import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * Browser-visible progress for {@link ContainerDiscoveryService#reconcileSelfHostedInfrastructureNow()}'s
 * recurring work — mirrors the DB setup wizard's own {@code provisionLog}/{@code PROVISION_LOCK}
 * pattern (see {@code DatabaseSetupWizardServlet} in the root-wizard module), so that wizard's
 * progress page can keep polling and showing real progress through this phase too, rather than
 * redirecting the moment Tomcat merely starts responding.
 *
 * <p>{@code done} latches true the first time a pass finds nothing left to do for either
 * self-hosted machine (template already linked, SSH already provisioned, auto-start/requires
 * already set), and stays true from then on — it's a one-time "the wizard's own polling can stop
 * watching now" signal, not a live health indicator. {@link ContainerDiscoveryService}'s scheduled
 * pass keeps re-checking indefinitely regardless (cheap once steady-state, same posture as {@link
 * ContainerDnsSyncService}), so any later drift is still corrected — just without being reflected
 * back in this flag, which nothing needs once the wizard page is gone.
 */
@Component
public class SelfHostedBootstrapStatus {

    private final Object lock = new Object();
    private final List<String> lines = new ArrayList<>();
    private boolean done;

    public void log(String line) {
        synchronized (lock) {
            lines.add(line);
        }
    }

    public void markDone() {
        synchronized (lock) {
            done = true;
        }
    }

    public String log() {
        synchronized (lock) {
            return String.join("\n", lines);
        }
    }

    public boolean isDone() {
        synchronized (lock) {
            return done;
        }
    }
}
