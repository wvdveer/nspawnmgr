# dev_env

Developer/environment-specific configuration that must never be committed: real database
connection details, HTTPS certs, which hostname to use locally, and (on the CI runner) test OS
accounts. Everything here except this README, `.gitignore`, and the `.example` files is gitignored.

This same folder convention applies on the Gitea CI runner, not just developer machines: since
the runner is persistent bare-metal with no root access from CI, it needs its own `dev_env` set up
once, out-of-band, the same way a real deployment host would.

## CI runner setup

Copy `ci-test-user.env.example` to `dev_env/ci-test-user.env` and point it at a real OS account
that already exists on the runner (or create one yourself, outside of CI, since CI itself can't).
`.gitea/workflows/build.yml`'s integration-test job sources this file to log into `auth/` for a
genuine end-to-end test of the PAM-based auth path.

The `real-container-lifecycle` job needs four more things set up once, out-of-band, on the runner:

1. **`sshpass`** installed (e.g. `apt install sshpass`) — `tools/scripts/lib/ssh-sudo.sh` needs it
   to drive the sudo-capable account non-interactively from a shell script (the app itself doesn't
   need this; `SshRemoteExecutor` authenticates over the SSH protocol directly).
2. **A separate sudo-capable local account** — copy `ssh-account.env.example` to
   `dev_env/ssh-account.env` and point it at a real local account with sudo access. This is the
   same account `nspawnmgr.ssh.*` (see item 5 below) would point at for interactive dev use; the
   CI job reuses it both to run `tools/scripts/setup-container-template.sh`/
   `tools/scripts/real-lifecycle-test.sh`'s own SSH+sudo calls and to configure the real
   `SshRemoteExecutor`-backed executors when it starts nspawnmgr for real
   (`tools/scripts/start-real-stack.sh`). That account needs to be able to read/write into this
   checkout's `site/templates/` directory.
3. **A real, bootable container rootfs** — run `tools/scripts/setup-container-template.sh` once by
   hand to download and bake it (installs+enables openssh-server so the real create-container flow
   never needs the container itself to reach the network — see the script's own comments for why).
   The CI job's checkout step preserves the result across runs, so this is a one-time cost per
   runner, not a per-run one.
4. **The sudo account's grants, the privileged scripts, and dnsmasq's own scoping config** — since
   `real-container-lifecycle` runs the app directly (`start-real-stack.sh`), not via a real `.deb`
   install, none of `packaging/nspawnmgr-deb/debian/nspawnmgr.sudoers` (sudo grants),
   `packaging/nspawnmgr-deb/privileged-scripts/*.sh` (copied to `/usr/lib/nspawnmgr/privileged/`),
   or `packaging/nspawnmgr-deb/dnsmasq-nspawnmgr.conf` (copied to
   `/etc/dnsmasq.d/nspawnmgr.conf`) get installed automatically the way a real `.deb`'s postinst
   would. All three need to be placed by hand once, and **kept manually in sync whenever their
   source changes** — confirmed live (2026-08-21), all three had silently drifted for weeks (missing
   sudoers grants → "sudo: a password is required"; missing scripts → "command not found"; the
   dnsmasq scoping config was never installed at all → dnsmasq failing to start, colliding with
   `systemd-resolved` on port 53). Diff/recopy all three from source if `real-container-lifecycle`
   ever reports a permissions or "command not found" error that looks environmental rather than a
   real code bug.
5. **A ufw rule allowing DHCP on `nspawnbr0`, if the runner runs ufw at all** — same reasoning as
   item 4: postinst's own `ufw allow in on nspawnbr0 to any port 67 proto udp` (added after the
   2026-08-22 incident below) never runs here either. Without it, a default-deny ufw setup silently
   drops every container's DHCPDISCOVER, and it never leaves BOOTING - confirmed live (acer,
   2026-08-22): `sudo ufw status verbose` showed `Default: reject (incoming)` with no rule for port
   67, despite `70-nspawnmgr-bridge.network`'s `DHCPServer=yes` and `systemd-networkd` itself both
   being correctly configured. Run the `ufw allow` command above by hand once if
   `real-container-lifecycle` ever gets stuck polling `BOOTING` forever with an otherwise-correct
   bridge/networkd setup.

## Setup

1. Copy `application-dev_env.example.yml` to `application-dev_env.yml` and fill in the values
   your system administrator gave you (database vendor/URL/credentials, hostname, optionally an
   HTTPS keystore path). It's picked up automatically on startup — no profile flag needed.
2. If your administrator gave you an HTTPS keystore, drop it under `dev_env/certs/` and point
   `server.ssl.key-store` at it in your `application-dev_env.yml`.
3. If `nspawnmgr.host.external-hostname` isn't `localhost`, add a hosts-file entry pointing it at
   `127.0.0.1` (or wherever the stack actually runs), e.g. on Linux/macOS:
   `echo "127.0.0.1 nspawnmgr.local" | sudo tee -a /etc/hosts`
4. The database vendor (MySQL or PostgreSQL) is entirely your administrator's choice —
   `spring.datasource.url` plus matching `spring.flyway.locations: classpath:db/migration/<vendor>`
   in your `application-dev_env.yml` is all that's needed; both drivers already ship with the app.
5. Since the account nspawnmgr runs under (Tomcat) has no local sudo access, set
   `nspawnmgr.ssh.username`/`password` to a separate local account on the host that does — that's
   who RealContainerCliExecutor/RealContainerFilesystemProvisioner SSH into (always `127.0.0.1`)
   to run machinectl/systemd-run and touch root-owned paths under sudo.

If you don't create `application-dev_env.yml` at all, nspawnmgr falls back to the tracked
`dev` profile defaults (in-memory H2, plain HTTP, `localhost`/fakes under `tools/`) — see
`site/env/README.md` for that path instead.
