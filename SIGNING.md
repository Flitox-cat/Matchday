# App signing

Every Matchday APK is signed with `app/matchday-release.jks` (alias `matchday`).
Android only installs an update over the existing app when both are signed with the same key,
so this file must never be replaced or regenerated. `versionCode` increases automatically with
every build (minutes since 2025-03-01).
