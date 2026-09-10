-- Whether this container's .nspawn file should grant CAP_NET_ADMIN and bind-mount
-- /dev/net/tun in, the two things every TUN/TAP-based VPN client (e.g. PureVPN's Linux CLI)
-- needs and that systemd-nspawn doesn't grant by default - see
-- NspawnSettingsRenderer.render()'s own comment. Off by default; not tied to any particular
-- VPN provider, just "this container is allowed to run its own tunnel networking".
ALTER TABLE containers ADD COLUMN vpn_capable BOOLEAN NOT NULL DEFAULT FALSE;
