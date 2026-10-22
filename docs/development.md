# Build Tunelyst

Tunelyst is an Android app. To build it, use **JDK 21** with Android SDK **36** and Build Tools **36.0.0**. It supports Android 8.0 and newer.

Open the project in Android Studio, or run the tests and make a debug app with:

```bash
bash ./gradlew :app:testDebugUnitTest :app:assembleDebug
```

The debug app is saved at `app/build/outputs/apk/debug/app-debug.apk`. It is for testing, not for public release.

## Publishing a release

A version tag starts the release checks and creates the signed APK and source archive. Release credentials belong only in GitHub's **Tunelyst** environment. Never add a keystore or passwords to the repository.
