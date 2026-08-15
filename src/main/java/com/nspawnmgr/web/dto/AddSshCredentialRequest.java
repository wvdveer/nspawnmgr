package com.nspawnmgr.web.dto;

import javax.validation.constraints.NotBlank;

/** Manually records SSH access details nspawnmgr already has (but never itself provisioned) for a
 *  container - see ProvisioningService.recordManualSshCredential's own javadoc for why this exists. */
public record AddSshCredentialRequest(
        @NotBlank String accountName,
        @NotBlank String privateKeyPem
) {
}
