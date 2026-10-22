# Approved mockup

The redesign mockup approved on 2026-10-19: 75 boards (`*.dc.html`) and `canvas.json`, which places each
board on a canvas page (Users & viewers, VM & environment details, More — everyday, More — cost &
administration, Environment admin, Administrator, Phone, Building blocks) with its title and size.
The boards are 1440 × 900 unless `canvas.json` says otherwise (phones are 390 × 844).

These files are the target for the UX work (UX Tracker U01–U17). Do not edit them here; a change of
design is made in the mockup and copied in again.

## Viewing the boards next to the app

`test/ux-review/compare-mockup.mjs` renders every board and the app screen it was built into
(`test/ux-review/board-routes.json`) and writes a side-by-side report. Two things are not in this folder:

- **The board runtime.** Each board loads `./support.js`, the design tool's runtime. It is not part of
  this repository; export it from the mockup (`artifact-type/dc-runtime.js`) and pass its path with
  `--runtime`.
- **Fonts.** The boards link IBM Plex from Google Fonts; the compare script answers those requests with
  the app's own font files (`src/main/resources/static/webfonts`), so nothing is fetched from Google.
