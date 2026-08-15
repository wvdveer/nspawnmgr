package com.nspawnmgr.service;

import com.nspawnmgr.cli.ContainerFilesystemBrowser;
import com.nspawnmgr.cli.FileEntry;
import com.nspawnmgr.domain.Container;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ContainerFileBrowserServiceTest {

    private SettingsService settingsService;
    private ContainerFilesystemBrowser browser;
    private ContainerFileBrowserService service;

    @BeforeEach
    void setUp() {
        settingsService = mock(SettingsService.class);
        browser = mock(ContainerFilesystemBrowser.class);
        when(settingsService.nspawnMachinesDir()).thenReturn("/var/lib/machines");
        service = new ContainerFileBrowserService(settingsService, browser);
    }

    private Container container() {
        Container container = new Container();
        container.setId(9L);
        container.setName("my-container");
        return container;
    }

    @Test
    void rejectsDotDotSegment() {
        assertThatThrownBy(() -> service.list(container(), "../etc"))
                .isInstanceOf(IllegalArgumentException.class);
        verify(browser, never()).list(anyString(), anyString());
    }

    @Test
    void rejectsDotDotSegmentBuriedDeeper() {
        assertThatThrownBy(() -> service.download(container(), "a/b/../../../etc/passwd"))
                .isInstanceOf(IllegalArgumentException.class);
        verify(browser, never()).download(anyString(), anyString());
    }

    @Test
    void rejectsAbsolutePath() {
        assertThatThrownBy(() -> service.list(container(), "/etc/passwd"))
                .isInstanceOf(IllegalArgumentException.class);
        verify(browser, never()).list(anyString(), anyString());
    }

    @Test
    void rejectsBackslashAbsolutePath() {
        assertThatThrownBy(() -> service.list(container(), "\\Windows\\System32"))
                .isInstanceOf(IllegalArgumentException.class);
        verify(browser, never()).list(anyString(), anyString());
    }

    @Test
    void rejectsNullByte() {
        assertThatThrownBy(() -> service.list(container(), "foo\0bar"))
                .isInstanceOf(IllegalArgumentException.class);
        verify(browser, never()).list(anyString(), anyString());
    }

    @Test
    void rejectsDotDotSegmentWithMixedSeparators() {
        // Segment-splitting on both "/" and "\" means this is caught by the same up-front rejection
        // as a pure-forward-slash "..", not the resolve-and-verify-prefix defense-in-depth layer -
        // still worth asserting explicitly, since it's the kind of input a Windows-authored path
        // might produce.
        assertThatThrownBy(() -> service.list(container(), "a/..\\..\\etc"))
                .isInstanceOf(IllegalArgumentException.class);
        verify(browser, never()).list(anyString(), anyString());
    }

    @Test
    void acceptsDeepLegitimatePath() {
        // Path.relativize's toString() is platform-native ("\" on Windows, "/" elsewhere) - build
        // the expected relative form the same way the service does, rather than assuming "/".
        String expectedRelative = java.nio.file.Path.of("a", "b", "c").toString();
        when(browser.list(anyString(), eq(expectedRelative))).thenReturn(List.of());
        List<FileEntry> result = service.list(container(), "a/b/c");
        assertThat(result).isEmpty();
        verify(browser).list(anyString(), eq(expectedRelative));
    }

    @Test
    void blankAndNullPathMeanRoot() {
        when(browser.list(anyString(), eq(""))).thenReturn(List.of());
        service.list(container(), "");
        service.list(container(), null);
        verify(browser, org.mockito.Mockito.times(2)).list(anyString(), eq(""));
    }

    @Test
    void listSortsDirectoriesFirstThenAlphabetically() {
        when(browser.list(anyString(), eq(""))).thenReturn(List.of(
                new FileEntry("zebra.txt", false, 10, 0),
                new FileEntry("Beta", true, 0, 0),
                new FileEntry("alpha.txt", false, 5, 0),
                new FileEntry("Apple", true, 0, 0)));
        List<FileEntry> result = service.list(container(), "");
        assertThat(result).extracting(FileEntry::name)
                .containsExactly("Apple", "Beta", "alpha.txt", "zebra.txt");
    }

    @Test
    void uploadRejectsFilenameWithSeparator() {
        assertThatThrownBy(() -> service.upload(container(), "", "sub/dir/evil.txt", "x".getBytes()))
                .isInstanceOf(IllegalArgumentException.class);
        verify(browser, never()).upload(anyString(), anyString(), anyString(), any());
    }

    @Test
    void uploadRejectsDotDotFilename() {
        assertThatThrownBy(() -> service.upload(container(), "", "..", "x".getBytes()))
                .isInstanceOf(IllegalArgumentException.class);
        verify(browser, never()).upload(anyString(), anyString(), anyString(), any());
    }

    @Test
    void uploadAcceptsOrdinaryFilename() {
        service.upload(container(), "sub", "notes.txt", "x".getBytes());
        verify(browser).upload(anyString(), eq("sub"), eq("notes.txt"), any());
    }

    @Test
    void uploadPropagatesAlreadyExistsFailureFromBrowser() {
        org.mockito.Mockito.doThrow(new IllegalArgumentException("A file or folder named 'dup.txt' already exists in this directory."))
                .when(browser).upload(anyString(), anyString(), eq("dup.txt"), any());
        assertThatThrownBy(() -> service.upload(container(), "", "dup.txt", "x".getBytes()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("already exists");
    }
}
