package com.nspawnmgr.web;

import com.nspawnmgr.domain.Role;
import com.nspawnmgr.security.CurrentUserProvider;
import com.nspawnmgr.service.LogService;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * Viewing is for every logged-in user (falls under SecurityConfig's ordinary
 * .anyRequest().authenticated() rule, deliberately NOT under /api/admin/**, which is ADMIN-only
 * wholesale) — deleting a rotated log is admin-only, enforced with a manual check here, the same
 * style ContainerApiController.requireOwned already uses for ownership checks.
 */
@RestController
public class LogsApiController {

    private final LogService logService;
    private final CurrentUserProvider currentUserProvider;

    public LogsApiController(LogService logService, CurrentUserProvider currentUserProvider) {
        this.logService = logService;
        this.currentUserProvider = currentUserProvider;
    }

    @GetMapping("/api/logs/tail")
    public List<String> tail(@RequestParam(defaultValue = "100") int lines) {
        return logService.tailCurrentLog(lines);
    }

    @GetMapping("/api/logs/current")
    public String current() {
        return logService.currentLogFull();
    }

    @GetMapping("/api/logs/current/download")
    public ResponseEntity<String> downloadCurrent() {
        return ResponseEntity.ok()
                .contentType(MediaType.TEXT_PLAIN)
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"catalina.out\"")
                .body(logService.currentLogFull());
    }

    @GetMapping("/api/logs/rotated")
    public List<String> rotated() {
        return logService.listRotatedLogs();
    }

    @GetMapping("/api/logs/rotated/{filename}")
    public String rotated(@PathVariable String filename) {
        return logService.readRotatedLog(filename);
    }

    @DeleteMapping("/api/logs/rotated/{filename}")
    public void deleteRotated(@PathVariable String filename) {
        if (currentUserProvider.get().getRole() != Role.ADMIN) {
            throw new AccessDeniedException("Only an admin may delete a log file");
        }
        logService.deleteRotatedLog(filename);
    }
}
