package com.nspawnmgr.web.dto;

/** Unlike ApproveProvisioningRequest, the password is optional here — only actually required when
 *  in admin-approval mode (see ContainerUserService.approveRequest). */
public record ApproveContainerUserActionRequest(String sudoPassword) {
}
