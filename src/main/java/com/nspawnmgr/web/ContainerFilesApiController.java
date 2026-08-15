package com.nspawnmgr.web;

import com.nspawnmgr.domain.Container;
import com.nspawnmgr.domain.User;
import com.nspawnmgr.repository.ContainerRepository;
import com.nspawnmgr.repository.ContainerShareRepository;
import com.nspawnmgr.security.CurrentUserProvider;
import com.nspawnmgr.service.ContainerFileBrowserService;
import com.nspawnmgr.web.dto.FileEntryResponse;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Path;
import java.util.List;

/**
 * Browse/download/upload a container's rootfs - works whether or not the container is currently
 * running, unlike the machinectl-based SSH/RDP/VNC sessions (see ContainerFileBrowserService's own
 * javadoc). Gated the same as container scripts: browsing a container's filesystem is at least as
 * privileged as running an owner-authored script inside it, and a shared user already has
 * shell-equivalent access via SSH sessions anyway, so this isn't a privilege escalation.
 */
@RestController
public class ContainerFilesApiController {

    private final ContainerRepository containerRepository;
    private final ContainerShareRepository containerShareRepository;
    private final ContainerFileBrowserService fileBrowserService;
    private final CurrentUserProvider currentUserProvider;

    public ContainerFilesApiController(ContainerRepository containerRepository,
                                        ContainerShareRepository containerShareRepository,
                                        ContainerFileBrowserService fileBrowserService,
                                        CurrentUserProvider currentUserProvider) {
        this.containerRepository = containerRepository;
        this.containerShareRepository = containerShareRepository;
        this.fileBrowserService = fileBrowserService;
        this.currentUserProvider = currentUserProvider;
    }

    @GetMapping("/api/containers/{id}/files")
    public List<FileEntryResponse> list(@PathVariable Long id, @RequestParam(defaultValue = "") String path) {
        Container container = requireOwnedOrShared(id);
        return fileBrowserService.list(container, path).stream().map(FileEntryResponse::from).toList();
    }

    @GetMapping("/api/containers/{id}/files/download")
    public ResponseEntity<byte[]> download(@PathVariable Long id, @RequestParam String path) {
        Container container = requireOwnedOrShared(id);
        byte[] content = fileBrowserService.download(container, path);
        String filename = Path.of(path).getFileName().toString();
        return ResponseEntity.ok()
                .contentType(MediaType.APPLICATION_OCTET_STREAM)
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"" + filename + "\"")
                .body(content);
    }

    @PostMapping("/api/containers/{id}/files")
    public void upload(@PathVariable Long id, @RequestParam(defaultValue = "") String path,
                        @RequestParam("file") MultipartFile file) {
        Container container = requireOwnedOrShared(id);
        byte[] content;
        try {
            content = file.getBytes();
        } catch (IOException e) {
            throw new UncheckedIOException("Failed to read uploaded file", e);
        }
        fileBrowserService.upload(container, path, file.getOriginalFilename(), content);
    }

    /** Owner, or a user the container has been shared with — see ContainerApiController's twin. */
    private Container requireOwnedOrShared(Long id) {
        Container container = containerRepository.findById(id)
                .orElseThrow(() -> new IllegalArgumentException("No such container: " + id));
        User user = currentUserProvider.get();
        if (container.getOwner().getId().equals(user.getId())) {
            return container;
        }
        if (containerShareRepository.existsByContainerAndUser(container, user)) {
            return container;
        }
        throw new AccessDeniedException("Only the owner or a shared user may perform this action");
    }
}
