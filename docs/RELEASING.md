# Releasing

Every GitHub release carries the same APK twice:

- `OPhone-AI-vX.Y.Z-debug.apk`: the versioned file.
- `OPhone-AI.apk`: the same file under a fixed name. Because the name never changes, `https://github.com/SilverCoastR/OPhone-AI/releases/latest/download/OPhone-AI.apk` always downloads the newest release. The READMEs ("Download Android APK") and the repository's website link use this URL, so they never need updating.

The in-app updater (`UpdateManager`) picks any `.apk` asset of the latest release that is not an androidTest build. Both files are identical, and each one's SHA-256 digest published by GitHub is verified before installing.

Steps:

1. Bump `versionCode` and `versionName` in `app/build.gradle`.
2. Run `node --test tests/runtime.test.mjs tests/web-markdown.test.mjs` and build `assembleDebug`.
3. Merge the release branch, then publish:
   `gh release create vX.Y.Z OPhone-AI-vX.Y.Z-debug.apk OPhone-AI.apk --latest --title "OPhone AI X.Y.Z" --notes-file notes.md`
