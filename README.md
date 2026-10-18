# FITS Viewer for PyCharm

A plugin for PyCharm and other JetBrains IDEs that opens FITS (Flexible Image Transport System) files in an image viewer instead of as unreadable binary.

## Features

- **Image viewer** for every image HDU, including tile-compressed (`.fz`) images and data cubes (pick the plane)
- **Stretches:** linear, log, square root and asinh, with grayscale, heat, viridis, magma, inferno, plasma, cividis and cool colour maps (invertible)
- **Levels:** ZScale by default (as in DS9 and astropy), *99.5%* and *Min/Max* presets, and a histogram with draggable black and white points
- **Cursor readout:** pixel coordinates, value and RA/Dec (TAN, SIN, ARC, ZEA and STG projections)
- **Mouse and keyboard navigation:** zoom around the cursor, drag to pan
- **Magnifier, crosshair and measuring tool** (pixel distance and on-sky separation)
- **Statistics** including median and a robust noise estimate (MAD σ) that ignores stars
- **Export as PNG** with the current stretch and colour map
- **Remembers your display settings** across files and IDE restarts
- **Header browser** for every HDU, with filtering
- **Reloads automatically** when the file changes on disk, for example when a script rewrites it

Opens `.fits`, `.fit`, `.fts` and `.fz` files. Gzip-compressed FITS files with those extensions work too.

Pixel coordinates are 0-based with `y` increasing upwards, so the value shown at `x, y` is `data[y, x]` in NumPy and astropy. Images are drawn with the first row at the bottom, like `imshow(..., origin="lower")` and DS9.

### Controls

| | Mouse | Keyboard |
|---|---|---|
| Zoom in / out | ⌘ (Ctrl on Windows/Linux) + scroll, or the toolbar buttons | `+` / `−` |
| Fit to window | Fit toolbar button | `0` |
| Actual size (1 image pixel = 1 screen pixel) | Actual Size toolbar button | `1` |
| Pan | Drag, or scroll / two-finger swipe | Arrow keys (Shift for half a screen) |
| Measure a distance | Turn on Measure (ruler icon), then drag. Middle-drag still pans. | |

Click the image first so it has keyboard focus.

### Memory use

Only headers and the plane being displayed are held in memory, and nothing is kept once the tab is closed. Planes larger than 4096×4096 pixels are downsampled for display (every *n*-th pixel); the status bar says when that happens, and coordinates and values still refer to original pixels. Each open FITS tab uses at most about 130 MB.

## Installation

Requires PyCharm (or another JetBrains IDE) 2024.3 or newer.

1. Download the latest `.zip` from the [Releases](https://github.com/enzoperesafonso/charm-fits-viewer/releases) page.
2. In the IDE, open **Settings → Plugins**, click the ⚙️ icon and choose **Install Plugin from Disk…**
3. Select the downloaded `.zip`. No restart needed.

## Development

You need Git and any JDK 17+ to run Gradle. Gradle downloads JDK 21 for compiling if you don't have it.

```sh
./gradlew runIde        # launch a sandbox PyCharm with the plugin installed
./gradlew check         # run the tests
./gradlew buildPlugin   # build the installable zip into build/distributions/
./gradlew verifyPlugin  # check binary compatibility against recent IDE releases (large download)
```

`platformVersion` in `gradle.properties` sets the IDE that `runIde` launches and the build compiles against. `pluginSinceBuild` is the oldest IDE the plugin supports.

## Releasing

1. Bump `pluginVersion` in `gradle.properties` and update the change notes in `build.gradle.kts`.
2. Create a GitHub release with a tag like `v1.1.0`. The **Release** workflow builds the plugin and attaches the zip to the release.
3. Optionally publish to the [JetBrains Marketplace](https://plugins.jetbrains.com) too:
   - The first upload is manual: on the Marketplace, choose **Upload plugin** and upload the zip from step 2. JetBrains reviews new plugins before listing them.
   - For later versions, create a token under **Profile → My Tokens** and add it as a repository secret called `PUBLISH_TOKEN`. The Release workflow then publishes each new release automatically.
   - Optional: to [sign the plugin](https://plugins.jetbrains.com/docs/intellij/plugin-signing.html), also add `CERTIFICATE_CHAIN`, `PRIVATE_KEY` and `PRIVATE_KEY_PASSWORD` secrets.

## Contributing

Bug reports and pull requests are welcome. Please run `./gradlew check` before opening a pull request.

## License

MIT. See [LICENSE](LICENSE).
