package com.nspawnmgr.web.dto;

import javax.validation.constraints.NotNull;

import com.nspawnmgr.domain.KeystrokeButtonPanelPlacement;

public record UpdateKeystrokeButtonPanelPlacementRequest(@NotNull KeystrokeButtonPanelPlacement placement) {
}
