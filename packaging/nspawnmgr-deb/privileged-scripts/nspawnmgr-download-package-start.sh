#!/bin/sh
# Launches a detached systemd unit that downloads $2 (a URL) to $3 (a host path) via curl - runs
# entirely host-side, never proxied through Tomcat's own JVM (see PackageDownloadExecutor's own
# javadoc for why: a multi-GB ISO can't reasonably be buffered in JVM heap or Base64-encoded over
# an SSH exec's stdin the way small package uploads already are). Deliberately no --collect (would
# make the race below strictly worse, not fix it): PackageDownloadService's own poll loop needs to
# read the unit's final ActiveState/ExecMainStatus after curl exits. Confirmed live this doesn't
# fully avoid the race anyway - a plain, non-collected transient unit isn't guaranteed to stay
# queryable indefinitely; it can be garbage-collected by systemd within the poll loop's own
# 2-second interval even after exiting perfectly cleanly. PackageDownloadService.checkOne() treats
# this as a completed download (not a failure) when the on-disk file size matches the known
# expected total, so a vanished-but-actually-successful unit doesn't get reported as a failure.
# $1 = download id (used only to name the unit - NOT interpolated into anything shell-parsed),
# $2 = source URL, $3 = destination host path (its parent directory is created if missing).
set -e

if [ "$(id -u)" -ne 0 ]; then
    echo "nspawnmgr-download-package-start.sh must be run as root." >&2
    exit 1
fi
if ! command -v curl >/dev/null 2>&1; then
    echo "curl is not installed on this host." >&2
    exit 1
fi

download_id="$1"
url="$2"
target_path="$3"

mkdir -p "$(dirname "$target_path")"

# -f: fail (nonzero exit) on an HTTP error response instead of writing the error page's own body
# to $target_path as if it were the real file. -L: follow redirects. --max-time: a generous but
# real ceiling (6h) so a stalled transfer doesn't tie up the unit indefinitely. $url/$target_path
# are passed as their own argv elements to systemd-run/curl, never through a shell string, so
# nothing in either can be interpreted as a shell command.
exec systemd-run --unit="nspawnmgr-download-$download_id" \
    curl -sS -f -L --max-time 21600 -o "$target_path" "$url"
