# Stardrift interactive mockup

A clickable, keyboard-driven mockup of the Stardrift home screen with sample content only (no real apps, feeds or accounts). The drifting sky, the sounds and the layout are the same ones the launcher uses.

- Open `index.html`. It must be served over http (for example GitHub Pages, or `python3 -m http.server` in this folder); opening the file straight from disk does not work because the page loads its own template.
- Arrow keys move the focus, Enter selects, Escape goes back. Press and hold a tile or a card for its menu. Sound starts after the first click or key press.
- `Main.dc.html` is the mockup, `support.js` is the small runtime that renders it, `audio-asset.js` holds the project's own ambient song and interface sounds.
- The sky data in the app (`SkyData.kt`, see docs/DEVELOPING.md) is generated from this mockup.
