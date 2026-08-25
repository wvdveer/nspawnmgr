-- Container.podCommand (see domain/Container.java's own comment) - the command run as a podman
-- pod's PID 1, like a Dockerfile CMD. Nullable: blank means "trust the loaded image's own CMD",
-- the pre-existing (and often broken - see PodLivenessPollingService) default behavior.
ALTER TABLE containers ADD COLUMN pod_command VARCHAR(1000);
