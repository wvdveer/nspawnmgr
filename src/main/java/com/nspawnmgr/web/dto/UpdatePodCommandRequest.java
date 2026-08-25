package com.nspawnmgr.web.dto;

import javax.validation.constraints.Size;

public record UpdatePodCommandRequest(@Size(max = 1000) String command) {
}
