#!/bin/sh
# Removes a cached package file. $1 = path (always computed by nspawnmgr's own Java code).
set -e
rm -f "$1"
