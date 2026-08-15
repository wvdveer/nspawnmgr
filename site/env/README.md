# site/env

Example configuration for the two ways to run nspawnmgr:

- **Dev loop**: `application-dev.yml` (tracked, in `src/main/resources`) already points at the
  fakes and needs no changes to get started. Run it via `tools/scripts/start-dev-stack.sh`, which
  deploys `nspawnmgr.war` (with `tools/fake-machinectl` injected onto its classpath) and
  `guacamole.war` (the `tools/fake-guacamole-server` stand-in) side by side into one Tomcat 9
  instance, plus `auth.war` via Jetty. `application-local.example.yml` here shows every available
  `nspawnmgr.*` setting if you want to override something locally — copy it to
  `application-local.yml` (gitignored) and add `local` to `spring.profiles.active`.
  Note: this orchestration is bash-only (no `.ps1` equivalent) — a fully native-Windows
  (non-WSL/git-bash) dev loop isn't currently supported; `tools/fake-machinectl` itself still
  builds and runs fine on Windows, only the scripted Tomcat/WAR orchestration doesn't.
- **Real deployment**: `.env.example` is the same settings as environment variables, for deploying
  `nspawnmgr.war` into a real Tomcat with `SPRING_PROFILES_ACTIVE=prod` against a real Linux host,
  real MySQL, and a real Guacamole install (see `tools/scripts/setup-guacamole.sh`).
