package com.nspawnmgr.web;

import com.nspawnmgr.cli.ContainerCliExecutor;
import com.nspawnmgr.domain.Container;
import com.nspawnmgr.domain.ContainerKind;
import com.nspawnmgr.domain.ContainerScript;
import com.nspawnmgr.domain.ContainerPamService;
import com.nspawnmgr.domain.CredentialType;
import com.nspawnmgr.domain.PackageManager;
import com.nspawnmgr.domain.PamAuthSource;
import com.nspawnmgr.domain.PamServiceCatalog;
import com.nspawnmgr.domain.User;
import com.nspawnmgr.repository.ContainerCredentialRepository;
import com.nspawnmgr.repository.ContainerRepository;
import com.nspawnmgr.repository.ContainerShareRepository;
import com.nspawnmgr.security.CurrentUserProvider;
import com.nspawnmgr.service.ContainerLifecycleService;
import com.nspawnmgr.service.ContainerPortMappingService;
import com.nspawnmgr.service.ContainerScriptService;
import com.nspawnmgr.service.PackageCacheService;
import com.nspawnmgr.service.PamCredentialAuthService;
import com.nspawnmgr.service.SettingsService;
import com.nspawnmgr.service.ShareService;
import com.nspawnmgr.service.TemplateService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;

import java.util.Set;
import java.util.TreeSet;
import java.util.stream.Collectors;

@Controller
public class ContainerPageController {

    private static final Logger log = LoggerFactory.getLogger(ContainerPageController.class);

    private final ContainerRepository containerRepository;
    private final TemplateService templateService;
    private final ShareService shareService;
    private final ContainerPortMappingService portMappingService;
    private final ContainerScriptService scriptService;
    private final ContainerShareRepository containerShareRepository;
    private final ContainerCredentialRepository containerCredentialRepository;
    private final ContainerLifecycleService lifecycleService;
    private final PackageCacheService packageCacheService;
    private final ContainerCliExecutor cliExecutor;
    private final CurrentUserProvider currentUserProvider;
    private final PamCredentialAuthService pamCredentialAuthService;
    private final SettingsService settingsService;

    public ContainerPageController(ContainerRepository containerRepository, TemplateService templateService,
                                    ShareService shareService, ContainerPortMappingService portMappingService,
                                    ContainerScriptService scriptService,
                                    ContainerShareRepository containerShareRepository,
                                    ContainerCredentialRepository containerCredentialRepository,
                                    ContainerLifecycleService lifecycleService,
                                    PackageCacheService packageCacheService,
                                    ContainerCliExecutor cliExecutor,
                                    CurrentUserProvider currentUserProvider,
                                    PamCredentialAuthService pamCredentialAuthService,
                                    SettingsService settingsService) {
        this.containerRepository = containerRepository;
        this.templateService = templateService;
        this.shareService = shareService;
        this.portMappingService = portMappingService;
        this.scriptService = scriptService;
        this.containerShareRepository = containerShareRepository;
        this.containerCredentialRepository = containerCredentialRepository;
        this.lifecycleService = lifecycleService;
        this.packageCacheService = packageCacheService;
        this.cliExecutor = cliExecutor;
        this.currentUserProvider = currentUserProvider;
        this.pamCredentialAuthService = pamCredentialAuthService;
        this.settingsService = settingsService;
    }

    @GetMapping("/")
    public String list(Model model) {
        User user = currentUserProvider.get();
        // Owner or shared-with only - previously every managed container was listed for every
        // user (see project_container_access_model.md's own 2026-07-21 history); reverted per
        // explicit request. Sorted by name (findManagedVisibleToUserOrderByName's own ORDER BY).
        model.addAttribute("containers", containerRepository.findManagedVisibleToUserOrderByName(user));
        model.addAttribute("currentUser", user);
        model.addAttribute("hasActiveTemplates", !templateService.listActive().isEmpty());
        return "containers/list";
    }

    @GetMapping("/containers/new")
    public String createForm(Model model) {
        model.addAttribute("templates", templateService.listActive());
        return "containers/create";
    }

