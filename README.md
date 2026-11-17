# OpenLens

A free, open-source camera app for Android (Kotlin + CameraX), MIT licensed.
Original code, inspired by Open Camera but not derived from it.

## Features
- Photo and video capture, saved to Pictures/OpenLens and Movies/OpenLens
- Front/back camera switch, flash and torch
- Tap to focus, pinch to zoom, exposure compensation slider

## Build
Open this folder in Android Studio (Koala or newer), let Gradle sync, press Run.
Or generate a wrapper (`gradle wrapper`) and run `./gradlew assembleDebug`.

## Roadmap (contributions welcome)
Manual ISO/shutter/focus (Camera2 interop), RAW/DNG, HDR, self-timer,
geotagging, settings screen, resolution picker, app icon, F-Droid release.
