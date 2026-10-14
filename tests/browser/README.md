# Browser integration checks

Run the development backend at `127.0.0.1:8080` and Vite at `127.0.0.1:5173`.
Run `python tests/dev_smoke.py --base http://127.0.0.1:8080` from the repository root to create the synthetic PDF fixture, then build `apps/web` with the default production settings (no `VITE_ENABLE_DEMO`).

From this directory run `npm ci` and `npm test`. The test uses installed Google Chrome on Windows, adds only synthetic documents, exercises approval against the real development backend, checks tenant switching and responsive widths, then serves the real production bundle on a temporary loopback port to verify the demo gate.

Evidence is saved in `work/browser-test-result.json` and `work/ui-*.png`. A frame load event cannot prove PDF rendering or human review; screenshots and explicit confirmation complement the automated control checks. This is not clinical validation or a security certification.
