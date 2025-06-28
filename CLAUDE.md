# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Build Commands

- **Build Plugin:** `./gradlew build`
- **Run Format:** `./gradlew ktlintFormat`
- **Run Tests:** `./gradlew check`
- **Run Verification:** `./gradlew runPluginVerifier`
- **Clean Build:** `./gradlew clean`

## Project Architecture

The dprint-intellij project is an IntelliJ plugin that integrates the dprint code formatter (https://dprint.dev/) into
IntelliJ IDEs. The plugin allows users to format their code using dprint directly from the IDE.

### Key Components

1. **DprintService** - Central service that manages the dprint process and coordinates formatting requests
    - Handles initialization of the appropriate editor service based on dprint schema version
    - Manages a cache of files that can be formatted
    - Maintains service state (`UNINITIALIZED`, `INITIALIZING`, `READY`, `ERROR`)
    - Provides both callback and coroutine-based formatting APIs
    - Thread-safe using `AtomicReference<ServiceStateData>`

2. **DprintTaskExecutor** - Handles background task execution and coordination
    - Implements `CoroutineScope` for modern Kotlin coroutines
    - Uses `Channel<QueuedTask>` for task queuing with deduplication
    - Ensures only one task of each type runs at a time
    - Integrates with IntelliJ's `ProgressManager` for UI feedback
    - Handles timeouts and proper cancellation support

3. **EditorService Implementations**
    - `EditorServiceV4` and `EditorServiceV5` - Implement different versions of the dprint editor service protocol
    - `EditorServiceInitializer` - Detects schema version and creates appropriate service with improved error handling
    - `EditorServiceCache` - LRU cache for `canFormat` results to improve performance
    - Handle communication with the dprint CLI process with better config discovery and logging

4. **DprintExternalFormatter** - Integration with IntelliJ's external formatter API
    - Determines if a file can be formatted by dprint
    - Creates `DprintFormattingTask` instances for eligible files
    - Integrates with `AsyncDocumentFormattingService`

5. **Configuration**
    - `ProjectConfiguration` - Project-level settings (stored in .idea/dprintProjectConfig.xml)
    - `UserConfiguration` - User-level settings (stored in .idea/dprintUserConfig.xml)
    - `DprintConfigurable` - UI for configuring the plugin with reset functionality

6. **Actions**
    - `ReformatAction` - Triggers dprint formatting
    - `RestartAction` - Restarts the dprint editor service
    - `ClearCacheAction` - Clears the format cache

### Data Flow

1. User action (manual format or save) triggers the formatter
2. `DprintExternalFormatter` is used by the internal IntelliJ formatter and keybinds if dprint can format the file
3. If eligible, a `DprintFormattingTask` is created and executed via `DprintService`
4. `DprintService` delegates the task to `DprintTaskExecutor` for background processing
5. The task is executed by the appropriate `EditorService` implementation (V4 or V5)
6. The dprint CLI daemon formats the file and returns the result
7. The formatted content is applied to the document

## Development Notes

1. The plugin is developed using Kotlin and targets IntelliJ 2024.3+
2. JDK 17 is required for development
3. To test the plugin locally:
    - Install dprint CLI (`brew install dprint` on macOS)
    - Run `dprint init` to create a default config
    - Use the "Run IDE with Plugin" run configuration

4. Plugin requires a working dprint executable and config file. It can:
    - Auto-detect dprint in PATH or node_modules
    - Auto-detect config in project root
    - Accept custom paths for both executable and config

5. The plugin supports both dprint schema v4 and v5

6. The plugin uses Kotlin coroutines for background task processing
    - Uses `DprintTaskExecutor` with `Channel<QueuedTask>` for managing asynchronous operations
    - Properly handles task cancellation, timeouts, and deduplication
    - Integrates with IntelliJ's progress system for UI feedback

## Recent Improvements (v0.9.0)

### Major Architectural Refactoring
- **Coroutines migration** - Complete rearchitecture from callback-based to coroutine-based asynchronous operations for improved CPU utilization
- **New service architecture** - Introduced `DprintService` as central coordinator and `DprintTaskExecutor` for background task management using Kotlin coroutines
- **Editor service refactoring** - Split monolithic `EditorServiceManager` into focused components: `EditorServiceInitializer`, `EditorServiceCache`, and improved V4/V5 implementations
- **State management overhaul** - New `BaseConfiguration` abstraction for type-safe configuration management

### Range Formatting Enhancements
- **Range formatting re-implementation** - Added `DprintRangeFormattingTask` with improved character encoding handling
- **Fixed character encoding issues** - Resolved problems with special characters causing incorrect range calculations
- **Better integration** - Improved range formatting integration with IntelliJ's formatting system

### Performance & Reliability Improvements
- **Task queue optimization** - New `Channel<QueuedTask>` based system with deduplication and proper cancellation support
- **Caching improvements** - Enhanced `EditorServiceCache` with LRU caching for `canFormat` results
- **Timeout handling** - Better timeout management and error recovery throughout the plugin
- **Memory management** - Improved resource cleanup and lifecycle management

### Developer Experience Enhancements  
- **Enhanced error handling** - Fixed JSON parsing errors with graceful handling for empty `dprint editor-info` output
- **Improved config discovery** - Better working directory handling and config file detection with user-friendly logging
- **Reset to defaults functionality** - Replaced restart button with comprehensive reset functionality covering all configuration settings
- **Better logging** - User-friendly messages showing which config files are being used and actionable error guidance
- **Progress integration** - Better integration with IntelliJ's progress system for background operations

### Technical Updates
- **Dependency updates** - Updated all dependencies for 2025, including Gradle foojay-resolver-convention plugin (0.7.0 → 1.0.0)
- **IntelliJ platform updates** - Updated to target IntelliJ 2024.3+ with latest platform APIs
- **Deprecated code removal** - Cleaned up deprecated class usage and modernized codebase
- **Test improvements** - Added comprehensive test suite for new architecture including `DprintServiceUnitTest`, `EditorServiceCacheTest`, and improved V5 service tests

### Configuration & UI Improvements
- **Configuration persistence** - Better state management with separate project and user configurations
- **Bundle message improvements** - Enhanced localized messages for better user experience
- **Git ignore updates** - Improved `.gitignore` for better development workflow