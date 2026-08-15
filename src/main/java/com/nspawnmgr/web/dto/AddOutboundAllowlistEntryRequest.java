package com.nspawnmgr.web.dto;

import com.nspawnmgr.domain.PortMappingProtocol;
import javax.validation.constraints.Max;
import javax.validation.constraints.Min;
import javax.validation.constraints.NotNull;
import javax.validation.constraints.Pattern;

public record AddOutboundAllowlistEntryRequest(
        @NotNull
        @Pattern(regexp = "^((25[0-5]|2[0-4][0-9]|[01]?[0-9][0-9]?)\\.){3}(25[0-5]|2[0-4][0-9]|[01]?[0-9][0-9]?)$",
                message = "must be a literal IPv4 address")
        String destinationHost,
        @Min(1) @Max(65535) int destinationPort,
        @NotNull PortMappingProtocol protocol
) {
}
