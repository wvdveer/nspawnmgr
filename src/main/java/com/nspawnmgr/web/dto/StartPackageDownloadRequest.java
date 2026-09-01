package com.nspawnmgr.web.dto;

import javax.validation.constraints.NotBlank;
import javax.validation.constraints.Pattern;
import javax.validation.constraints.Size;

public record StartPackageDownloadRequest(
        @NotBlank String packageManager,
        @NotBlank @Pattern(regexp = "^https?://.+", message = "{validation.url.mustBeHttp}") String url,
        @Size(max = 500) String description
) {
}
