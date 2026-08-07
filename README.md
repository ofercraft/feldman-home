# Feldman Home

An independent, unofficial Android client for [Home Assistant](https://www.home-assistant.io/), designed to make smart home control fast, expressive, and easy to use.

## Download

[**Install from Google Play**](https://play.google.com/store/apps/details?id=com.feldman.ha)

You can also [download the latest APK from Releases](https://github.com/ofercraft/feldman-home/releases/latest) to install Feldman Home without Google Play.

> [!IMPORTANT]
> The repository APK is an alternative to Google Play. Android may ask you to allow installation from your browser or file manager; only install APKs downloaded from this repository.

## Features

- Build custom dashboards with resizable, configurable cards
- Control lights, climate, locks, blinds, switches, and more
- View Frigate cameras, live feeds, and event history
- Control your home using interactive home screen widgets
- Turn your charging device into a customizable Home Assistant screensaver with its own cards, orientation, and visual style
- Personalize colors, themes, motion, and expressive card styling
- Get responsive controls with real-time state feedback
- Connect directly to your own Home Assistant instance

Manage your home from the app or your home screen without ads, bloat, or unnecessary clutter.

## Screenshots

### Phone

<p align="center">
  <img src="screenshots/phone/01_home_dashboard.png" alt="Home dashboard" width="24%">
  <img src="screenshots/phone/02_climate_controls.png" alt="Climate controls" width="24%">
  <img src="screenshots/phone/03_climate_detail.png" alt="Climate details" width="24%">
  <img src="screenshots/phone/04_camera_overview.png" alt="Camera overview" width="24%">
</p>
<p align="center">
  <img src="screenshots/phone/05_camera_history.png" alt="Camera history" width="24%">
  <img src="screenshots/phone/06_home_screen_widgets.png" alt="Home screen widgets" width="24%">
  <img src="screenshots/phone/07_personalization.png" alt="Personalization" width="24%">
  <img src="screenshots/phone/08_dashboard_editor.png" alt="Dashboard editor" width="24%">
</p>

### Tablet

<p align="center">
  <img src="screenshots/tablet/01_home_dashboard.png" alt="Tablet home dashboard" width="49%">
  <img src="screenshots/tablet/02_climate_controls.png" alt="Tablet climate controls" width="49%">
</p>
<p align="center">
  <img src="screenshots/tablet/03_climate_detail.png" alt="Tablet climate details" width="49%">
  <img src="screenshots/tablet/04_camera_overview.png" alt="Tablet camera overview" width="49%">
</p>
<p align="center">
  <img src="screenshots/tablet/05_camera_history.png" alt="Tablet camera history" width="49%">
  <img src="screenshots/tablet/06_home_screen_widgets.png" alt="Tablet home screen widgets" width="49%">
</p>
<p align="center">
  <img src="screenshots/tablet/07_personalization.png" alt="Tablet personalization" width="49%">
  <img src="screenshots/tablet/08_dashboard_editor.png" alt="Tablet dashboard editor" width="49%">
</p>

## Source and builds

The complete Android Studio project lives in [`src/`](src/).

Requirements:

- JDK 17
- Android SDK 37

Build a debug APK:

```bash
cd src
./gradlew assembleDebug
```

Release signing credentials are never stored in the repository. To create a signed release, set `RELEASE_STORE_FILE`, `RELEASE_STORE_PASSWORD`, `RELEASE_KEY_ALIAS`, and `RELEASE_KEY_PASSWORD` in your environment before running `./gradlew bundleRelease` or `./gradlew assembleRelease`.

## Project policies

- [Privacy policy](PRIVACY.md)
- [Security policy](SECURITY.md)
- [Contributing](CONTRIBUTING.md)
- [Third-party notices](THIRD_PARTY_NOTICES.md)

## License

Feldman Home is licensed under the [MIT License](LICENSE).

## Disclaimer

Feldman Home is an independent project and is not affiliated with, endorsed by, or connected to the Home Assistant project.
