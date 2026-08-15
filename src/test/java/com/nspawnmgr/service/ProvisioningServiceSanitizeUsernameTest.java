package com.nspawnmgr.service;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Focused on ProvisioningService.sanitizeUsername (package-private test seam) - used to derive a
 * container's initial primary account name from its owner's own login username. The rest of
 * ProvisioningService's provisioning flow is exercised live/via dev-stack, not unit tests, since it
 * shells out through ContainerCliExecutor at nearly every step.
 */
class ProvisioningServiceSanitizeUsernameTest {

    @Test
    void leavesAnAlreadyValidUsernameUntouched() {
        assertThat(ProvisioningService.sanitizeUsername("ward")).isEqualTo("ward");
    }

    @Test
    void lowercasesAndStripsDisallowedCharacters() {
        assertThat(ProvisioningService.sanitizeUsername("Ward.VanderVeer")).isEqualTo("wardvanderveer");
    }

    @Test
    void prependsUnderscoreWhenTheResultWouldStartWithADigit() {
        assertThat(ProvisioningService.sanitizeUsername("123ward")).isEqualTo("_123ward");
    }

    @Test
    void truncatesToThirtyTwoCharacters() {
        String longName = "a".repeat(40);
        assertThat(ProvisioningService.sanitizeUsername(longName)).hasSize(32).isEqualTo("a".repeat(32));
    }

    @Test
    void returnsNullForNull() {
        assertThat(ProvisioningService.sanitizeUsername(null)).isNull();
    }

    @Test
    void returnsNullWhenNothingSurvivesSanitizing() {
        assertThat(ProvisioningService.sanitizeUsername("!@#$%^&*()")).isNull();
    }

    @Test
    void returnsNullForALeadingHyphenAfterSanitizing() {
        // Digit-prefix gets an underscore prepended (see above), but a leading hyphen still fails
        // USERNAME_PATTERN even after that treatment - not worth special-casing further, callers
        // just fall back to the fixed default in this rare case.
        assertThat(ProvisioningService.sanitizeUsername("-ward")).isNull();
    }
}
