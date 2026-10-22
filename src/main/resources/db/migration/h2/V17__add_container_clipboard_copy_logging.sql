-- Whether a "copy to host" from this container/host's own session page writes the copied text
-- into the shared AUDIT log - see Container.clipboardCopyLoggingEnabled's own javadoc. Off by
-- default: that log is readable by every logged-in nspawnmgr user, not just this container's
-- owner, and copied text routinely includes passwords.
ALTER TABLE containers ADD COLUMN clipboard_copy_logging_enabled BOOLEAN NOT NULL DEFAULT FALSE;
