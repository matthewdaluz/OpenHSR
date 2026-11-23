# OpenHSR

OpenHSR is a free and open-source Android app for checking Hamilton Street Railway (HSR) departures. Tap a stop on the map, search by stop name or number, or use **Near me** to find stops within 800 m. Drag up the departure sheet to see upcoming buses or choose a date for a stop’s published timetable.

The app downloads Hamilton’s timetable on first launch and keeps a copy so stop search and scheduled departures work offline. Live departures and bus positions require a connection. OpenHSR uses Android’s built-in location providers and calculates nearby stops on the device. The map uses OpenFreeMap’s OpenStreetMap-based tiles.

## Build and install

Use JDK 17 and the Android SDK with platform 36, build-tools 35.0.0, and platform-tools. Set `JAVA_HOME` to your JDK and `ANDROID_HOME` to your Android SDK. OpenHSR runs on Android 8.0 (API 26) and later.

```sh
./gradlew testDebugUnitTest assembleDebug
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

You can also open the repository in Android Studio and run the `app` configuration. With an emulator or device connected, run `./gradlew connectedDebugAndroidTest` to verify that a failed schedule import preserves the previous timetable.

Use hardware graphics acceleration to check map labels on the emulator. SwiftShader can omit text on the OpenFreeMap basemap. [MapLibre issue #3648](https://github.com/maplibre/maplibre-native/issues/3648)

## Data and licence

Contains public sector Data made available under the City of Hamilton’s Open Data Licence. [GTFS Schedule](https://opendata.hamilton.ca/GTFS-Static/) · [GTFS Realtime](https://opendata.hamilton.ca/GTFS-RT/) · [Hamilton Open Data Licence](https://www.hamilton.ca/city-council/data-maps/open-data/open-data-licence-terms-and-conditions)

Map tiles by OpenFreeMap and OpenMapTiles, with data from OpenStreetMap contributors. [OpenFreeMap](https://openfreemap.org/) · [OpenMapTiles](https://openmaptiles.org/) · [OpenStreetMap attribution](https://www.openstreetmap.org/copyright)

OpenHSR is an independent project licensed under the [GNU General Public License v3](LICENSE).
