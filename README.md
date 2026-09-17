# Motion Sickness Relief

Motion Sickness Relief is an Android app that shows an overlay while you use your phone in a car, bus, or train.

It aims to reduce motion sickness by drawing dots that drift with the vehicle's motion.

## Features

- **Standard & Accessibility Overlays** — use a standard system overlay, or grant Accessibility permission to have the cues drawn over everything, including the notification shade and lock screen.
- **Quick Settings Tile** — toggle the cues instantly from your notification shade.
- **Auto-start** — automatically activates when vehicle motion is detected (requires the "Physical activity" permission).

## Customization

The Customize screen lets you adjust the experience to your preference:

- **Dot placement** — dots near the edges only (default), a couple of columns hugging the left/right edges (matching Android's shipped Motion Assist layout), or spread across the full screen.
- **Dot Size & Density** — adjust the size of the dots or choose how many dots make up the grid (Sparse, Normal, or Dense).
- **Motion Feel** — choose between **World Relative** (dots behave like a fixed world you rotate through) or **Raw** (simpler, direct acceleration reaction).
- **Speed-Scaled Turn Cues** — automatically adjusts turn sensitivity based on your speed (requires Location permission).
- **Intensity & Opacity** — fine-tune how pronounced or subtle the cues are as well as their overall sensitivity.
- **Shape & Color** — choose between circles, diamonds, or meteoroids, with colors pulled from your device's Material You theme.
- **Randomize** — cycles shape and color every few seconds.

## Screenshots

<img src="fastlane/metadata/android/en-US/images/phoneScreenshots/main.png" alt="Main screen" width="300" /> <img src="fastlane/metadata/android/en-US/images/phoneScreenshots/dots.png" alt="Overlay dots screen" width="300" />

## License

This project is licensed under `GPL-3.0-only`.

See [LICENSE](LICENSE) for the full text.
