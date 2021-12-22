# dprint-intellij-plugin

<!-- Plugin description -->
A dprint plugin for Intellij. Formats code using the dprint CLI.
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
