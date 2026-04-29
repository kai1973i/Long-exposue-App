# Long Exposure App

An Android camera application that enables **long-exposure photography** using the Camera2 API.

## Features

- **Manual exposure control** – choose exposure times from 1/30 s up to 30 s
- **Manual ISO control** – select ISO from 100 to 3200
- **Live preview** – TextureView-based viewfinder (preview exposure is capped at 500 ms for usability)
- **Full-resolution capture** – captures a JPEG still at the selected exposure and ISO
- **Gallery integration** – saved images land in `DCIM/LongExposure/` and appear in the Gallery on all Android versions (API 21+)

## Requirements

| Requirement | Version |
|---|---|
| Min SDK | API 21 (Android 5.0 Lollipop) |
| Target SDK | API 34 (Android 14) |
| Camera2 hardware level | `LIMITED` or better |

## Getting Started

### Build

```bash
./gradlew assembleDebug
```

The resulting APK will be at `app/build/outputs/apk/debug/app-debug.apk`.

### Permissions

The app requests the following permissions at runtime:

- `CAMERA` – required to open the camera
- `WRITE_EXTERNAL_STORAGE` – required on Android ≤ 9 to save photos
- `READ_MEDIA_IMAGES` – required on Android 13+ to read saved photos

## Project structure

```
app/
└── src/main/
    ├── AndroidManifest.xml
    ├── java/com/longexposure/app/
    │   └── MainActivity.kt          # Camera2 session, exposure controls, image saving
    └── res/
        ├── layout/activity_main.xml # TextureView + SeekBars + Capture button
        └── values/
            ├── strings.xml
            ├── colors.xml
            └── themes.xml
```

## How it works

1. The Camera2 API is used to open the back-facing camera.
2. Auto-exposure (`CONTROL_AE_MODE`) is disabled; `SENSOR_EXPOSURE_TIME` and `SENSOR_SENSITIVITY` are set manually.
3. A live preview is shown in the `TextureView`.  For exposures longer than 500 ms the preview uses the capped value so the viewfinder remains usable.
4. Pressing **Capture** fires a single `TEMPLATE_STILL_CAPTURE` request with the full selected exposure time and ISO.
5. The resulting JPEG is saved to `DCIM/LongExposure/LE_<timestamp>.jpg` via `MediaStore` (Android 10+) or `MediaScannerConnection` (older devices).
