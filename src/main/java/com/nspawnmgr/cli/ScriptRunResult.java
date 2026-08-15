package com.nspawnmgr.cli;

import java.util.List;

/** The result of running a stored ContainerScript: exit code plus the full timestamped transcript. */
public record ScriptRunResult(int exitCode, List<OutputLine> lines) {

    public boolean success() {
        return exitCode == 0;
    }
}
