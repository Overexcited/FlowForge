FlowForge v0.2.7 — drag-vs-selection fix

Basis: current `main` source from Overexcited/FlowForge, which is currently v0.2.6 / versionCode 26.

Requested behavior:
- A stationary single tap selects an element or connector normally.
- A drag gesture moves an element without selecting it on touch-down.
- Dragging/panning over a connector does not select that connector.
- Existing selection is left unchanged during a drag; dragging does not invoke selection.
- Double-tap behavior remains available when the gesture is a stationary tap.
- No other editor behavior is intentionally changed.

The Kotlin source change is supplied as an exact unified patch against the current repository source. The four version-bearing files are supplied as complete replacements.
