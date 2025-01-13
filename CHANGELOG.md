<!-- Keep a Changelog guide -> https://keepachangelog.com -->

# dprint Changelog

## [Unreleased]

- Update plugin name for marketplace verification

## 0.8.1 - 2024-12-06

- Reinstate require restart, even though the plugin doesn't technically need it, it avoids issues that a restart would
  fix.

## 0.8.0 - 2024-12-05

- Removed json validation of config file
- Set minimum versions to 2024
- Updated to newer build tooling

## 0.7.0 - 2024-07-20

- Fixed an issue where initialisation could get into an infinite loop.
- Default to the IntelliJ formatter when the canFormat cache is cold for a file.
- Stop self-healing restarts and show error baloons every time a startup fails.

## 0.6.0 - 2024-02-08

- Fixed issue where on save actions were running twice per save.
- Enforce that only a initialisation or format can be queued to run at a time.
- Added checkbox to `Tools -> Actions on Save` menu.
- Added notification when the underlying dprint daemon cannot initialise.
- Fixed an issue where an error could be thrown when closing or changing projects due to a race condition.
- Added timeout configuration for the initialisation of the dprint daemon and for commands.

## 0.5.0 - 2023-12-22

- Add check for dead processes to warn users that the dprint daemon is not responding
- Increase severity of logging in the event processes die or errors are seen in process communication
    - This may be a little noisy, and if so disabling the plugin is recommended unless the underlying issue with the
      process can be fixed
    - For intermittent or one off errors, just restart the dprint plugin via the `Restart dprint` action
- Upgrade dependencies
- Attempt to fix changelog update on publish

## 0.4.4 - 2023-11-23

- Fixed issue for plugins that require the underlying process to be running in the working projects git repository.

## 0.4.3 - 2023-10-11

- Update to latest dependencies

## 0.4.2

- Fix issue with run on save config not saving

## 0.4.1

- Fix null pointer issue in external formatter

## 0.4.0

- Run dprint after Eslint fixes have been applied
- Ensure dprint doesn't attempt to check/format scratch files (perf optimisation) or diff views
- Add verbose logging config
- Add override default IntelliJ formatter config

## 0.3.9

- Fix issue where windows systems reported invalid executables
- Add automatic node module detection from the base project path
- Add 2022.2 to supported versions

## 0.3.8

- Fix issue causing IntelliJ to hang on shutdown

## 0.3.7

- Performance improvements
- Invalidate cache on restart

## 0.3.6

- Fix issue where using the IntelliJ formatter would result in a no-op on every second format, IntelliJ is reporting
  larger formatting ranges that content length and dprint would not format these files
- Better handling of virtual files
- Silence an error that is thrown when restarting dprint
- Improve verbose logging in the console
- Add a listener to detect config changes, note this only detects changes made inside IntelliJ

## 0.3.5

- Fix issue when performing code refactoring

## 0.3.4

- Reduce timeout when checking if a file can be formatted in the external formatter
- Cache whether files can be formatted by dprint and create an action to clear this
- Remove custom synchronization and move to an IntelliJ background task queue for dprint tasks (this appears to solve
  the hard to reproduce lock up issues)

## 0.3.3

- Handle execution exceptions when running can format
- Ensure on save action is only run when on user triggered saves

## 0.3.2

- Fix intermittent lock up when running format

## 0.3.1

- Fix versioning to allow for 2021.3.x installs

## 0.3.0

- Introduced support for v5 of the dprint schema
- Added a dprint tool window to provide better output of the formatting process
- Added the `Restart Dprint` action so the underlying editor service can be restarted without needed to go to
  preferences
- Removed the default key command of `cmd/ctrl+shift+option+D`, it clashed with too many other key commands. Users can
  still map this manually should they want it.

## 0.2.3

- Support all future versions of IntelliJ

## 0.2.2

- Fix error that was thrown when code refactoring tools were used
- Synchronize the editor service so two processes can't interrupt each others formatting
- Reduce log spam

## 0.2.1

- Fix issues with changelog

## 0.2.0

- Added support for the inbuilt IntelliJ formatter. This allows dprint to be run at the same time as optimizing imports
  using `shift + option + command + L`

## 0.1.3

- Fix issue where the inability to parse the schema would stop a project form opening.

## 0.1.2

- Release first public version of the plugin.
