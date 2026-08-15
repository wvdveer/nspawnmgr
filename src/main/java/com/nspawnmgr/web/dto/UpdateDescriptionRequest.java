package com.nspawnmgr.web.dto;

import javax.validation.constraints.Size;

public record UpdateDescriptionRequest(@Size(max = 500) String description) {
}
