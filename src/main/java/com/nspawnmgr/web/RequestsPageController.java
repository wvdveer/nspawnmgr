package com.nspawnmgr.web;

import com.nspawnmgr.domain.ContainerState;
import com.nspawnmgr.domain.Role;
import com.nspawnmgr.domain.User;
import com.nspawnmgr.repository.ContainerRepository;
import com.nspawnmgr.security.CurrentUserProvider;
import com.nspawnmgr.service.ContainerUserService;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;

/**
 * UI redesign Phase 4: replaces the two formerly-separate admin-only pages this "Requests" nav
 * item used to cross-link to each other from (AdminProvisioningPageController's pending
 * container-creation requests, AdminContainerUserRequestPageController's pending in-container
 * user requests) with one combined page under a single destination - two distinct domains/tables,
 * stacked, not a deeper data-level merge (see requests.html).
 *
 * <p>Phase 6 refinement: this page (and its nav item, see app-shell.html) is only reachable while
 * {@code sshApprovalRequired} is true - once a stored sudo secret is configured, nothing ever
 * lands in PENDING_APPROVAL, so there's nothing to show anyone. While it's true, non-admins can
 * reach it too, scoped to their own requests (own container-creation requests, own container-user
 * requests) - they can deny those but not approve them, since approving needs the sudo password
 * that's deliberately only ever asked of an admin. Admins still see every pending request from
 * every user.
 */
@Controller
public class RequestsPageController {

    private final ContainerRepository containerRepository;
    private final ContainerUserService containerUserService;
    private final CurrentUserProvider currentUserProvider;

    public RequestsPageController(ContainerRepository containerRepository, ContainerUserService containerUserService,
                                   CurrentUserProvider currentUserProvider) {
        this.containerRepository = containerRepository;
        this.containerUserService = containerUserService;
        this.currentUserProvider = currentUserProvider;
    }

    @GetMapping("/requests")
    public String requests(Model model) {
        User currentUser = currentUserProvider.get();
        boolean isAdmin = currentUser.getRole() == Role.ADMIN;

        var pendingContainers = containerRepository.findByStateWithOwnerAndTemplate(ContainerState.PENDING_APPROVAL);
        var pendingUserRequests = containerUserService.listPendingRequests();
        if (!isAdmin) {
            pendingContainers = pendingContainers.stream()
                    .filter(c -> c.getOwner().getId().equals(currentUser.getId())).toList();
            pendingUserRequests = pendingUserRequests.stream()
                    .filter(r -> r.getRequestedBy().getId().equals(currentUser.getId())).toList();
        }
        model.addAttribute("pendingContainers", pendingContainers);
        model.addAttribute("pendingUserRequests", pendingUserRequests);
        // sshApprovalRequired itself comes from GlobalModelAttributes (every page needs it for the
        // nav item, not just this one) - requests.html also reads it directly for the sudo-password
        // field gate.
        model.addAttribute("currentUser", currentUser);
        model.addAttribute("isAdmin", isAdmin);
        return "requests";
    }
}
