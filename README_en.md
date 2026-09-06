<div align="center">

<img src="store/icon-512.png" width="96" alt="Junrei Map icon">

# Junrei Map

**An Android map for anime pilgrimage — find the real places behind the scenes.**

English | [中文](README.md)

</div>

Junrei Map is an **unofficial, third-party Android client** built on the anime pilgrimage data of
[anitabi.cn](https://anitabi.cn).

The map holds more than 50,000 anime filming locations: find where a familiar scene was shot and which
episode it comes from, and once you are there, open the original frame and line up the same shot.

The project takes the open-source [Anitabi for iOS](https://github.com/anitabi/anitabi-swift-app)
as its reference. The main features — browsing the map, work and spot cards, search, comparison
shots, dropped pins — behave the same way as in the iOS app.

To learn more about Anitabi and its pilgrimage data, visit [anitabi.cn](https://anitabi.cn).

> **Status: v0.1 released**
>
> The APK is on the [Releases](../../releases) page; the source is under the Apache License 2.0.

## What's different on Android

On top of what Anitabi already offers, the Android app adds a few things aimed at everyday
pilgrimage use.

* **Pilgrimage log**

  After visiting a spot, tap ✓ to record it as done.

  Work cards show your progress, and the map can switch to "visited only" or "unvisited only". A
  pilgrimage-log list grouped by work makes it easy to look back at the places you have been.

  Everything is stored on your own device and travels with Android system backup.

* **Search Chinese and Japanese names with pinyin or romaji**

  No need to switch keyboards.

  Pinyin such as `gudu yaogun`, initials such as `gdyg`, or romaji such as `yuru` all find the
  matching works.

  Japanese kanji and simplified Chinese names also match each other.

* **A more flexible map card**

  The bottom card snaps to three heights. When you want to see more map, shrink it to a single strip.

  In landscape, the card moves to the left side of the screen so it covers less of the map.

* **Experimental AI character cutout**

  For comparison shots you can opt into a segmentation model trained specifically on anime
  characters, which cuts them out more cleanly than the general-purpose one.

  Off by default; enable it on the About page. `arm64-v8a` devices only. The model is downloaded
  once over Wi-Fi when you first turn it on.

  Leave it off and the app keeps using the standard cutout.

* **Languages and themes**

  Chinese, English and 日本語, with light and dark themes.

## Screenshots

| Map                              | Work                                | Spot                                 | Pilgrimage log                              |
| ------------------------------- | --------------------------------- | ---------------------------------- | --------------------------------- |
| ![Map](docs/screenshots/map.jpg) | ![Work card](docs/screenshots/work.jpg) | ![Spot card](docs/screenshots/point.jpg) | ![Pilgrimage log](docs/screenshots/log.jpg) |

<sub>Map imagery © Google. The anime screenshots shown are anitabi.cn community content (CC BY-NC-SA 4.0); the works themselves belong to their respective rights holders.</sub>

## Install

**Requirements:**

* Android 10 (API 29) or newer
* Google Play services, used for the map base layer and location

1. Download the latest `junrei-map-v*.apk` from [Releases](../../releases).
2. If you like, check the APK's SHA-256 against the release notes.
3. Open the APK and follow the Android prompts to install it.

If you want to build the app yourself, see the [developer documentation](docs/README.md).

## Permissions and privacy

Junrei Map:

* needs no account or sign-in
* has no ads
* contains no analytics or crash-reporting SDK
* does not collect or upload your pilgrimage log or any other personal data

The app only uses the following permissions when the related feature needs them:

| Permission        | Purpose                                                                                   |
| ----------------- | ----------------------------------------------------------------------------------------- |
| Location (coarse) | Only while the app is in use, for "nearby sanctuaries" and map follow; no location history |
| Camera            | Taking on-site comparison shots                                                            |
| Photos            | Saving the comparison images you create                                                    |

In normal use the app connects to:

* **the anitabi.cn CDN** — pilgrimage data and images, cached on the device
* **Google Maps** — the map base layer

If you enable the experimental **AI cutout**, the model file is downloaded once from GitHub. That
means GitHub receives the IP address that any ordinary network connection carries.

Leave the feature off and that download never happens.

## Data and credits

The places, work information and scene screenshots in Junrei Map come from the
[anitabi.cn](https://anitabi.cn) community.

To add a new sanctuary, correct an existing spot or submit other data, use the same submission
links as the Anitabi web and iOS versions — the spot cards and the map both provide them.

By default the app hides spots related to adult (R18) content.

Places that call for extra care, such as schools and libraries, show a pilgrimage etiquette
reminder.

**Please respect residents, staff and other visitors, stay out of restricted areas, and don't disturb
people's daily lives.**

Thanks to the Anitabi community and everyone who keeps collecting, correcting and maintaining the
pilgrimage data.

## Developer documentation

If you want to know how the project is built, how it is structured, or how to contribute, see:

* [Developer documentation](docs/README.md)
* [Contributing guide](CONTRIBUTING.md)
* [Security policy](SECURITY.md)

This README is written for ordinary users; implementation details and technical notes live in the
developer documentation.

## Reporting problems

Pick the channel that matches the problem:

* **Problems with the app itself — crashes or features misbehaving**
  Open an [issue](../../issues) in this repository.

* **Wrong map data, spot locations or work information**
  Report it to the [Anitabi documentation repository](https://github.com/anitabi/anitabi.cn-document/issues).

## License

The source code and UI text in this repository are released under the
[Apache License 2.0](LICENSE).

**Junrei Map is an unofficial, third-party Anitabi client for Android and has no affiliation with
Anitabi.**

Pilgrimage data and related screenshots come from the anitabi.cn community and are shared under
[CC BY-NC-SA 4.0](https://creativecommons.org/licenses/by-nc-sa/4.0/).

Anime screenshots, work titles and other related content remain the property of their respective
rights holders and are used in this project only to compare anime scenes with real locations.

Third-party components and their licenses:

* [NOTICE](NOTICE)
* [licenses/](licenses/)
