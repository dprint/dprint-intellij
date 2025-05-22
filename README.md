# dprint

<!-- Plugin description -->
This plugin adds support for dprint, a flexible and extensible code formatter ([dprint.dev](https://dprint.dev/)). It is
in active early development, please report bugs and feature requests to
our [github](https://github.com/dprint/dprint-intellij/issues).

To use this plugin:

- Install and configure dprint for your repository, [dprint.dev/setup](https://dprint.dev/setup/)
- Configure this plugin at `Preferences` -> `Tools` -> `dprint`.
    - Ensure `Enable dprint` is checked
    - To run dprint on save check `Run dprint on save`.
    - To enable overriding the default IntelliJ formatter check `Default formatter override`. If a file can be
      formatted via dprint, the default IntelliJ formatter will be overridden and dprint will be run in its place when
      using <kbd>Option+Shift+Cmd+L</kbd> on macOS or <kbd>Alt+Shift+Ctrl+L</kbd> on Windows and Linux.
    - To enable verbose logging from the underlying dprint daemon process check `Verbose daemon logging`
    - If your `dprint.json` config file isn't at the base of your project, provide the absolute path to your config in
      the `Config file path` field, otherwise it will be detected automatically.
    - If dprint isn't on your path or in `node_modules`, provide the absolute path to the dprint executable in
      the `Executable path` field, otherwise it will be detected automatically.
- Use the "Reformat with dprint" action by using the "Find Action" popup (<kbd>Cmd/Ctrl+Shift+A</kbd>).
- Output from the plugin will be displayed in the dprint tool window. This includes config errors and any syntax errors
  that may be stopping your file from being formatted.

This plugin uses a long-running process known as the `editor-service`. If you change your `dprint.json` file outside of
IntelliJ or dprint is not formatting as expected, run the `Restart dprint` action or in `Preferences` -> `Tools` ->
`dprint` click the `Restart` button. This will force the editor service to close down and restart.

Please report any issues with this Intellij plugin to the
[github repository](https://github.com/dprint/dprint-intellij/issues).
<!-- Plugin description end -->

## Installation

- Manually:

  Download the [latest release](https://github.com/dprint/dprint-intellij/releases/latest) and install it
  manually using
  <kbd>Settings/Preferences</kbd> > <kbd>Plugins</kbd> > <kbd>⚙️</kbd> > <kbd>Install plugin from disk...</kbd>

---

## Development

This project is currently built using JDK 17. To install on a mac with homebrew run `brew install java` and set that
be your project SDK.

### Dprint setup

To test this plugin, you will require dprint installed. To install on a mac with homebrew run `brew install dprint`. 
When running the plugin via the `Run Plugin` configuration, add a default dprint config file by running `dprint init`.

### Intellij Setup

- Set up linting settings, run <kbd>Gradle</kbd> > <kbd>Tasks</kbd> > <kbd>help</kbd> > <kbd>ktlintGernateBaseline</kbd>.
  This sets up intellij with appropriate formatting settings.

### Running

There are 3 default run configs set up

- <kbd>Run Plugin</kbd> - This starts up an instance of Intellij Ultimate with the plugin installed and enabled.
- <kbd>Run Tests</kbd> - This runs linting and tests.
- <kbd>Run Verifications</kbd> - This verifies the plugin is publishable.

Depending on the version of IntellJ you are running for development, you will need to change the `platformType` property
in `gradle.properties`. It is IU for IntelliJ Ultimate and IC for IntelliJ Community.
