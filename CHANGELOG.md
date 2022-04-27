<!-- Keep a Changelog guide -> https://keepachangelog.com -->

# dprint-intellij-plugin Changelog

## [Unreleased]
- Support all future versions

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
