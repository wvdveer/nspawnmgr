#!/bin/sh
# Full restart of dnsmasq - needed after a change to /etc/dnsmasq.d/nspawnmgr-upstream.conf's
# server= lines (see ContainerDnsSyncService/DnsReloader.restart()). Unlike addn-hosts (see
# nspawnmgr-reload-dnsmasq.sh, hot-reloadable via SIGHUP), server= is a structural directive
# dnsmasq only parses at process startup - confirmed live, a plain reload/SIGHUP does not pick up
# a changed server= line no matter how current the file on disk is.
set -e
systemctl restart dnsmasq.service
