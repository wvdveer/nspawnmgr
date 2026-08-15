#!/bin/sh
# Lists every machine image name machinectl knows about on this host - both nspawnmgr-managed ones
# and anything created by hand - so the app can diff against its own DB and discover the
# difference. Read-only and always safe, so NOPASSWD like listContainerUsers/getInternalAddress.
set -e
machinectl list-images --no-legend --full | awk '{print $1}'
