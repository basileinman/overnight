# Overnight

A direct-flash, film-look camera for Android, made for nights out.

- **Tap anywhere** on the viewfinder to shoot. The flash fires the instant you tap (no pre-flash).
- **Hold and slide** on the viewfinder to change exposure.
- **NIGHT / DAY**: NIGHT uses the flash, DAY doesn't. It switches by itself with the light.
- **Shot modes**: single, burst (5 frames plus a photo strip, playable and exportable as a GIF), or double exposure.
- **Effects**: BLUR (motion smear) or DRAG (rear-curtain slow shutter with flash).
- **Rolls**: load 12, 24 or 36 exposures. Photos stay hidden until the roll develops at 8 AM.
- Photos save to `Pictures/Overnight`.

## Install and update

Download the latest APK from **Releases**. To get updates automatically, add this repo's URL to
[Obtainium](https://github.com/ImranR98/Obtainium). The app can also check for updates in Settings (gear on the roll page).

## How the look works

The processing pipeline is in `app/src/main/java/app/flashcam/Look.kt`: soften, per-photo white balance,
fitted per-channel tone curves for NIGHT and DAY, saturation, halation, grain and vignette.
Settings → Developer → Look Lab exposes the knobs live.

## Building

GitHub Actions builds a signed APK on every push to `main` and publishes it under **Releases**.
The signing key lives in repository secrets (`KEYSTORE_BASE64`, `KEYSTORE_PASSWORD`), never in the code.
