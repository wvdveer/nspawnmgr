#!/bin/sh
# Sets up the nspawnmgr_ci sudo-capable SSH account: a second, deliberately narrow account,
# completely isolated from nspawnmgr_exec (see docs/administrator-guide.md, "Trust boundary" -
# nspawnmgr_exec is loopback-only by design and already holds broad NOPASSWD/PASSWORD grants; this
# account is the opposite, meant to be reached over the network by an external CI/CD pipeline, so
# it's kept to exactly two fixed-shape privileged capabilities: installing/updating a container
# template via nspawnmgr-install-template.sh, and installing/updating an admin-package-cache entry
# via nspawnmgr-install-package.sh. NOT part of `dpkg -i` - opt-in only, run this by hand when you
# actually want CI/CD template/package automation.
#
# Runnable two ways, matching setup-sudo-account.sh:
#   1. Standalone, directly from a repo checkout:
#         sudo packaging/nspawnmgr-deb/scripts/setup-ci-template-account.sh
#      (auto-detects the sibling privileged-scripts/ and debian/nspawnmgr-ci.sudoers next to this
#      script's own checkout location - no flags needed.)
#   2. From an installed .deb (the script itself ships at /usr/lib/nspawnmgr/, same as
#      setup-sudo-account.sh, but nothing calls it automatically):
#         sudo /usr/lib/nspawnmgr/setup-ci-template-account.sh --sudoers-src /usr/share/nspawnmgr/nspawnmgr-ci.sudoers
#
# Prints the newly generated SSH PRIVATE key to stdout exactly once (on first setup, or when
# re-run with --rotate-key) - copy it into your CI system's own secret store immediately. Nothing
# is stored on this host beyond the public half in authorized_keys; password login is locked
# entirely (this account authenticates by SSH key only).
#
# Usage: setup-ci-template-account.sh [--privileged-scripts-src DIR] [--sudoers-src FILE] [--rotate-key]
set -e

SERVICE_USER="nspawnmgr_ci"
SERVICE_HOME="/var/lib/nspawnmgr_ci"
TARGET_SCRIPTS_DIR="/usr/lib/nspawnmgr/privileged"
SUDOERS_FILE="/etc/sudoers.d/nspawnmgr_ci"

PRIVILEGED_SCRIPTS_SRC=""
SUDOERS_SRC=""
ROTATE_KEY=false

while [ $# -gt 0 ]; do
    case "$1" in
        --privileged-scripts-src) PRIVILEGED_SCRIPTS_SRC="$2"; shift 2 ;;
        --sudoers-src) SUDOERS_SRC="$2"; shift 2 ;;
        --rotate-key) ROTATE_KEY=true; shift ;;
        *) echo "Unknown argument: $1" >&2; exit 1 ;;
    esac
done

# Auto-detect repo-checkout siblings if not explicitly given, and they exist - same fallback
# behavior as setup-sudo-account.sh (see its own comment for why).
SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
if [ -z "$PRIVILEGED_SCRIPTS_SRC" ] && [ -d "$SCRIPT_DIR/../privileged-scripts" ]; then
    PRIVILEGED_SCRIPTS_SRC="$SCRIPT_DIR/../privileged-scripts"
fi
if [ -z "$SUDOERS_SRC" ] && [ -f "$SCRIPT_DIR/../debian/nspawnmgr-ci.sudoers" ]; then
    SUDOERS_SRC="$SCRIPT_DIR/../debian/nspawnmgr-ci.sudoers"
fi

if [ "$(id -u)" -ne 0 ]; then
    echo "setup-ci-template-account.sh must be run as root." >&2
    exit 1
fi

# 1. Service account - idempotent. Real home dir + shell since it's an SSH login target that must
#    be able to run a non-interactive remote command (`ssh user@host sudo ...`); a nologin-style
#    shell would refuse that too, not just an interactive session. Plain `useradd` (not Debian's
#    `adduser` wrapper) - see setup-sudo-account.sh's matching comment for why.
ACCOUNT_EXISTED=false
if id "$SERVICE_USER" >/dev/null 2>&1; then
    ACCOUNT_EXISTED=true
else
    useradd -r -m -d "$SERVICE_HOME" -s /bin/bash -U "$SERVICE_USER"
fi

