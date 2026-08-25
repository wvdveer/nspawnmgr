package com.nspawnmgr.web;

import com.nspawnmgr.domain.AuditAction;
import com.nspawnmgr.domain.AuditTargetType;
import com.nspawnmgr.domain.ContainerBackend;
import com.nspawnmgr.domain.MinimalTemplateFlavor;
import com.nspawnmgr.domain.PackageManager;
import com.nspawnmgr.domain.PrivateUsersMode;
import com.nspawnmgr.domain.Template;
import com.nspawnmgr.domain.TemplateFeatureState;
import com.nspawnmgr.security.CurrentUserProvider;
import com.nspawnmgr.service.AuditLogService;
import com.nspawnmgr.service.TemplateService;
import com.nspawnmgr.web.dto.ConvertTemplateRequest;
import com.nspawnmgr.web.dto.CreateMinimalTemplateRequest;
import com.nspawnmgr.web.dto.CreateOrUpdateTemplateRequest;
import com.nspawnmgr.web.dto.CreatePodmanPullRequest;
import com.nspawnmgr.web.dto.TemplateDetailResponse;
import javax.validation.Valid;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
public class AdminTemplateApiController {

    private final TemplateService templateService;
    private final AuditLogService auditLogService;
    private final CurrentUserProvider currentUserProvider;

    public AdminTemplateApiController(TemplateService templateService, AuditLogService auditLogService,
                                       CurrentUserProvider currentUserProvider) {
        this.templateService = templateService;
        this.auditLogService = auditLogService;
        this.currentUserProvider = currentUserProvider;
    }

    @GetMapping("/api/admin/templates")
    public List<TemplateDetailResponse> list() {
        // Self-hosted installs bake/copy debian-minimal.tar.gz onto disk at .deb-install time,
        // before nspawnmgr's own database exists yet to record a Template row for it (see
        // nspawnmgr-bootstrap-app-machine.sh) - backfill that row here, the first place an admin
        // would look for it, rather than requiring them to notice it's "missing" and re-click
        // "Set up debian-minimal" (which would fail outright: the tarball already exists).
        templateService.registerExistingMinimal(MinimalTemplateFlavor.DEBIAN);
        return templateService.listAll().stream().map(this::toResponse).toList();
    }

    @PostMapping("/api/admin/templates")
    public TemplateDetailResponse create(@Valid @RequestBody CreateOrUpdateTemplateRequest request) {
        Template template = templateService.create(toFields(request));
        auditLogService.log(currentUserProvider.get(), AuditAction.CREATED, AuditTargetType.TEMPLATE,
                template.getId(), template.getName(), null);
        return toResponse(template);
    }

    @PutMapping("/api/admin/templates/{id}")
    public TemplateDetailResponse update(@PathVariable Long id, @Valid @RequestBody CreateOrUpdateTemplateRequest request) {
        Template template = templateService.update(id, toFields(request));
        auditLogService.log(currentUserProvider.get(), AuditAction.UPDATED, AuditTargetType.TEMPLATE,
                template.getId(), template.getName(), null);
        return toResponse(template);
    }

    private TemplateService.TemplateFields toFields(CreateOrUpdateTemplateRequest request) {
        return new TemplateService.TemplateFields(request.name(), request.description(), request.sourcePath(),
                parseBackend(request.backend()), parsePackageManager(request.packageManager()),
                request.installSshCommand(), parseTemplateFeatureState(request.sshState()), request.sshPreDownloadPackages(),
                request.installXrdpCommand(), parseTemplateFeatureState(request.rdpState()), request.xrdpPreDownloadPackages(),
                request.installVncCommand(), parseTemplateFeatureState(request.vncState()), request.vncPreDownloadPackages(), request.vncXstartupTemplate(),
                request.vncProcessNamePattern(),
                request.installGnomeCommand(), request.gnomePreDownloadPackages(),
                request.installKdeStandardCommand(), request.kdeStandardPreDownloadPackages(),
                request.installXfce4Command(), request.xfce4PreDownloadPackages(),
                parsePrivateUsersMode(request.privateUsersMode()), request.active());
    }

    @GetMapping("/api/admin/templates/available-source-files")
    public List<String> availableSourceFiles(@RequestParam String backend) {
        return templateService.listAvailableSourceFiles(parseBackend(backend));
    }

    @PostMapping("/api/admin/templates/create-debian-minimal")
    public TemplateDetailResponse createDebianMinimal(@RequestBody(required = false) CreateMinimalTemplateRequest request) {
        return createMinimal(MinimalTemplateFlavor.DEBIAN, request);
    }

    @PostMapping("/api/admin/templates/create-fedora-minimal")
    public TemplateDetailResponse createFedoraMinimal(@RequestBody(required = false) CreateMinimalTemplateRequest request) {
        return createMinimal(MinimalTemplateFlavor.FEDORA, request);
    }

    @PostMapping("/api/admin/templates/create-arch-minimal")
    public TemplateDetailResponse createArchMinimal(@RequestBody(required = false) CreateMinimalTemplateRequest request) {
        return createMinimal(MinimalTemplateFlavor.ARCH, request);
    }

    private TemplateDetailResponse createMinimal(MinimalTemplateFlavor flavor, CreateMinimalTemplateRequest request) {
        String submitted = request != null ? request.sudoPassword() : null;
        char[] password = (submitted != null && !submitted.isBlank()) ? submitted.toCharArray() : null;
        Template template = templateService.createMinimalDefault(flavor, password);
        auditLogService.log(currentUserProvider.get(), AuditAction.CREATED, AuditTargetType.TEMPLATE,
                template.getId(), template.getName(), "via Set up " + flavor.templateName());
        return toResponse(template);
    }

