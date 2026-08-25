package com.nspawnmgr.web;

import com.nspawnmgr.security.CurrentUserProvider;
import com.nspawnmgr.service.NetworkDiagnosticsService;
import com.nspawnmgr.web.dto.SudoPasswordRequest;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
public class AdminNetworkDiagnosticsApiController {

    private final NetworkDiagnosticsService diagnosticsService;
    private final CurrentUserProvider currentUserProvider;

    public AdminNetworkDiagnosticsApiController(NetworkDiagnosticsService diagnosticsService,
                                                  CurrentUserProvider currentUserProvider) {
        this.diagnosticsService = diagnosticsService;
        this.currentUserProvider = currentUserProvider;
    }

    @PostMapping("/api/admin/network-diagnostics/run")
    public List<NetworkDiagnosticsService.DiagnosticCheck> run() {
        return diagnosticsService.runChecks();
    }

    @PostMapping("/api/admin/network-diagnostics/fix/networkd")
    public NetworkDiagnosticsService.DiagnosticCheck fixNetworkd(@RequestBody SudoPasswordRequest request) {
        return diagnosticsService.fixNetworkd(currentUserProvider.get(), toCharArray(request));
    }

    @PostMapping("/api/admin/network-diagnostics/fix/host-address")
    public NetworkDiagnosticsService.DiagnosticCheck fixHostAddress(@RequestBody SudoPasswordRequest request) {
        return diagnosticsService.fixHostAddress(currentUserProvider.get(), toCharArray(request));
    }

    @PostMapping("/api/admin/network-diagnostics/fix/podman")
    public NetworkDiagnosticsService.DiagnosticCheck fixPodman(@RequestBody SudoPasswordRequest request) {
        return diagnosticsService.fixPodman(currentUserProvider.get(), toCharArray(request));
    }

    @PostMapping("/api/admin/network-diagnostics/fix/qemu")
    public NetworkDiagnosticsService.DiagnosticCheck fixQemu(@RequestBody SudoPasswordRequest request) {
        return diagnosticsService.fixQemu(currentUserProvider.get(), toCharArray(request));
    }

    @PostMapping("/api/admin/network-diagnostics/fix/podman-network")
    public NetworkDiagnosticsService.DiagnosticCheck fixPodmanNetwork(@RequestBody SudoPasswordRequest request) {
        return diagnosticsService.fixPodmanNetwork(currentUserProvider.get(), toCharArray(request));
    }

    @PostMapping("/api/admin/network-diagnostics/fix/qemu-bridge")
    public NetworkDiagnosticsService.DiagnosticCheck fixQemuBridge(@RequestBody SudoPasswordRequest request) {
        return diagnosticsService.fixQemuBridge(currentUserProvider.get(), toCharArray(request));
    }

    /** null outside admin-approval mode - the page only sends/renders a password field then. */
    private static char[] toCharArray(SudoPasswordRequest request) {
        return request.sudoPassword() != null ? request.sudoPassword().toCharArray() : null;
    }
}
