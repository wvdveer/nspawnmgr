#!/usr/bin/env bash
# Downloads Apache Guacamole's official webapp WAR (javax.servlet-based, hence nspawnmgr itself
# targeting Boot 2.7/Tomcat 9 — see root pom.xml), caching the archive under tools/downloads
# (gitignored), then deploys it into site/tomcat/webapps/guacamole.war alongside nspawnmgr.war.
#
# This only proves the two webapps can be *deployed together* in one Tomcat 9 instance — actually
# using Guacamole for real sessions additionally needs guacd (the native proxy daemon) running and
# an authentication backend configured via GUACAMOLE_HOME/guacamole.properties, neither of which
# this script sets up. Run tools/scripts/setup-tomcat.sh first if site/tomcat doesn't exist yet.
set -euo pipefail

GUACAMOLE_VERSION="${GUACAMOLE_VERSION:-1.5.5}"
root="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
downloads_dir="$root/tools/downloads"
tomcat_dir="$root/site/tomcat"
archive_name="guacamole-${GUACAMOLE_VERSION}.war"
archive_path="$downloads_dir/$archive_name"
download_url="https://archive.apache.org/dist/guacamole/${GUACAMOLE_VERSION}/binary/${archive_name}"

if [[ ! -d "$tomcat_dir" ]]; then
    echo "site/tomcat doesn't exist yet — run tools/scripts/setup-tomcat.sh first." >&2
    exit 1
fi

mkdir -p "$downloads_dir"

if [[ ! -f "$archive_path" ]]; then
    echo "Downloading Guacamole ${GUACAMOLE_VERSION}..."
    curl -fsSL -o "$archive_path" "$download_url"
else
    echo "Using cached $archive_path"
fi

echo "Deploying guacamole.war..."
cp "$archive_path" "$tomcat_dir/webapps/guacamole.war"

echo "Guacamole ${GUACAMOLE_VERSION} deployed to $tomcat_dir/webapps/guacamole.war"
