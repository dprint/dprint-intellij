<!-- Keep a Changelog guide -> https://keepachangelog.com -->

# dprint-intellij-plugin Changelog

## [Unreleased]

## [0.3.0]
- Introduced support for v5 of the dprint schema
- Added a dprint tool window to provide better output of the formatting process
- Added the `Restart Dprint` action so the underlying editor service can be restarted without needed to go to preferences
- Removed the default key command of `cmd/ctrl+shift+option+D`, it clashed with too many other key commands. Users can still map this manually should they want it.

## [0.2.3]
- Support all future versions of IntelliJ

## [0.2.2]
- Fix error that was thrown when code refactoring tools were used
- Synchronize the editor service so two processes can't interrupt each others formatting
- Reduce log spam

## [0.2.1]
- Fix issues with changelog

## [0.2.0]
- Added support for the inbuilt IntelliJ formatter. This allows dprint to be run at the same time as optimizing imports
  using `shift + option + command + L`

## [0.1.3]
- Fix issue where the inability to parse the schema would stop a project form opening.

## [0.1.2]
- Release first public version of the plugin.