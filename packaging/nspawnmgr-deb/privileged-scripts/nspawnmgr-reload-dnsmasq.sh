#!/bin/sh
# Reloads dnsmasq so it re-reads addn-hosts (see ContainerDnsSyncService/DnsReloader) - dnsmasq
# does NOT do this automatically, confirmed live: it only re-reads addn-hosts on SIGHUP or restart.
# reload-or-restart rather than a bare reload: falls back to a full restart if the installed
# dnsmasq.service unit doesn't define ExecReload for some reason, rather than silently no-op-ing.
set -e
systemctl reload-or-restart dnsmasq.service
