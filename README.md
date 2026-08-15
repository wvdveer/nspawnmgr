# nspawnmgr

## Dev tooling

- **`tools/scripts/start-dev-stack.sh`** — builds and runs the full stack (nspawnmgr, auth, a fake
  Guacamole) against fakes for `machinectl`, so it works without root or a real container host.
- **`tools/web-test-harness/`** — a dependency-free browser test runner (plain HTML/JS, no npm, no
  build step) that drives the real server-rendered pages in their own tabs and asserts on their
  live DOM. Deployed alongside the dev stack at `http://localhost:8080/test-harness/`.

