# Overnight

A direct-flash, film-look camera for Android, made for nights out.

- **Tap anywhere** on the viewfinder to shoot. The flash always fires (the front camera uses a white screen flash).
- **PORTRAIT / GROUP**: fixed focus at about 0.8 m or 2.2 m, like a point-and-shoot.
- **×1 / ×5**: a single shot, or 5 shots over about 5 seconds. A burst saves every frame plus a stitched photo strip.
- **BLUR**: drag-shutter look. The background streaks while the subject stays sharp.
- Photos save to `Pictures/FlashCam`.

## How the look works

Everything is in `app/src/main/java/app/flashcam/Look.kt`:

1. Downscale and soften the image so it reads like a cheap digicam.
2. Find the person with on-device ML Kit selfie segmentation. This stands in for LiDAR depth.
3. Flash falloff: push the subject brighter and pull the background darker, plus a vignette.
4. Film tone curve: warm highlights, faded blacks, cream whites.
5. Saturation boost that rolls off in blown highlights.
6. Halation and glow around hot spots.
7. Grain.

Tuning values live in `Look.Params`.

## Building

GitHub Actions builds a signed APK on every push to `main`. Each build is published under **Releases**. To build locally, open the project in Android Studio.