    @PostMapping("/api/admin/templates/create-podman-pull")
    public TemplateDetailResponse createPodmanPull(@Valid @RequestBody CreatePodmanPullRequest request) {
        char[] password = toCharArray(request.sudoPassword());
        Template template = templateService.createFromPodmanPull(request.pullReference(), password);
        auditLogService.log(currentUserProvider.get(), AuditAction.CREATED, AuditTargetType.TEMPLATE,
                template.getId(), template.getName(), "via New Pod (podman pull " + request.pullReference() + ")");
        return toResponse(template);
    }

    @PostMapping("/api/admin/templates/{id}/convert-to-podman")
    public TemplateDetailResponse convertToPodman(@PathVariable Long id, @Valid @RequestBody ConvertTemplateRequest request) {
        char[] password = toCharArray(request.sudoPassword());
        Template template = templateService.convertToPodman(id, request.newName(), password);
        auditLogService.log(currentUserProvider.get(), AuditAction.CREATED, AuditTargetType.TEMPLATE,
                template.getId(), template.getName(), "via Create podman template (from template " + id + ")");
        return toResponse(template);
    }

    @PostMapping("/api/admin/templates/{id}/convert-to-nspawn")
    public TemplateDetailResponse convertToNspawn(@PathVariable Long id, @Valid @RequestBody ConvertTemplateRequest request) {
        char[] password = toCharArray(request.sudoPassword());
        Template template = templateService.convertToNspawn(id, request.newName(), password);
        auditLogService.log(currentUserProvider.get(), AuditAction.CREATED, AuditTargetType.TEMPLATE,
                template.getId(), template.getName(), "via Create nspawn template (from template " + id + ")");
        return toResponse(template);
    }

    /** null unless a non-blank password was actually submitted - shared helper for the three
     *  podman-related endpoints above, same shape {@link #createMinimal} already uses inline. */
    private static char[] toCharArray(String sudoPassword) {
        return (sudoPassword != null && !sudoPassword.isBlank()) ? sudoPassword.toCharArray() : null;
    }

    @PostMapping("/api/admin/templates/{id}/deactivate")
    public void deactivate(@PathVariable Long id) {
        templateService.setActive(id, false);
        Template template = templateService.getById(id);
        auditLogService.log(currentUserProvider.get(), AuditAction.DEACTIVATED, AuditTargetType.TEMPLATE,
                template.getId(), template.getName(), null);
    }

    @PostMapping("/api/admin/templates/{id}/reactivate")
    public void reactivate(@PathVariable Long id) {
        templateService.setActive(id, true);
        Template template = templateService.getById(id);
        auditLogService.log(currentUserProvider.get(), AuditAction.ACTIVATED, AuditTargetType.TEMPLATE,
                template.getId(), template.getName(), null);
    }

    @DeleteMapping("/api/admin/templates/{id}")
    public void delete(@PathVariable Long id) {
        Template template = templateService.getById(id);
        templateService.delete(id);
        auditLogService.log(currentUserProvider.get(), AuditAction.DELETED, AuditTargetType.TEMPLATE,
                template.getId(), template.getName(), null);
    }

    private ContainerBackend parseBackend(String value) {
        try {
            return ContainerBackend.valueOf(value.toUpperCase());
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("Invalid backend: " + value);
        }
    }

    /** Blank/null means "not applicable" (see Template.packageManager's own comment - podman
     *  templates, unlike every nspawn one, may genuinely have none) - same blank-means-null shape
     *  as {@link #parsePrivateUsersMode} below, unlike {@link #parseBackend} which always requires
     *  a real value. */
    private PackageManager parsePackageManager(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        try {
            return PackageManager.valueOf(value.toUpperCase());
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("Invalid package manager: " + value);
        }
    }

    /** Blank/null defaults to CAPABLE, matching Template.sshState/rdpState/vncState's own Java field
     *  default - lets external API callers (e.g. the web-test-harness fixtures) omit this rather
     *  than requiring every caller to specify it explicitly, same leniency {@link #parsePackageManager}
     *  already extends to blank input, just with a real default instead of null (these three columns
     *  are NOT NULL, unlike packageManager). */
    private TemplateFeatureState parseTemplateFeatureState(String value) {
        if (value == null || value.isBlank()) {
            return TemplateFeatureState.CAPABLE;
        }
        try {
            return TemplateFeatureState.valueOf(value.toUpperCase());
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("Invalid state: " + value);
        }
    }

    /** Unlike backend/packageManager, blank/null means "no override" - leaves systemd-nspawn's own
     *  PrivateUsers= default untouched (see Template.privateUsersMode/PrivateUsersMode). */
    private PrivateUsersMode parsePrivateUsersMode(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        try {
            return PrivateUsersMode.valueOf(value.toUpperCase());
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("Invalid PrivateUsers mode: " + value);
        }
    }

    private TemplateDetailResponse toResponse(Template t) {
        return new TemplateDetailResponse(t.getId(), t.getName(), t.getDescription(), t.getSourcePath(),
                t.getBackend(), t.getPackageManager(),
                t.getInstallSshCommand(), t.getSshState(), t.getSshPreDownloadPackages(),
                t.getInstallXrdpCommand(), t.getRdpState(), t.getXrdpPreDownloadPackages(),
                t.getInstallVncCommand(), t.getVncState(), t.getVncPreDownloadPackages(), t.getVncXstartupTemplate(),
                t.getVncProcessNamePattern(),
                t.getInstallGnomeCommand(), t.getGnomePreDownloadPackages(),
                t.getInstallKdeStandardCommand(), t.getKdeStandardPreDownloadPackages(),
                t.getInstallXfce4Command(), t.getXfce4PreDownloadPackages(),
                t.getPrivateUsersMode(), t.isActive());
    }
}
