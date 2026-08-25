package com.nspawnmgr.web.dto;

import com.nspawnmgr.domain.QemuCpuModel;
import com.nspawnmgr.domain.QemuNicModel;
import com.nspawnmgr.domain.QemuPointerDevice;

import javax.validation.constraints.Max;
import javax.validation.constraints.Min;
import javax.validation.constraints.NotBlank;
import javax.validation.constraints.Pattern;
import javax.validation.constraints.Size;

/**
 * QEMU still can't reuse {@link CreateContainerRequest}'s Template-taking shape (a genuinely
 * separate creation flow, see ContainerApiController's own {@code POST /api/containers/qemu}), but
 * unlike QEMU v1 (from-scratch + ISO only), a VM can now optionally be cloned from an existing QEMU
 * {@link com.nspawnmgr.domain.Template} instead of getting a fresh empty disk - exactly one of
 * {@code diskSizeGb} (empty-disk mode) or {@code templateId} (clone mode) must be set, validated in
 * {@code ContainerApiController.createQemu} rather than here (bean validation has no clean
 * "exactly one of" cross-field construct). {@code isoPackageId} stays independent of either mode -
 * mounting boot media is orthogonal to where the disk itself came from.
 */
public record CreateQemuVmRequest(
        @NotBlank @Pattern(regexp = "^[a-z0-9][a-z0-9-]{1,62}$", message = "lowercase alphanumeric and hyphens only") String name,
        @Min(1) @Max(2000) Integer diskSizeGb,
        /** A QEMU-backed Template id to clone the disk from, or null for empty-disk mode. */
        Long templateId,
        /** A CachedPackage id with packageManager=ISO, or null for no boot media. */
        Long isoPackageId,
        @Size(max = 500) String description,
        QemuCpuModel cpuModel,
        @Min(1) @Max(32) Integer cpuCount,
        @Min(128) @Max(131072) Integer memoryMb,
        QemuNicModel nicModel,
        QemuPointerDevice pointerDevice
) {
}
