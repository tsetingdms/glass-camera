# Glass Camera

A camera app tuned for the **Motorola Moto E40** (Android 11, Unisoc T700), with a "liquid glass" interface.
Everything happens on the phone: no internet permission, no account, no tracking.

**Download:** https://github.com/tsetingdms/glass-camera/releases/latest/download/glass-camera.apk

## Modes

- **Photo** — one shot from the camera's own processing. Optional **HDR**: 4 frames taken ~1 EV darker
  (bright skies keep their detail), aligned and merged, then the shadows are lifted.
- **Night** — 8 or 16 frames (6 or 12 on the front camera) aligned for hand shake and averaged, which removes most
  of the low-light grain, then brightened with local tone mapping and sharpened. Alignment is per 64-pixel tile,
  refined at full resolution to a fraction of a pixel, so a slightly turning hand or small movements still line up;
  pixels that moved are left out (no ghosts).
- **Natural detail** (Settings, on by default) — for Night the camera chip is asked to skip its own smoothing,
  which smears fine detail. Merging 8 or 16 frames removes the grain instead: frames are aligned to a fraction of a
  pixel, compared by 3×3 patches (grain averages out, a misaligned edge doesn't), and the merge measures how grainy
  they are. HDR and Clean selfies (4 frames) keep the chip's smoothing. Settings → This camera shows whether the
  phone allows it.
- **Portrait** — on-device person detection (ML Kit, offline) and a soft background blur.
- **Video** — 1080p (or 720p) at a high bitrate, with sound. **Stable**: GCam-style software stabilization — each
  frame's hand shake is measured against the previous one and cancelled within a 15 % crop. **Enhance**: local tone
  mapping (brighter shadows, kept highlights) and motion-aware temporal noise reduction. Both run on the GPU and apply
  to the viewfinder too, so what you see is what's recorded. Pause / resume, torch, timer. Saved to `Movies/Glass Camera`.
- **Pro** — exposure compensation and white balance; ISO, shutter speed and manual focus where the phone's camera
  allows them (shown in Settings → This camera).

Front camera: **Clean selfies** (4 frames merged for less grain), **Screen light** (a white screen lights your
face in the dark), mirrored like the viewfinder (both can be turned off in Settings).

Also: tap to focus, pinch / 1×-2×-4× zoom, 3 s / 10 s timer, grid, volume buttons as shutter.
Photos go to `DCIM/Glass Camera`.

## Building

GitHub Actions builds it: **Actions → Build APK → Run workflow** (tick "release" to publish a release).

Locally (needs JDK 17, the Android SDK and Gradle 8.14): `gradle assembleRelease`.

## Signing

The release job signs the APK with secrets set in **Settings → Secrets and variables → Actions**:
`SIGNING_KEYSTORE_BASE64`, `SIGNING_STORE_PASSWORD`, and optionally `SIGNING_KEY_ALIAS` (default `camera`) and
`SIGNING_KEY_PASSWORD` (default = store password). The camera has its own key (`glass-camera-release.jks`, alias `camera`). Keep using it: Android only installs updates signed
with the key the app was first installed with.
