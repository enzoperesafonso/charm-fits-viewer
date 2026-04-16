# FITS Viewer for IntelliJ & PyCharm

A plugin for JetBrains IDEs (like IntelliJ IDEA and PyCharm) that allows you to view and inspect FITS (Flexible Image Transport System) files.

![FITS Viewer Screenshot](https.placeholder.com/screenshot.png) <!-- TODO: Add a real screenshot -->

---

## Features

- **Header Viewer**: Displays all FITS header keywords, values, and comments from the Primary HDU in a clear, searchable table.
- **Image Viewer**: Renders the 2D image data from the Primary HDU.
- **Simple Image Stretching**: Applies a basic 5th-95th percentile linear stretch to make astronomical images with high dynamic range visible.
- **File Type Integration**: Registers `.fits` and `.fit` file extensions to be opened with this custom editor automatically.

## Installation

1.  **From JetBrains Marketplace** (Recommended):
    - Go to `Settings` > `Plugins`.
    - Search for "FITS Viewer" in the Marketplace tab and click `Install`.
    - Restart the IDE.

2.  **From a `.zip` file**:
    - Download the latest release `.zip` file from the [Releases](https://github.com/your-username/charm-fits-viewer/releases) page.
    - Go to `Settings` > `Plugins`.
    - Click the gear icon ⚙️ and select `Install Plugin from Disk...`.
    - Choose the downloaded `.zip` file and restart the IDE.

## Building from Source

This project is built with Gradle.

1.  **Prerequisites**:
    - A Java 17 JDK must be installed. Ensure your `JAVA_HOME` environment variable is set to its path.

2.  **Clone the repository**:
    ```sh
    git clone https://github.com/your-username/charm-fits-viewer.git
    cd charm-fits-viewer
    ```

3.  **Build the plugin**:
    ```sh
    ./gradlew buildPlugin
    ```
    The distributable `.zip` file will be created in `build/distributions/`.

## Contributing

Contributions are welcome! If you find a bug or have a feature request, please open an issue. If you'd like to contribute code, please fork the repository and submit a pull request.

## License

This project is licensed under the MIT License. See the [LICENSE](LICENSE) file for details.
