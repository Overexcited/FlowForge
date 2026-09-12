# FlowForge

FlowForge is a native Android visual flowchart editor prototype built with the Android Canvas API. It is designed as a touch-first editor rather than a text/code-based diagramming tool.

## Current editor features

- Infinite-style canvas with pan and pinch zoom.
- Optional background grid and adjustable grid spacing.
- Optional snap-to-grid while moving and resizing objects.
- Ten built-in object shapes: rectangle, rounded rectangle, diamond, oval, parallelogram, cylinder, document, hexagon, cloud and circle.
- Standard semantic element types: process, decision, terminal, data, server, text and note.
- Eight resize handles on selected objects, including corner/diagonal handles.
- Selected-object contextual action bar and an in-canvas action button.
- Clone places an independent copy into nearby free space without copying connections.
- Save any selected element as a persistent **Building Block**. Building Blocks contain the element definition only, not its connections.
- Insert and manage saved Building Blocks from the main toolbar.
- Element labels plus non-cluttering metadata/notes indicators.
- Connection labels, notes, arrow styles, line styles and adjustable bends.
- Multiple independent connections between the same two objects.
- Atomic Undo/Redo history with a 2,000-operation bound. Continuous dragging is recorded as one move operation rather than hundreds of touch events.
- Diagram templates (20 starter templates).
- FlowForge JSON import/export.
- Mermaid import/export.
- PDF and JPG export, with an independent option to include/exclude the grid.
- Android document/file pickers for import/export.
- Settings for grid, snapping, grid spacing, dark canvas, fit-to-screen and Building Block management.

## Architecture

The app deliberately uses only Android framework APIs and Kotlin; no external runtime/service is required. The main pieces are:

- `FlowModel.kt` — diagram model and native JSON format.
- `History.kt` — bounded atomic undo/redo manager.
- `Assets.kt` — persistent Building Block definitions.
- `FlowCanvasView.kt` — touch/zoom/pan/selection/resize/rendering engine.
- `MainActivity.kt` — editor UI, dialogs, file operations and command integration.
- `Templates.kt` — starter whole-diagram templates.
- `Mermaid.kt` — Mermaid import/export.

A GitHub Actions workflow is included at `.github/workflows/android-build.yml` to build the release APK on GitHub without requiring a local Gradle installation.

## Release builds and upgrade compatibility

FlowForge is built as the **release** variant by the included GitHub Actions workflow. It is not a debug APK: the release build is explicitly non-debuggable and is signed with the fixed test signing key stored at the repository root as `debug.keystore`.

This is intentionally a **test/development signing key**, not a production signing key. Its credentials use the conventional Android debug-key credentials (`androiddebugkey` / `android`), so the key is suitable for a personal/test distribution but must not be treated as a secret production signing key.

The application ID remains `com.flowforge.app`, and releases must keep using the same signing key and a monotonically increasing `versionCode` so a newer APK can be installed over an older FlowForge APK. The current project version is **0.2.4** (`versionCode 24`).
