# nspawnmgr .deb packaging

Maven module that builds the production `.deb` package. For build/install instructions, what the
package does, and what to do afterward, see **`docs/administrator-guide.md`, §5 "Installing
nspawnmgr"** — that's the authoritative walkthrough, not this file.

This module must be built last, after `mvn install` (root) and `mvn -f auth/pom.xml package` have
produced `target/nspawnmgr.war` and `auth/target/auth.war` — see `tools/scripts/build-all.sh`
(`BUILD_DEB=1` to include this step).
