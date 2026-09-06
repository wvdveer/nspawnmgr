#!/bin/sh
# Runs on the bare HOST, over the same SSH path every other privileged-scripts/*.sh call uses
# (SshRemoteExecutor always targets nspawnbr0's own gateway address, i.e. the host - see that
# class's own doc) - but tomcat9.service itself lives inside the self-hosted "nspawnmgr" machine's
# own systemd, not the host's, since self-hosting moved Tomcat off the host entirely (see
# nspawnmgr-bootstrap-app-machine.sh). A bare `systemctl restart tomcat9` here runs against the
# HOST's systemd, where that unit was never registered - confirmed live: "Unit tomcat9.service not
# found", meaning this had been silently broken for every self-hosted install since that migration
# (nothing had exercised it in that state until GuacamoleExtensionRefresh's own restart trigger).
# --machine=nspawnmgr reaches into that specific container's systemd instead, the same technique
# already used for read-only inspection (systemctl --machine=nspawnmgr status guacd).
#
# --no-block: queue the restart and return immediately, rather than waiting for tomcat9 to actually
# go down — which would never return at all, since the SSH command that's *asking* for the restart
# is itself served by the very Tomcat instance about to die.
set -e
systemctl --machine=nspawnmgr restart --no-block tomcat9
