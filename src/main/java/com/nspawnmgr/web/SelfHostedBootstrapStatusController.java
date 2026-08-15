package com.nspawnmgr.web;

import com.nspawnmgr.service.SelfHostedBootstrapStatus;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Polled by the DB setup wizard's own progress page (see {@code DatabaseSetupWizardServlet} in the
 * root-wizard module) while it waits for {@link SelfHostedBootstrapStatus}-tracked work
 * (ContainerDiscoveryService's self-hosted-infrastructure reconciliation) to finish — deliberately
 * unauthenticated, same reasoning as {@code /login-required}: no admin has ever logged in yet at
 * the point the wizard needs this. Read-only, exposes nothing more sensitive than "is setup done"
 * plus a handful of already-non-secret progress lines (SelfHostedBootstrapStatus itself never logs
 * generated credentials).
 *
 * <p>Mirrors the wizard's own {@code respondProvisionLog}'s header/body convention
 * ({@code X-Provision-Status} + a plain-text log body) so its polling JS can be reused almost
 * as-is — {@code pending}/{@code done} here rather than the wizard's own
 * {@code pending}/{@code success}/{@code error}, since this never hard-fails: a stuck step just
 * keeps retrying on {@link com.nspawnmgr.service.ContainerDiscoveryService}'s own recurring
 * schedule, logged at WARN server-side, not surfaced here as an "error" state.
 */
@RestController
public class SelfHostedBootstrapStatusController {

    private final SelfHostedBootstrapStatus bootstrapStatus;

    public SelfHostedBootstrapStatusController(SelfHostedBootstrapStatus bootstrapStatus) {
        this.bootstrapStatus = bootstrapStatus;
    }

    @GetMapping("/api/bootstrap/self-hosted-infra-status")
    public ResponseEntity<String> status() {
        return ResponseEntity.ok()
                .contentType(MediaType.TEXT_PLAIN)
                .header(HttpHeaders.CACHE_CONTROL, "no-store")
                .header("X-Provision-Status", bootstrapStatus.isDone() ? "done" : "pending")
                .body(bootstrapStatus.log());
    }
}
