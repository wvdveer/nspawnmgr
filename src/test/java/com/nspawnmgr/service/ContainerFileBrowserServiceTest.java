package com.nspawnmgr.service;

import com.nspawnmgr.cli.ContainerCliExecutor;
import com.nspawnmgr.cli.ContainerFilesystemBrowser;
import com.nspawnmgr.cli.ContainerFilesystemProvisioner;
import com.nspawnmgr.cli.FileEntry;
import com.nspawnmgr.cli.RemoteSftpBrowser;
import com.nspawnmgr.domain.Container;
import com.nspawnmgr.domain.ContainerBackend;
import com.nspawnmgr.domain.User;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ContainerFileBrowserServiceTest {

    private SettingsService settingsService;
    private ContainerFilesystemBrowser browser;
    private RemoteSftpBrowser remoteBrowser;
    private ContainerCliExecutor cliExecutor;
    private ContainerFileBrowserService service;

    private static final GuestSftpSessionStore.Credential CREDENTIAL =
            new GuestSftpSessionStore.Credential("alice", "hunter2".toCharArray());

    @BeforeEach
    void setUp() {
        settingsService = mock(SettingsService.class);
        browser = mock(ContainerFilesystemBrowser.class);
        remoteBrowser = mock(RemoteSftpBrowser.class);
        cliExecutor = mock(ContainerCliExecutor.class);
        when(settingsService.nspawnMachinesDir()).thenReturn("/var/lib/machines");
        service = new ContainerFileBrowserService(settingsService, browser,
                mock(ContainerFilesystemProvisioner.class), remoteBrowser, cliExecutor);
    }

    private Container container() {
        Container container = new Container();
        container.setId(9L);
        container.setName("my-container");
        return container;
    }

    private Container qemuContainer() {
        Container container = Container.qemu("my-vm", new User(), "");
        container.setId(11L);
        return container;
    }

    private Container externalContainer() {
        Container container = Container.external("my-host", new User(), "host.example.com");
        container.setId(12L);
        return container;
    }

    @Test
    void rejectsDotDotSegment() {
        assertThatThrownBy(() -> service.list(container(), "../etc", null))
                .isInstanceOf(IllegalArgumentException.class);
        verify(browser, never()).list(anyString(), anyString());
    }

    @Test
    void rejectsDotDotSegmentBuriedDeeper() {
        assertThatThrownBy(() -> service.download(container(), "a/b/../../../etc/passwd", null))
                .isInstanceOf(IllegalArgumentException.class);
        verify(browser, never()).download(anyString(), anyString());
    }

    @Test
    void rejectsAbsolutePath() {
        assertThatThrownBy(() -> service.list(container(), "/etc/passwd", null))
                .isInstanceOf(IllegalArgumentException.class);
        verify(browser, never()).list(anyString(), anyString());
    }

    @Test
    void rejectsBackslashAbsolutePath() {
        assertThatThrownBy(() -> service.list(container(), "\\Windows\\System32", null))
                .isInstanceOf(IllegalArgumentException.class);
        verify(browser, never()).list(anyString(), anyString());
    }

    @Test
    void rejectsNullByte() {
        assertThatThrownBy(() -> service.list(container(), "foo\0bar", null))
                .isInstanceOf(IllegalArgumentException.class);
        verify(browser, never()).list(anyString(), anyString());
    }

    @Test
    void rejectsDotDotSegmentWithMixedSeparators() {
        // Segment-splitting on both "/" and "\" means this is caught by the same up-front rejection
        // as a pure-forward-slash "..", not the resolve-and-verify-prefix defense-in-depth layer -
        // still worth asserting explicitly, since it's the kind of input a Windows-authored path
        // might produce.
        assertThatThrownBy(() -> service.list(container(), "a/..\\..\\etc", null))
                .isInstanceOf(IllegalArgumentException.class);
        verify(browser, never()).list(anyString(), anyString());
    }

    @Test
    void acceptsDeepLegitimatePath() {
        // Path.relativize's toString() is platform-native ("\" on Windows, "/" elsewhere) - build
        // the expected relative form the same way the service does, rather than assuming "/".
        String expectedRelative = java.nio.file.Path.of("a", "b", "c").toString();
        when(browser.list(anyString(), eq(expectedRelative))).thenReturn(List.of());
        List<FileEntry> result = service.list(container(), "a/b/c", null);
        assertThat(result).isEmpty();
        verify(browser).list(anyString(), eq(expectedRelative));
    }

    @Test
    void blankAndNullPathMeanRoot() {
        when(browser.list(anyString(), eq(""))).thenReturn(List.of());
        service.list(container(), "", null);
        service.list(container(), null, null);
        verify(browser, org.mockito.Mockito.times(2)).list(anyString(), eq(""));
    }

    @Test
    void listSortsDirectoriesFirstThenAlphabetically() {
        when(browser.list(anyString(), eq(""))).thenReturn(List.of(
                new FileEntry("zebra.txt", false, 10, 0),
                new FileEntry("Beta", true, 0, 0),
                new FileEntry("alpha.txt", false, 5, 0),
                new FileEntry("Apple", true, 0, 0)));
        List<FileEntry> result = service.list(container(), "", null);
        assertThat(result).extracting(FileEntry::name)
                .containsExactly("Apple", "Beta", "alpha.txt", "zebra.txt");
    }

    @Test
    void uploadRejectsFilenameWithSeparator() {
        assertThatThrownBy(() -> service.upload(container(), "", "sub/dir/evil.txt", "x".getBytes(), null))
                .isInstanceOf(IllegalArgumentException.class);
        verify(browser, never()).upload(anyString(), anyString(), anyString(), any());
    }

    @Test
    void uploadRejectsDotDotFilename() {
        assertThatThrownBy(() -> service.upload(container(), "", "..", "x".getBytes(), null))
                .isInstanceOf(IllegalArgumentException.class);
        verify(browser, never()).upload(anyString(), anyString(), anyString(), any());
    }

    @Test
    void uploadAcceptsOrdinaryFilename() {
        service.upload(container(), "sub", "notes.txt", "x".getBytes(), null);
        verify(browser).upload(anyString(), eq("sub"), eq("notes.txt"), any());
    }

    @Test
    void uploadPropagatesAlreadyExistsFailureFromBrowser() {
        org.mockito.Mockito.doThrow(new IllegalArgumentException("A file or folder named 'dup.txt' already exists in this directory."))
                .when(browser).upload(anyString(), anyString(), eq("dup.txt"), any());
        assertThatThrownBy(() -> service.upload(container(), "", "dup.txt", "x".getBytes(), null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("already exists");
    }

    @Test
    void needsRemoteSftpTrueForQemu() {
        assertThat(service.needsRemoteSftp(qemuContainer())).isTrue();
    }

    @Test
    void needsRemoteSftpTrueForExternal() {
        assertThat(service.needsRemoteSftp(externalContainer())).isTrue();
    }

    @Test
    void needsRemoteSftpFalseForManagedNspawn() {
        assertThat(service.needsRemoteSftp(container())).isFalse();
    }

    @Test
    void listWithoutCredentialThrowsForRemoteTarget() {
        assertThatThrownBy(() -> service.list(qemuContainer(), "", null))
                .isInstanceOf(IllegalStateException.class);
        verify(remoteBrowser, never()).list(anyString(), anyInt(), anyString(), any(), anyString());
    }

    @Test
    void listDispatchesToRemoteBrowserForQemuUsingInternalAddress() {
        when(cliExecutor.getInternalAddress("my-vm", ContainerBackend.QEMU)).thenReturn("10.0.0.5");
        when(remoteBrowser.list(eq("10.0.0.5"), eq(22), eq("alice"), any(), eq("/")))
                .thenReturn(List.of(new FileEntry("f.txt", false, 1, 0)));
        List<FileEntry> result = service.list(qemuContainer(), "/", CREDENTIAL);
        assertThat(result).extracting(FileEntry::name).containsExactly("f.txt");
    }

    @Test
    void listThrowsWhenQemuHasNoAddressYet() {
        when(cliExecutor.getInternalAddress("my-vm", ContainerBackend.QEMU)).thenReturn("");
        assertThatThrownBy(() -> service.list(qemuContainer(), "/", CREDENTIAL))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void listDispatchesToRemoteBrowserForExternalUsingResolvedHostnameAndDefaultPort() {
        when(cliExecutor.resolveHostname("host.example.com")).thenReturn("192.168.1.10");
        when(remoteBrowser.list(eq("192.168.1.10"), eq(22), eq("alice"), any(), eq("/")))
                .thenReturn(List.of());
        service.list(externalContainer(), "/", CREDENTIAL);
        verify(remoteBrowser).list(eq("192.168.1.10"), eq(22), eq("alice"), any(), eq("/"));
    }

    @Test
    void listDispatchesToRemoteBrowserForExternalUsingConfiguredPort() {
        Container container = externalContainer();
        container.setExternalSshPort(2222);
        when(cliExecutor.resolveHostname("host.example.com")).thenReturn("192.168.1.10");
        service.list(container, "/", CREDENTIAL);
        verify(remoteBrowser).list(eq("192.168.1.10"), eq(2222), eq("alice"), any(), eq("/"));
    }

    @Test
    void remotePathMustBeAbsolute() {
        when(cliExecutor.getInternalAddress("my-vm", ContainerBackend.QEMU)).thenReturn("10.0.0.5");
        assertThatThrownBy(() -> service.list(qemuContainer(), "notes.txt", CREDENTIAL))
                .isInstanceOf(IllegalArgumentException.class);
        verify(remoteBrowser, never()).list(anyString(), anyInt(), anyString(), any(), anyString());
    }

    @Test
    void remotePathRejectsDotDotSegmentEvenWhenAbsolute() {
        when(cliExecutor.getInternalAddress("my-vm", ContainerBackend.QEMU)).thenReturn("10.0.0.5");
        assertThatThrownBy(() -> service.list(qemuContainer(), "/a/../etc", CREDENTIAL))
                .isInstanceOf(IllegalArgumentException.class);
        verify(remoteBrowser, never()).list(anyString(), anyInt(), anyString(), any(), anyString());
    }

    @Test
    void remotePathAllowsNavigatingAboveWhereverHomeIs() {
        // No artificial home-directory cap - see ContainerFileBrowserService's own javadoc. A path
        // that isn't under any notion of "home" (e.g. another user's home, or a root-owned path)
        // is accepted here just like any other absolute path - the real boundary is the remote
        // account's own OS permissions, enforced by RemoteSftpBrowser itself, not this validation.
        when(cliExecutor.getInternalAddress("my-vm", ContainerBackend.QEMU)).thenReturn("10.0.0.5");
        when(remoteBrowser.list(eq("10.0.0.5"), eq(22), eq("alice"), any(), eq("/root")))
                .thenReturn(List.of());
        service.list(qemuContainer(), "/root", CREDENTIAL);
        verify(remoteBrowser).list(eq("10.0.0.5"), eq(22), eq("alice"), any(), eq("/root"));
    }

    @Test
    void downloadDispatchesToRemoteBrowser() {
        when(cliExecutor.getInternalAddress("my-vm", ContainerBackend.QEMU)).thenReturn("10.0.0.5");
        when(remoteBrowser.download(eq("10.0.0.5"), eq(22), eq("alice"), any(), eq("/home/alice/notes.txt")))
                .thenReturn("hi".getBytes());
        byte[] result = service.download(qemuContainer(), "/home/alice/notes.txt", CREDENTIAL);
        assertThat(result).isEqualTo("hi".getBytes());
    }

    @Test
    void uploadDispatchesToRemoteBrowser() {
        when(cliExecutor.getInternalAddress("my-vm", ContainerBackend.QEMU)).thenReturn("10.0.0.5");
        service.upload(qemuContainer(), "/home/alice/sub", "notes.txt", "x".getBytes(), CREDENTIAL);
        verify(remoteBrowser).upload(eq("10.0.0.5"), eq(22), eq("alice"), any(), eq("/home/alice/sub"), eq("notes.txt"), any());
    }

    @Test
    void testConnectionDelegatesToRemoteBrowserForQemuAndReturnsTheResolvedHomeDirectory() {
        when(cliExecutor.getInternalAddress("my-vm", ContainerBackend.QEMU)).thenReturn("10.0.0.5");
        when(remoteBrowser.testConnection(eq("10.0.0.5"), eq(22), eq("alice"), any())).thenReturn("/home/alice");
        String homeDirectory = service.testConnection(qemuContainer(), "alice", "hunter2".toCharArray());
        verify(remoteBrowser).testConnection(eq("10.0.0.5"), eq(22), eq("alice"), any());
        assertThat(homeDirectory).isEqualTo("/home/alice");
    }

    @Test
    void testConnectionDelegatesToRemoteBrowserForExternal() {
        when(cliExecutor.resolveHostname("host.example.com")).thenReturn("192.168.1.10");
        service.testConnection(externalContainer(), "alice", "hunter2".toCharArray());
        verify(remoteBrowser).testConnection(eq("192.168.1.10"), eq(22), eq("alice"), any());
    }
}
