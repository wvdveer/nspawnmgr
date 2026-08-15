package com.nspawnmgr.cli;

import java.time.Instant;

/** One line of stdout/stderr output from a script run, timestamped as it was received. */
public record OutputLine(Instant timestamp, OutputSource source, String text) {
}
