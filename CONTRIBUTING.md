# Contributing

Issues and pull requests are welcome. This is a small project, so the bar is
simple: keep each change focused on one thing, and say why in the description.

## Getting set up

```sh
./gradlew assembleDebug
```

Then install the APK on a round Wear OS watch or the Wear OS emulator.

## Before opening a pull request

- Test on a round display. A layout that only looks right on a square emulator is
  not finished.
- Say which watch and which Wear OS version you tested on.
- Never regress position saving. The whole point of this app is that it does not
  lose your place, so positions must still survive a force close, a crash, and a
  reboot. If you touch the persistence path, test all three.

Issues labelled `good first issue` are self contained and a good place to start.
