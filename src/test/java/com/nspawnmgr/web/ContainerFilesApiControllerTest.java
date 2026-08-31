package com.nspawnmgr.web;

import com.nspawnmgr.domain.Container;
import com.nspawnmgr.domain.User;
import com.nspawnmgr.repository.ContainerRepository;
import com.nspawnmgr.repository.ContainerShareRepository;
import com.nspawnmgr.security.CurrentUserProvider;
import com.nspawnmgr.service.ContainerFileBrowserService;
import com.nspawnmgr.service.GuestSftpSessionStore;
import com.nspawnmgr.web.dto.ConnectSftpRequest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.security.access.AccessDeniedException;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ContainerFilesApiControllerTest {

    private ContainerRepository containerRepository;
    private ContainerShareRepository containerShareRepository;
    private ContainerFileBrowserService fileBrowserService;
    private GuestSftpSessionStore sftpSessionStore;
    private CurrentUserProvider currentUserProvider;
    private ContainerFilesApiController controller;

    private final User owner = user(1L, "owner");
    private final User stranger = user(2L, "stranger");

    @BeforeEach
    void setUp() {
        containerRepository = mock(ContainerRepository.class);
        containerShareRepository = mock(ContainerShareRepository.class);
        fileBrowserService = mock(ContainerFileBrowserService.class);
        sftpSessionStore = mock(GuestSftpSessionStore.class);
        currentUserProvider = mock(CurrentUserProvider.class);
        controller = new ContainerFilesApiController(containerRepository, containerShareRepository,
                fileBrowserService, sftpSessionStore, currentUserProvider);
    }

    private static User user(long id, String username) {
        User user = new User("external-" + id);
        user.setId(id);
        user.setUsername(username);
        return user;
    }

    private Container container() {
        Container container = new Container();
        container.setId(9L);
        container.setName("my-vm");
        container.setOwner(owner);
        return container;
    }

    @Test
    void connectVerifiesCredentialAndStoresItInSessionOnSuccess() {
        Container container = container();
        when(containerRepository.findById(9L)).thenReturn(Optional.of(container));
        when(currentUserProvider.get()).thenReturn(owner);
        when(fileBrowserService.testConnection(eq(container), eq("alice"), any(char[].class))).thenReturn("/home/alice");
        MockHttpSession session = new MockHttpSession();

        var response = controller.connect(9L, new ConnectSftpRequest("alice", "hunter2"), session);

        verify(fileBrowserService).testConnection(eq(container), eq("alice"), any(char[].class));
        verify(sftpSessionStore).put(eq(session), eq(9L), eq("alice"), any(char[].class));
        assertThat(response.homeDirectory()).isEqualTo("/home/alice");
    }

    @Test
    void connectPropagatesTestConnectionFailureWithoutStoringCredential() {
        Container container = container();
        when(containerRepository.findById(9L)).thenReturn(Optional.of(container));
        when(currentUserProvider.get()).thenReturn(owner);
        org.mockito.Mockito.doThrow(new RuntimeException("auth failed"))
                .when(fileBrowserService).testConnection(any(), anyString(), any());
        MockHttpSession session = new MockHttpSession();

        assertThatThrownBy(() -> controller.connect(9L, new ConnectSftpRequest("alice", "wrong"), session))
                .isInstanceOf(RuntimeException.class);

        verify(sftpSessionStore, never()).put(any(), any(), anyString(), any());
    }

    @Test
    void connectRejectsUserWithNoOwnershipOrShare() {
        Container container = container();
        when(containerRepository.findById(9L)).thenReturn(Optional.of(container));
        when(currentUserProvider.get()).thenReturn(stranger);
        when(containerShareRepository.existsByContainerAndUser(container, stranger)).thenReturn(false);

        assertThatThrownBy(() -> controller.connect(9L, new ConnectSftpRequest("alice", "hunter2"), new MockHttpSession()))
                .isInstanceOf(AccessDeniedException.class);

        verify(fileBrowserService, never()).testConnection(any(), anyString(), any());
    }

    @Test
    void connectAllowsSharedUser() {
        Container container = container();
        when(containerRepository.findById(9L)).thenReturn(Optional.of(container));
        when(currentUserProvider.get()).thenReturn(stranger);
        when(containerShareRepository.existsByContainerAndUser(container, stranger)).thenReturn(true);
        MockHttpSession session = new MockHttpSession();

        controller.connect(9L, new ConnectSftpRequest("alice", "hunter2"), session);

        verify(sftpSessionStore).put(eq(session), eq(9L), eq("alice"), any(char[].class));
    }

    @Test
    void disconnectClearsSessionCredential() {
        Container container = container();
        when(containerRepository.findById(9L)).thenReturn(Optional.of(container));
        when(currentUserProvider.get()).thenReturn(owner);
        MockHttpSession session = new MockHttpSession();

        controller.disconnect(9L, session);

        verify(sftpSessionStore).clear(session, 9L);
    }

    @Test
    void listLooksUpSessionCredentialAndPassesItToTheService() {
        Container container = container();
        when(containerRepository.findById(9L)).thenReturn(Optional.of(container));
        when(currentUserProvider.get()).thenReturn(owner);
        MockHttpSession session = new MockHttpSession();
        GuestSftpSessionStore.Credential credential = new GuestSftpSessionStore.Credential("alice", "x".toCharArray());
        when(sftpSessionStore.get(session, 9L)).thenReturn(credential);
        when(fileBrowserService.list(container, "sub", credential)).thenReturn(java.util.List.of());

        var result = controller.list(9L, "sub", session);

        assertThat(result).isEmpty();
        verify(fileBrowserService).list(container, "sub", credential);
    }
}
