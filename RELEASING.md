# Releasing

Notes for maintainers.

## Useful commands

```sh
./gradlew runIde        # launch a sandbox PyCharm with the plugin installed
./gradlew check         # run the tests
./gradlew buildPlugin   # build the installable zip into build/distributions/
./gradlew verifyPlugin  # check binary compatibility against recent IDE releases (large download)
```

In `gradle.properties`, `platformVersion` sets the IDE that `runIde` launches and the build compiles against, and `pluginSinceBuild` is the oldest IDE the plugin supports.

## Making a release

1. Bump `pluginVersion` in `gradle.properties` and update the change notes in `build.gradle.kts`.
2. Create a GitHub release with a tag like `v1.1.0`. The **Release** workflow builds the plugin and attaches the zip to the release.
3. Publish to the [JetBrains Marketplace](https://plugins.jetbrains.com):
   - The first upload is manual: on the Marketplace, choose **Upload plugin** and upload the zip from step 2. JetBrains reviews new plugins before listing them.
   - For later versions, create a token under **Profile → My Tokens** and add it as a repository secret called `PUBLISH_TOKEN`. The Release workflow then publishes each new release automatically.
   - Optional: to [sign the plugin](https://plugins.jetbrains.com/docs/intellij/plugin-signing.html), also add `CERTIFICATE_CHAIN`, `PRIVATE_KEY` and `PRIVATE_KEY_PASSWORD` secrets.

## Installing a build by hand

**Settings → Plugins → ⚙️ → Install Plugin from Disk…**, then pick the zip from `build/distributions/` or from a GitHub release.
