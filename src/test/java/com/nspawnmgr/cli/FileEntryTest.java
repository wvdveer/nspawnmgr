package com.nspawnmgr.cli;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class FileEntryTest {

    @Test
    void parseLinesParsesTypeNameSizeAndMtimeFromEachLine() {
        String stdout = "d\tsubdir\t4096\t1754899200\nf\treadme.txt\t1024\t1754899100\n";

        assertThat(FileEntry.parseLines(stdout)).containsExactly(
                new FileEntry("subdir", true, 4096L, 1754899200L),
                new FileEntry("readme.txt", false, 1024L, 1754899100L));
    }

    @Test
    void parseLinesHandlesFilenamesWithSpaces() {
        String stdout = "f\tmy file (1).txt\t50\t1754899100\n";

        assertThat(FileEntry.parseLines(stdout)).containsExactly(
                new FileEntry("my file (1).txt", false, 50L, 1754899100L));
    }

    @Test
    void parseLinesSkipsMalformedLines() {
        String stdout = "Some stray chatter\nf\treadme.txt\t1024\t1754899100\n";

        assertThat(FileEntry.parseLines(stdout)).containsExactly(
                new FileEntry("readme.txt", false, 1024L, 1754899100L));
    }

    @Test
    void parseLinesReturnsEmptyForBlankInput() {
        assertThat(FileEntry.parseLines("")).isEmpty();
    }
}
