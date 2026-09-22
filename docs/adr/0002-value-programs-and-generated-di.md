# Use value-based programs with compile-time DI

Status: accepted program model; Metro is provisional.

Programs use `val main = teleop(...) { robot -> ... }`, rather than annotated functions with generated argument injection.
Metadata belongs to program descriptors, and each INIT creates a fresh typed dependency graph.
Metro supplies compile-time DI if its generated code works with the selected FTC toolchain and Sloth reload.

## Consequences

Gum does not require KSP or its own source generator for this model.
A reusable FTC host and Sloth registration can execute descriptors through explicit graph factories.
Program discovery can occur at runtime without runtime dependency-graph resolution.
Descriptions are Gum metadata; Driver Station display support is not established.

## Alternatives

The team's apex-2025 project uses kotlin-inject with KSP and explicitly creates its HardwareContainer inside each OpMode.
Kotlin-inject remains the fallback if Metro compatibility fails.
Neither compiler mechanism establishes a runtime performance advantage without measurements.
