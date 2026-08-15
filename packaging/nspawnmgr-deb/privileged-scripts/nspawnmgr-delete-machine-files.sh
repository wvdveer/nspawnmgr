#!/bin/sh
# Removes a container's machine directory and .nspawn settings file. $1 = machine dir, $2 = settings file.
rm -rf "$1"
rm -f "$2"
