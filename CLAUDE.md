# Glass Camera

Camera app for the **Motorola Moto E40** (Android 11 / API 30, Unisoc T700, 4 GB RAM, 48 MP main sensor used in its
binned 12 MP mode, 5 MP fixed-focus front camera). Native Kotlin + Jetpack Compose + CameraX; ML Kit selfie
segmentation for Portrait. Fully offline. Sister project of the Lumo launcher (`tsetingdms/my-android-app`), whose
glass look it copies.

## Commands

```bash
gradle assembleRelease      # needs JDK 17 + Android SDK (ANDROID_HOME); no Gradle wrapper is committed (CI uses gradle/actions with gradle-version)
```

There's no Android SDK on the owner's PC, so builds run on GitHub: `gh workflow run build.yml -R tsetingdms/glass-camera -f release=false`
(compile check) or `-f release=true` (signed release). Watch with `gh run list -R tsetingdms/glass-camera`.

## Layout

- `app/src/main/java/com/tsetingdms/glasscamera/`
  - `MainActivity.kt` — permission, edge-to-edge, `OrientationEventListener` (screen locked portrait; icons rotate
    and `ImageCapture.targetRotation` follows the phone), volume keys = shutter, keep screen on.
  - `camera/CameraController.kt` — CameraX binding (Preview + ImageCapture, 4:3, capture ≤ 13 MP), Compose state for
    the UI, settings in SharedPreferences `glass_camera`, capture flows, Pro controls via Camera2 interop
    (`Camera2CameraControl` capture-request options), capability read-out (`Caps`).
  - `process/BurstMerge.kt` — multi-frame pipeline (Night 8/16 / HDR / Clean selfies): sharpest frame as
    reference, coarse-to-fine global alignment (1/8 then 1/2 scale), then per-tile refinement (32 px tiles at 1/2
    scale = 64 px, ±3 search, parabola sub-pixel fit; flat or edge-of-search tiles keep the global offset; 3×3
    median) with offsets interpolated between tile centres per pixel, then touched up per strip at full resolution
    (`refine`: each 64 px tile column's block re-matched on luma within ±2 px + parabola, plain blocks skipped, 3-wide
    median; half-res offsets alone were ~0.5 px off → soft or dotted edges), and frames sampled bilinearly. Frames are compared by 3×3 patch averages (`patchDiff`: grain cancels, misalignment/motion
    doesn't); noise limits per brightness band (1.8 × median patch difference over 6 sample strips, never below
    `MergeParams.robust`, capped at 60); per-frame weights are softened with a 5×5 tent so pixels along edges don't
    flip between merged and reference-only (speckled outlines); robust per-pixel average in
    96-row strips decoded with `BitmapRegionDecoder` (memory ≈ one picture), then local tone mapping (blurred 1/32
    luminance → gain map) with a light unsharp mask and saturation. `MergeParams.NIGHT/HDR/CLEAN` hold the tuning;
    `merge` returns `Merged` (bitmap + frames used).
  - Natural detail (Night only — 4-frame HDR/Clean came out ~50 % grainier without chip smoothing on the E40):
    `CameraController.chipProcessing` sets NOISE_REDUCTION_MODE (MINIMAL, else OFF; the E40 only has OFF) through
    `Camera2CameraControl` only for the burst, then clears the options (bursts never run in Pro).
    Chip sharpening (EDGE_MODE) stays on: switching it off made E40 photos ~15 % softer. Support = `Caps.rawNoise`.
  - `process/Portrait.kt` — ML Kit selfie segmentation on a 512 px upright copy, mask mapped back to the stored
    orientation, background blurred at 1/8 with weights excluding the person (no halo), feathered composite.
  - `process/ImageSaver.kt` — MediaStore (DCIM/Glass Camera, no storage permission; `IS_PENDING`), EXIF orientation
    via `rotate()` then `flipHorizontally()` (front mirror) instead of rotating pixels; latest photo + thumbnail.
  - `video/VideoProcessor.kt` — CameraX `SurfaceProcessor` (+ `VideoEffect` targeting PREVIEW | VIDEO_CAPTURE, bound
    with a `UseCaseGroup` in Video mode). GL thread: OES camera texture → 1/4 → 1/16 downsamples (brightness map +
    shake measurement via `glReadPixels`, SAD search ±6 px with sub-pixel fit) → process pass into a ping-pong history
    FBO (stabilizing shift + 0.85 crop, local tone map, motion-adaptive temporal denoise) → each output drawn with
    `inverse(stMatrix) × SurfaceOutput.updateTransformMatrix(...)`. Everything is measured and corrected in "frame
    space" (SurfaceTexture matrix applied), so shift directions are right for any camera/orientation. Stable/Enhance
    are volatile flags (no rebind). `video/Gl.kt` — EGL (recordable config) and GL helpers.
  - `ui/Glass.kt` — glass modifier, `tiltPress` (3D press), BarIcon, GlassCircleButton, Chip, GlassSwitch.
  - `ui/CameraScreen.kt` — top bar, viewfinder (PreviewView COMPATIBLE so it clips and composes), focus ring, zoom
    chips, Pro panel, mode switcher, shutter (progress arc), status card, toast, settings, permission screen.

## Gotchas

- **Single shots** (Photo without HDR, Portrait capture, Pro) use `CAPTURE_MODE_MAXIMIZE_QUALITY`; bursts rebind with
  `MINIMIZE_LATENCY` (`usesBurst()` / `rebindIfNeeded()`). Flash only fires for single shots on the back camera.
- In-memory frames are unrotated; `ImageInfo.rotationDegrees` + mirror go into EXIF at save time. Keep that order.
- HDR shoots at about −1 EV through exposure compensation (works on LIMITED/LEGACY cameras); manual ISO/shutter/focus
  only appear when the camera reports MANUAL_SENSOR — the E40 may not, so Pro falls back to EV + white balance.
- Versions: AGP 8.13.2 + compileSdk 36 + Kotlin 2.2.21. Libraries must have minCompileSdk ≤ 36 (androidx.core 1.19+,
  Compose BOM 2026.09+ need 37 and AGP 9). Compose uses foundation only (no Material); icons from
  material-icons-extended (R8 strips unused).
- Manifest removes INTERNET / ACCESS_NETWORK_STATE that ML Kit brings in; keep the app offline.
- `largeHeap` is on for the 12 MP merges.
- Video mode binds Preview (16:9) + VideoCapture (Recorder: FHD/HD, 20/10 Mbit/s) + the effect; no ImageCapture there.
  Mode switches into/out of Video rebind. Mic permission is asked once on first Video use; without it videos are silent.
  A recording ended by leaving the app finalizes with ERROR_SOURCE_INACTIVE but the file is kept.

## CI / releases

`.github/workflows/build.yml`: `build` (no secrets, read-only) → unsigned APK artifact + version; `release`
(contents: write, only job with secrets) zipaligns and signs with `SIGNING_*` secrets (its own key, alias `camera`, kept on the owner's PC as `Documents\glass-camera-release.jks`; fails if
missing), publishes `v<versionName>-build<run>` marked latest. Actions pinned to SHAs. Bump `versionCode` /
`versionName` in `app/build.gradle.kts` for each release.
Commit and push only when asked; never force-push.