    @GetMapping("/containers/{id}")
    public String detail(@PathVariable Long id, Model model) {
        Container container = requireVisible(id);
        User user = currentUserProvider.get();
        model.addAttribute("container", container);
        model.addAttribute("shares", shareService.listShares(container));
        model.addAttribute("portMappings", portMappingService.listMappings(container));
        model.addAttribute("outboundAllowlist", lifecycleService.listOutboundAllowlist(container));
        model.addAttribute("hasSshCredential", containerCredentialRepository.findByContainerAndType(container, CredentialType.SSH_KEY).isPresent());
        model.addAttribute("hasRdpCredential", containerCredentialRepository.findByContainerAndType(container, CredentialType.RDP_PASSWORD).isPresent());
        model.addAttribute("hasVncCredential", containerCredentialRepository.findByContainerAndType(container, CredentialType.VNC_PASSWORD).isPresent());
        // Container.primaryAccountName is null for any container provisioned before this field
        // existed, or one nspawnmgr never itself provisioned - see its own javadoc for why that
        // means "use the global default" rather than "unset".
        model.addAttribute("effectivePrimaryAccountName", container.getPrimaryAccountName() != null
                ? container.getPrimaryAccountName() : settingsService.provisioningAdminAccountName());
        model.addAttribute("pamAuthSources", PamAuthSource.values());
        model.addAttribute("pamAuthServiceCatalog", new TreeSet<>(PamServiceCatalog.KNOWN_SERVICES));
        Set<String> pamAuthEnabledServices = pamCredentialAuthService.listServices(container).stream()
                .map(ContainerPamService::getServiceName)
                .collect(Collectors.toSet());
        model.addAttribute("pamAuthEnabledServices", pamAuthEnabledServices);
        if (container.getKind() == ContainerKind.MANAGED && container.effectivePackageManager() != null) {
            model.addAttribute("installablePackages", packageCacheService.listFor(container.effectivePackageManager()));
        }
        if (container.getKind() == ContainerKind.MANAGED && container.getTemplate() == null) {
            model.addAttribute("availablePackageManagers", PackageManager.forTemplates());
        }
        if (container.getKind() == ContainerKind.MANAGED) {
            model.addAttribute("availableIsos", packageCacheService.listFor(PackageManager.ISO));
            // Live host query, not nspawnmgr's own database - see ContainerCliExecutor
            // .getBootSettings's own javadoc for why. Best-effort: a transient SSH hiccup here
            // shouldn't break the whole page, just leave this one section showing a fallback.
            try {
                model.addAttribute("bootSettings", cliExecutor.getBootSettings(container.getName()));
            } catch (Exception e) {
                log.warn("Could not read boot settings for container '{}': {}", container.getName(), e.getMessage());
            }
            model.addAttribute("otherMachineNames", containerRepository.findManagedWithOwner().stream()
                    .map(Container::getName)
                    .filter(name -> !name.equals(container.getName()))
                    .sorted()
                    .toList());
        }
        model.addAttribute("currentUser", user);
        boolean canManageScripts = canManageScripts(container, user);
        model.addAttribute("canManageScripts", canManageScripts);
        if (canManageScripts) {
            model.addAttribute("scripts", scriptService.list(container));
        }
        return "containers/detail";
    }

    @GetMapping("/containers/{id}/scripts/new")
    public String newScript(@PathVariable Long id, Model model) {
        Container container = requireOwnedOrShared(id);
        model.addAttribute("container", container);
        model.addAttribute("script", null);
        model.addAttribute("currentUser", currentUserProvider.get());
        return "containers/script-form";
    }

    @GetMapping("/containers/{id}/scripts/{scriptId}")
    public String editScript(@PathVariable Long id, @PathVariable Long scriptId, Model model) {
        Container container = requireOwnedOrShared(id);
        ContainerScript script = scriptService.get(container, scriptId);
        model.addAttribute("container", container);
        model.addAttribute("script", script);
        model.addAttribute("currentUser", currentUserProvider.get());
        return "containers/script-form";
    }

    @GetMapping("/containers/{id}/session/{protocol}")
    public String session(@PathVariable Long id, @PathVariable String protocol, Model model) {
        Container container = requireOwnedOrSharedForConnect(id);
        model.addAttribute("container", container);
        model.addAttribute("protocol", protocol);
        return "containers/session";
    }

    /** Works whether or not the container is currently running - see ContainerFileBrowserService's
     *  own javadoc for why, unlike the machinectl-based SSH/RDP/VNC sessions above. */
    @GetMapping("/containers/{id}/files")
    public String files(@PathVariable Long id, Model model) {
        Container container = requireOwnedOrShared(id);
        model.addAttribute("container", container);
        model.addAttribute("currentUser", currentUserProvider.get());
        return "containers/files";
    }

    @GetMapping("/login-required")
    public String loginRequired() {
        return "login-required";
    }

    private Container requireVisible(Long id) {
        return containerRepository.findByIdWithTemplate(id)
                .orElseThrow(() -> new IllegalArgumentException("No such container: " + id));
    }

    /** Owner, or a user the container has been shared with — see ContainerApiController's twin. */
    private Container requireOwnedOrShared(Long id) {
        Container container = requireVisible(id);
        User user = currentUserProvider.get();
        if (!canManageScripts(container, user)) {
            throw new AccessDeniedException("Only the owner or a shared user may perform this action");
        }
        return container;
    }

    /** Owner, or a user the container has been shared with - same gate {@link #canManageScripts}
     *  uses, worded for the connect flow specifically (see ContainerApiController's twin, which
     *  guards the actual session-minting POST this page's own JS calls). */
    private Container requireOwnedOrSharedForConnect(Long id) {
        Container container = requireVisible(id);
        User user = currentUserProvider.get();
        if (!canManageScripts(container, user)) {
            throw new AccessDeniedException("This container has not been shared with you.");
        }
        return container;
    }

    private boolean canManageScripts(Container container, User user) {
        return container.getOwner().getId().equals(user.getId())
                || containerShareRepository.existsByContainerAndUser(container, user);
    }
}
