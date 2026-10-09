# Lawn Bowls

Singles lawn bowls in three versions, coded to the World Bowls *Laws of the Sport of Bowls* (Crystal Mark, 4th edition).

| Folder | What | Notes |
|---|---|---|
| `android/` | Android app (Java, targetSdk 37, no permissions) | The full version: laws engine, 14 AI personalities, AI v AI spectator mode |
| `cpp/` | Windows Win32/GDI version + `CMakeLists.txt` | Original game; does not have the laws engine or personalities |
| `python/` | tkinter version | Original game; does not have the laws engine or personalities |

## Android build
`android/build.sh` builds with the raw SDK tools (aapt2, javac, d8, zipalign, apksigner), no Gradle.
It targets Android 17 (API 37, minSdk 30). Set `ANDROID_HOME` (with `build-tools;37.0.0` and `platforms;android-37.0` installed), or `BUILD_TOOLS` + `ANDROID_JAR`; `VERSION_CODE` / `VERSION_NAME` override the version.
If there is no `signing.p12` + `signing.pass` (both git-ignored) it creates a new key.
The rules, AI and match logic (`Rules`, `Ai`, `Ego`, `Game`, `Physics`) are plain Java so they run on a JVM; see `android/test/`:

    javac -d /tmp/sim android/src/com/example/lawnbowls/{Ball,Physics,Rules,Ai,Ego,Game}.java android/test/com/example/lawnbowls/*.java
    java -cp /tmp/sim com.example.lawnbowls.Sim

## CI and releases
`.github/workflows/android.yml` runs the JVM tests (`Sim`, `SpecTest`) and builds the APK on every pull request and push to `main`.
Each push to `main` that passes is published on the Releases page as `v4.1.<run number>` with the APK and its SHA-256.

For releases to install over each other they must share a signing key. Add two repository secrets:

| Secret | Value |
|---|---|
| `ANDROID_SIGNING_P12_BASE64` | `base64 -w0 android/signing.p12` |
| `ANDROID_SIGNING_PASS` | contents of `android/signing.pass` |

Without them CI signs each build with a throwaway key and logs a warning.

## C++
    cmake -S cpp -B build && cmake --build build

## Python
    python3 python/lawn_bowls.py

## Rules source
Laws text from Bowls South Africa's copy of the World Bowls Laws (4th ed.). Not implemented: foot faults, time limits, trial ends, nominated touchers, displaced bowls, measuring disputes, declining the last bowl, falling bowls; Laws 50-52 and 55-56 were not consulted.
Official laws: https://www.worldbowls.com/laws-of-the-sport/

Untested on a physical Android device.
