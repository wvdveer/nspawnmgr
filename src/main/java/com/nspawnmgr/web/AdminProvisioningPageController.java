package com.nspawnmgr.web;

import com.nspawnmgr.domain.ContainerState;
import com.nspawnmgr.repository.ContainerRepository;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;

@Controller
public class AdminProvisioningPageController {

    private final ContainerRepository containerRepository;

    public AdminProvisioningPageController(ContainerRepository containerRepository) {
        this.containerRepository = containerRepository;
    }

    @GetMapping("/admin/containers/pending")
    public String pending(Model model) {
        model.addAttribute("pending", containerRepository.findByStateWithOwnerAndTemplate(ContainerState.PENDING_APPROVAL));
        return "admin/pending-approvals";
    }
}
