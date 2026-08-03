# Books

A deliberately tiny Wear OS audiobook player for offline `.mp3` / `.m4b` files. Built for a
OnePlus Watch 2R (Wear OS 4), personal use.

The whole point is that it never loses your place. Every audio file remembers its own position, and
the position is written to disk every 5 seconds while playing plus on every pause, seek, chapter
change, and shutdown — with a synchronous, fsynced write. So it survives all of these:

- swiping the app away (playback keeps going in a foreground service)
- the system force-closing the app to save battery
- a crash
- a flat battery or a reboot

Worst case you lose the last 5 seconds. Open the app and it goes straight back to the file you were
on, at the second you left it.

- **Library** — one folder, `/sdcard/Audiobooks`, browsed as folders and files. Sorted naturally, so
  `Chapter 2` comes before `Chapter 10`.
- **Player** — title, elapsed / total, progress drawn as an arc around the bezel, and three big
  buttons: back 30s, play/pause, forward 30s. Swipe right to go back to the library.
- Each book keeps a stable accent colour, and every part-listened file shows a progress ring plus how
  much time is left.
- Finishing a file auto-plays the next one in the same folder, and marks the finished one `finished`.
- Reopening a `finished` file starts it over; reopening any other file resumes it.
- Playback pauses when Bluetooth disconnects, and holds a wake lock so the watch dozing off does not
  stall a chapter.

## Getting the APK

GitHub Actions builds a **signed release APK** on every push to `main`. Go to the **Actions** tab,
open the latest `build` run, and download the `books-apk` artifact. Unzip it to get
`app-release.apk`. Tagging `vX.Y` also attaches it to the GitHub release.

Every build — CI or local — is signed with the same key, so `adb install -r` always works. That
matters: a signature mismatch forces an uninstall, and uninstalling wipes every saved position. The
key lives in `~/.android/wear-audiobooks.jks` on my Mac and in the repo's `KEYSTORE_BASE64` /
`KEYSTORE_PASSWORD` Actions secrets. `keystore.properties` (gitignored) points the local build at it;
without that file `assembleRelease` produces an unsigned APK.

## Installing on the watch

The Watch 2R has no USB port, so use ADB over WiFi.

1. On the watch: **Settings > System > About > Build number**, tap 7 times to enable developer options.
2. **Settings > Developer options**, turn on **ADB debugging** and **Debug over WiFi**.
3. Note the IP and port shown under Debug over WiFi.
4. From this machine, with the watch on the same WiFi network:

```sh
adb connect <watch-ip>:<port>       # accept the prompt on the watch
adb install -r app-release.apk
adb shell appops set com.abhiram.audiobooks MANAGE_EXTERNAL_STORAGE allow
```

**That third command is required, once per install.** Without it the app can only see files Android
happened to index as audio, which excludes every `.m4b` and anything you just pushed. Wear OS ships
no Settings screen and no file picker to grant this on the watch itself, so ADB is the only way —
skip it and the app just says "No storage access".

On first launch the watch will also ask for music/audio and notification access. Allow both.

## Putting books on the watch

```sh
adb push "My Book" /sdcard/Audiobooks/
adb push "Some Other Book.m4b" /sdcard/Audiobooks/
```

Anything under `/sdcard/Audiobooks` shows up, nested as deep as you like. Recognised extensions:
`mp3 m4a m4b aac ogg oga opus flac wav`. New files appear on their own within a couple of seconds —
the library screen re-reads the folder on a short interval while it is on screen (and only while it
is, so a pocketed watch scans nothing).

Books live outside the app's sandbox, so uninstalling the app does not delete them. Listening
positions do live inside it, so update with `adb install -r` rather than uninstalling first.

## Design notes

- **Media3 / ExoPlayer in a `MediaSessionService`.** The player lives in the service, not the UI, so
  closing the app cannot stop playback, and Wear gets a proper media notification and watch-face
  ongoing activity for free.
- **Positions in `SharedPreferences`, always `commit()`.** Not DataStore: `commit()` is synchronous
  and fsynced, so a saved position is on disk before the call returns, and reads are instant, which
  lets playback start at the right position with no async window.
- **Progress is keyed by path relative to `/sdcard/Audiobooks`**, so moving the whole library keeps
  every position.
- **Files must live in shared storage, not the app's own external dir.** Files `adb push`es into
  `Android/data/<pkg>/` stay owned by `shell` and the app genuinely cannot read them; shared storage
  normalises ownership.
- No audio offload. It saves battery, but it changes how positions are reported, and not losing your
  place matters more than an hour of runtime.
- No playback speed control, on purpose. Ask if you want it.

## Building locally

Requires JDK 17 and the Android SDK.

```sh
./gradlew assembleRelease   # needs keystore.properties, see above
./gradlew assembleDebug     # unsigned-for-development, no keystore needed
```

R8 is off in release on purpose: shrinking would make the shipped app differ from the one tested on
the emulator, and there is nothing here worth shrinking.
