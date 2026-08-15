package com.nspawnmgr.service;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Focused on ProvisioningService.truncateErrorMessage (package-private test seam) - the rest of
 * ProvisioningService's provisioning flow is exercised live/via dev-stack, not unit tests, since it
 * shells out through ContainerCliExecutor at nearly every step.
 */
class ProvisioningServiceTruncateErrorMessageTest {

    @Test
    void leavesShortMessagesUntouched() {
        assertThat(ProvisioningService.truncateErrorMessage("short")).isEqualTo("short");
    }

    @Test
    void returnsNullAsIs() {
        assertThat(ProvisioningService.truncateErrorMessage(null)).isNull();
    }

    @Test
    void keepsTheTailWhereTheRealFailureReasonLives() {
        // Mirrors the real shape live on yoga: a huge "additional packages will be installed"
        // preamble, then the actual apt/dpkg failure reason right at the end.
        String preamble = "x".repeat(3000);
        String realFailure = "E: Sub-process /usr/bin/dpkg returned an error code (1)";
        String message = "Command failed inside container 'b3' (exit 100): " + preamble + " " + realFailure;

        String truncated = ProvisioningService.truncateErrorMessage(message);

        assertThat(truncated).hasSize(2000);
        assertThat(truncated).endsWith(realFailure);
        assertThat(truncated).startsWith("Command failed inside container 'b3' (exit 100):");
    }
}
