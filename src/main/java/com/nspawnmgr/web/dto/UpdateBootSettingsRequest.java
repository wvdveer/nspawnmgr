package com.nspawnmgr.web.dto;

/** {@code requiresContainerName} is only meaningful when {@code autoStart} is true - see
 *  ContainerLifecycleService.setBootSettings's own javadoc. */
public record UpdateBootSettingsRequest(boolean autoStart, String requiresContainerName) {
}
