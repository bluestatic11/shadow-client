# Shadow Client — landing site

Single-page marketing site for the Shadow Client launcher. Plain HTML/CSS/JS — no build step, no framework.

## Deploy options

### Cloudflare Pages (recommended — free, fast CDN, free SSL)

1. Push this `website/` folder to a GitHub repo (could be the same as the launcher repo or a separate `shadow-client-website` repo).
2. Sign in to **dash.cloudflare.com** → Workers & Pages → Create → connect to GitHub.
3. Select the repo. Build settings:
   - Build command: *(leave blank)*
   - Output directory: `website` (or `/` if this folder IS the repo root)
4. Deploy. You'll get a `shadow-client.pages.dev` URL immediately.
5. When you buy a domain, add a CNAME to the Cloudflare-provided target. SSL auto-issues.

### GitHub Pages

1. Push to `github.com/Bluestatic11/shadow-client-website`.
2. Settings → Pages → Source = `main` branch / `(root)`. Save.
3. Get `bluestatic11.github.io/shadow-client-website` instantly.
4. Custom domain: add CNAME file with your domain; configure DNS at registrar.

## Updating download links

Open `index.html`, find `<a href="#" class="download-card primary" data-platform="windows">`. Replace `href="#"` with the direct URL of the `.exe` from your GitHub release:

```
href="https://github.com/Bluestatic11/shadow-client-launcher/releases/latest/download/ShadowClient-Setup.exe"
```

Same for `.dmg` (macOS) and `.AppImage` (Linux) once those are built.

## Files

- `index.html` — the page
- `styles.css` — all styling (dark navy + brand red, matches the in-mod theme)
- `assets/logo.svg` — favicon + brand mark
