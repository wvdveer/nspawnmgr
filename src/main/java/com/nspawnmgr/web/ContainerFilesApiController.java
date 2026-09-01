package com.nspawnmgr.web;

import com.nspawnmgr.domain.Container;
import com.nspawnmgr.domain.User;
import com.nspawnmgr.repository.ContainerRepository;
import com.nspawnmgr.repository.ContainerShareRepository;
import com.nspawnmgr.security.CurrentUserProvider;
import com.nspawnmgr.service.ContainerFileBrowserService;
import com.nspawnmgr.service.GuestSftpSessionStore;
import com.nspawnmgr.service.UserMessages;
import com.nspawnmgr.web.dto.ConnectSftpRequest;
import com.nspawnmgr.web.dto.ConnectSftpResponse;
import com.nspawnmgr.web.dto.FileEntryResponse;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import javax.servlet.http.HttpSession;
import javax.validation.Valid;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Path;
import java.util.List;

/**
 * Browse/download/upload a container's filesystem - works whether or not the container is
 * currently running (for MANAGED nspawn/podman - see {@link ContainerFileBrowserService}'s own
 * javadoc for the QEMU/EXTERNAL case, which needs a live SSH-reachable target instead). Gated the
 * same as container scripts: browsing a container's filesystem is at least as privileged as
 * running an owner-authored script inside it, and a shared user already has shell-equivalent
 * access via SSH sessions anyway, so this isn't a privilege escalation.
 */
@RestController
public class ContainerFilesApiController {

    private final ContainerRepository containerRepository;
    private final ContainerShareRepository containerShareRepository;
    private final ContainerFileBrowserService fileBrowserService;
    private final GuestSftpSessionStore sftpSessionStore;
    private final CurrentUserProvider currentUserProvider;
    private final UserMessages messages;

    public ContainerFilesApiController(ContainerRepository containerRepository,
                                        ContainerShareRepository containerShareRepository,
                                        ContainerFileBrowserService fileBrowserService,
                                        GuestSftpSessionStore sftpSessionStore,
                                        CurrentUserProvider currentUserProvider, UserMessages messages) {
        this.containerRepository = containerRepository;
        this.containerShareRepository = containerShareRepository;
        this.fileBrowserService = fileBrowserService;
        this.sftpSessionStore = sftpSessionStore;
        this.currentUserProvider = currentUserProvider;
        this.messages = messages;
    }

    @GetMapping("/api/containers/{id}/files")
    public List<FileEntryResponse> list(@PathVariable Long id, @RequestParam(defaultValue = "") String path,
                                         HttpSession session) {
        Container container = requireOwnedOrShared(id);
        GuestSftpSessionStore.Credential credential = sftpSessionStore.get(session, id);
        return fileBrowserService.list(container, path, credential).stream().map(FileEntryResponse::from).toList();
    }

    @GetMapping("/api/containers/{id}/files/download")
    public ResponseEntity<byte[]> download(@PathVariable Long id, @RequestParam String path, HttpSession session) {
        Container container = requireOwnedOrShared(id);
        GuestSftpSessionStore.Credential credential = sftpSessionStore.get(session, id);
        byte[] content = fileBrowserService.download(container, path, credential);
        String filename = Path.of(path).getFileName().toString();
        return ResponseEntity.ok()
                .contentType(MediaType.APPLICATION_OCTET_STREAM)
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"" + filename + "\"")
                .body(content);
    }

    @PostMapping("/api/containers/{id}/files")
    public void upload(@PathVariable Long id, @RequestParam(defaultValue = "") String path,
                        @RequestParam("file") MultipartFile file, HttpSession session) {
        Container container = requireOwnedOrShared(id);
        GuestSftpSessionStore.Credential credential = sftpSessionStore.get(session, id);
        byte[] content;
        try {
            content = file.getBytes();
        } catch (IOException e) {
            throw new UncheckedIOException(messages.get("error.web.failedToReadUploadedFile"), e);
        }
        fileBrowserService.upload(container, path, file.getOriginalFilename(), content, credential);
    }

    /** Verifies the given credential actually authenticates against a QEMU VM's guest OS or an
     *  EXTERNAL host's own SSH server, and if so, holds it in this browser session only (never the
     *  database) for {@code list}/{@code download}/{@code upload} above to use - see
     *  {@link GuestSftpSessionStore}'s own javadoc. A no-op-but-harmless call for a MANAGED
     *  nspawn/podman container (nothing needs the credential there), rather than a special case
     *  the frontend has to know about. */
    @PostMapping("/api/containers/{id}/files/connect")
    public ConnectSftpResponse connect(@PathVariable Long id, @Valid @RequestBody ConnectSftpRequest request, HttpSession session) {
        Container container = requireOwnedOrShared(id);
        char[] password = request.password().toCharArray();
        try {
            String homeDirectory = fileBrowserService.testConnection(container, request.username(), password);
            sftpSessionStore.put(session, id, request.username(), password);
            return new ConnectSftpResponse(homeDirectory);
        } finally {
            java.util.Arrays.fill(password, '\0');
        }
    }

    @DeleteMapping("/api/containers/{id}/files/connect")
    public void disconnect(@PathVariable Long id, HttpSession session) {
        requireOwnedOrShared(id);
        sftpSessionStore.clear(session, id);
    }

    /** Owner, or a user the container has been shared with — see ContainerApiController's twin. */
    private Container requireOwnedOrShared(Long id) {
        Container container = containerRepository.findById(id)
                .orElseThrow(() -> new IllegalArgumentException(messages.get("error.common.noSuchContainer", id)));
        User user = currentUserProvider.get();
        if (container.getOwner().getId().equals(user.getId())) {
            return container;
        }
        if (containerShareRepository.existsByContainerAndUser(container, user)) {
            return container;
        }
        throw new AccessDeniedException(messages.get("error.web.onlyOwnerOrSharedMayPerform"));
    }
}
