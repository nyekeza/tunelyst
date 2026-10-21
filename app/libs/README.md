# Slim Compose icons

`material-icons-slim.jar` contains only the 22 Material Icons Extended classes currently referenced by Tunelyst that are not already provided by `androidx.compose.material:material-icons-core`.

- Upstream artifact: `androidx.compose.material:material-icons-extended-android:1.7.8`
- The subset is byte-for-byte copied from that artifact's `classes.jar`; the upstream Kotlin module metadata and version marker are retained.
- If icon usage or the Compose icon version changes, regenerate this JAR from the matching upstream AAR and update the class subset.
- AndroidX is licensed under Apache License 2.0; see `LICENSE-APACHE-2.0.txt`.
