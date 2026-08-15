package com.nspawnmgr.cli;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class DownloadedPackageTest {

    @Test
    void parseLinesParsesFilenameAndSizeFromEachLine() {
        String stdout = "libfoo1_1.0_amd64.deb 100\nlibbar2_2.0_amd64.deb 200\n";

        assertThat(DownloadedPackage.parseLines(stdout)).containsExactly(
                new DownloadedPackage("libfoo1_1.0_amd64.deb", 100L),
                new DownloadedPackage("libbar2_2.0_amd64.deb", 200L));
    }

    @Test
    void parseLinesSkipsLinesWithoutATrailingNumber() {
        String stdout = "Some stray chatter\nlibfoo1_1.0_amd64.deb 100\n";

        assertThat(DownloadedPackage.parseLines(stdout)).containsExactly(
                new DownloadedPackage("libfoo1_1.0_amd64.deb", 100L));
    }

    @Test
    void parseLinesReturnsEmptyForBlankInput() {
        assertThat(DownloadedPackage.parseLines("")).isEmpty();
    }
}
