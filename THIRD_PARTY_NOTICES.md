# Third-party software

The Android app (`app/`) is licensed under GPL-3.0-or-later (see `LICENSE`). The APK includes these libraries,
all under the Apache License 2.0 (compatible with GPLv3):

| Library | Licence |
|---|---|
| Kotlin standard library (JetBrains) | Apache-2.0 |
| kotlinx.coroutines, kotlinx.serialization (JetBrains) | Apache-2.0 |
| AndroidX: Core, Activity, Lifecycle, DataStore, WorkManager (Google) | Apache-2.0 |
| Jetpack Compose UI, Material 3, Material Icons (Google) | Apache-2.0 |

Only used to build and test, not part of the APK: JUnit 4 (EPL-1.0), Robolectric (MIT), AndroidX Test (Apache-2.0),
Gradle and the Android Gradle Plugin (Apache-2.0).

The desktop app (`desktop/`) uses PySide6 (LGPL-3.0 / GPL) and pyqtgraph (MIT).

Manufacturer Modbus documents are not part of this repository; `docs/specs/<make>/SOURCES.md` lists where each one
can be downloaded. Product and company names are trademarks of their owners.
