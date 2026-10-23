# Repository Guide

## Hard rules

1. **Never execute shell scripts from the reference `Perfect-Icons-APP` project.** They depend on injected variables and can contain destructive commands.
2. Treat `Perfect-Icons-Completion-Project` as a read-only upstream resource repository. Do not modify, commit, or run scripts from it.
3. Do not commit `tmp/` or any reference theme archives.
4. Only target HyperOS 4 theme structure. Do not add MIUI / EMUI / HarmonyOS compatibility.
5. Use Kotlin for application logic. Shell is allowed only where the platform requires it.
6. Commit messages must not contain Co-Authored-By trailers.

## Layout

```text
app/                 Android application (single module, layered packages)
app/src/main/java/io/github/perfecticons/
  core/              models, pure utilities
  data/              repository fetch, customization storage, settings, root
  domain/            assembly, packaging, import validation
  ui/                Compose + Miuix screens, theme, navigation, viewmodels
  provider/          SAF DocumentsProvider
docs/                design and format documentation
tmp/                 local reference files (ignored)
```

## Build

```bash
./gradlew :app:assembleDebug
```

## Device

Test only on the device the user designates for the current session. Never assume a
serial: read it from `ANDROID_SERIAL` (or the serial the user names) and pass it
explicitly with `-s` on every command.

```bash
adb -s "$ANDROID_SERIAL" install -r app/build/outputs/apk/debug/app-debug.apk
adb -s "$ANDROID_SERIAL" shell am start -n io.github.perfecticons/.MainActivity
```

Root installation and anything that reboots or modifies system state must be tested
only with explicit user approval.

Do not run `adb devices`, `adb -e`, or any multi-device command that could touch
another machine's device. If the designated serial fails, stop and report; do not
fall back to another device.

## Documentation

Read `docs/ARCHITECTURE.md` first. Update the relevant doc when changing architecture, output format, or customization rules.