# 2. Wrapper scripts - only copied in if a source was given (standalone/manual use); when installed
#    via the .deb, dpkg has already unpacked these to $TARGET_SCRIPTS_DIR (shared with
#    nspawnmgr_exec - nspawnmgr-install-template.sh lives alongside the others there; only the
#    sudoers grant differs between the two accounts).
if [ -n "$PRIVILEGED_SCRIPTS_SRC" ]; then
    mkdir -p "$TARGET_SCRIPTS_DIR"
    cp "$PRIVILEGED_SCRIPTS_SRC"/*.sh "$TARGET_SCRIPTS_DIR/"
    chmod 750 "$TARGET_SCRIPTS_DIR"/*.sh
    chown root:root "$TARGET_SCRIPTS_DIR"/*.sh
    echo "setup-ci-template-account.sh: copied wrapper scripts from $PRIVILEGED_SCRIPTS_SRC into $TARGET_SCRIPTS_DIR."
fi

# 3. Sudoers - copy in if a source was given, then always validate before trusting it. Deliberately
#    a separate file from /etc/sudoers.d/nspawnmgr_exec so the two trust domains stay independently
#    auditable/removable.
if [ -n "$SUDOERS_SRC" ]; then
    install -m 440 -o root -g root "$SUDOERS_SRC" "$SUDOERS_FILE"
fi
if [ -f "$SUDOERS_FILE" ] && ! visudo -cf "$SUDOERS_FILE" >/dev/null 2>&1; then
    echo "setup-ci-template-account.sh: $SUDOERS_FILE failed visudo validation - removing it. Sudo" >&2
    echo "setup-ci-template-account.sh: access for $SERVICE_USER will NOT work until this is fixed." >&2
    rm -f "$SUDOERS_FILE"
    exit 1
fi
if [ ! -f "$SUDOERS_FILE" ]; then
    echo "setup-ci-template-account.sh: $SUDOERS_FILE not found and no --sudoers-src given - re-run" >&2
    echo "setup-ci-template-account.sh: with --sudoers-src pointing at nspawnmgr-ci.sudoers." >&2
    exit 1
fi
echo "setup-ci-template-account.sh: $SUDOERS_FILE is in place and valid."

# 4. Lock password login entirely - this account authenticates by SSH key only, and its sole sudo
#    grant is NOPASSWD, so there's no password to manage for it at all (unlike nspawnmgr_exec, which
#    supports a stored-secret password specifically for admin-approval-mode's `sudo -S` prompt).
passwd -l "$SERVICE_USER" >/dev/null

# 5. SSH keypair - regenerated every time this section runs (fresh setup, or explicit
#    --rotate-key), unlike nspawnmgr_exec's own key (generated once and left in place - that one is
#    read directly by Tomcat's own JVM on every call, so it has to persist on this host). This
#    account's private key is never meant to live here at all: only the public half goes into
#    authorized_keys, and the private half is printed once, then its local copy is deleted.
if [ "$ACCOUNT_EXISTED" = true ] && [ "$ROTATE_KEY" != true ]; then
    echo "setup-ci-template-account.sh: $SERVICE_USER already exists - leaving its authorized_keys" >&2
    echo "setup-ci-template-account.sh: alone. Re-run with --rotate-key to replace its SSH key." >&2
else
    install -d -m 700 -o "$SERVICE_USER" -g "$SERVICE_USER" "$SERVICE_HOME/.ssh"
    KEY_TMP_DIR="$(mktemp -d)"
    trap 'rm -rf "$KEY_TMP_DIR"' EXIT
    ssh-keygen -t ed25519 -N '' -f "$KEY_TMP_DIR/id_ed25519" -C nspawnmgr_ci >/dev/null
    # Replace (not append): this account has exactly one credential in use at a time, so a previous
    # --rotate-key's key should stop working immediately, not linger as a second valid credential.
    cp "$KEY_TMP_DIR/id_ed25519.pub" "$SERVICE_HOME/.ssh/authorized_keys"
    chmod 600 "$SERVICE_HOME/.ssh/authorized_keys"
    chown "$SERVICE_USER:$SERVICE_USER" "$SERVICE_HOME/.ssh/authorized_keys"
    echo "setup-ci-template-account.sh: generated a fresh SSH keypair for $SERVICE_USER."
    echo "================================================================"
    echo "  PRIVATE KEY - copy everything below this line into your CI"
    echo "  system's secret store now. It will not be shown again, and"
    echo "  nothing is kept on this host beyond the public half."
    echo "================================================================"
    cat "$KEY_TMP_DIR/id_ed25519"
    echo "================================================================"
    rm -rf "$KEY_TMP_DIR"
    trap - EXIT
fi

echo "setup-ci-template-account.sh: done."
