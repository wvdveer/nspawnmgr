#!/bin/sh
# --no-block: queue the restart and return immediately, rather than waiting for tomcat9 to actually
# go down — which would never return at all, since the SSH command that's *asking* for the restart
# is itself served by the very Tomcat instance about to die.
set -e
systemctl restart --no-block tomcat9
