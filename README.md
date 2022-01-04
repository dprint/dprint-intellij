# dprint-intellij-plugin

<!-- Plugin description -->
This plugin adds support for dprint, a flexible and extensible code formatter ([dprint.dev](https://dprint.dev/)).

To use this plugin:
- Install and configure dprint for your repository, [dprint.dev/setup](https://dprint.dev/setup/)
- Configure this plugin at `Preferences` -> `Tools` -> `Dprint`.
  - Ensure `Enable Dprint` is checked
  - If your config file isn't at the base of your project, provide the absolute path to your config in the "Dprint configuration json file location" field, otherwise it will be detected automatically.
  - If dprint isn't on your path, provide the absolute path to the dprint executable in the "Dprint executable location" field, otherwise it will be detected automatically.
  - To run dprint on save tick the "Run dprint formatter on save" config checkbox.
- Use the "Reformat with dprint" action (Alt-Shift-Cmd-D on macOS or Alt-Shift-Ctrl-D on Windows and Linux) or find it using the "Find Action" popup (Cmd/Ctrl-Shift-A)

This plugin uses a long running process known as the `editor-service`. If you change your `dprint.json` file or dprint is not formatting as expected, in `Preferences` -> `Tools` -> `Dprint` click the `Reload` button. This will force the editor service to close down and reload.

Please report any issues with this Intellij plugin to the [github repository](https://github.com/dprint/dprint-intellij/issues).
<!-- Plugin description end -->

## Installation

- Manually:

  Download the [latest release](https://github.com/ryan-rushton/dprint-intellij-plugin/releases/latest) and install it
  manually using
  <kbd>Settings/Preferences</kbd> > <kbd>Plugins</kbd> > <kbd>⚙️</kbd> > <kbd>Install plugin from disk...</kbd>

---

## Development

This project is currently built using JDK 11. To install on a mac with homebrew run `brew install java11` and set that
be your project SDK.

### Intellij Setup

- Set up linting settings, run <kbd>Gradle</kbd> > <kbd>Tasks</kbd> > <kbd>help</kbd> > <kbd>ktlintApplyToIdea</kbd>.
  This sets up intellij with appropriate formatting settings.

### Running

There are 3 default run configs set up

- <kbd>Run Plugin</kbd> - This starts up an instance of Intellij Ultimate with the plugin installed and enabled.
- <kbd>Run Tests</kbd> - This runs linting and tests.
- <kbd>Run Verifications</kbd> - This verifies the plugin is publishable.
