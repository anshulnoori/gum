# Gum

Gum v2 is a Kotlin-first framework for FIRST Tech Challenge robot programs.
It connects functional OpModes, lifecycle-scoped dependency injection, controller events, and state-machine subsystems.

**Status: implemented and JVM-tested: the lifecycle, the FTC host as a `LinearOpMode`, Ivy scheduling, KStateMachine subsystems with parallel regions, parent states, history, and feedback control, gamepad events from the SDK callback with button combinators and stick and trigger thresholds, background reads, program chains, hardware configuration from Kotlin with opt-in write caching, Panels telemetry and field drawing, and `debug(program)` entries that show loop and per-phase timing. Implemented but not JVM-tested: the chain-lasting Metro scope and Sloth registration. apex-2026 compiles on it with FTC SDK 12.0. Nothing has run on a robot yet. Programs compose commands with Ivy's groups; faults are not implemented, and no artifact is published.**

This repository starts with independent Git history. The previous implementation remains in [old-gum](https://github.com/anshulnoori/old-gum).

## Direction

- Ivy owns commands, composition, and subsystem reservations.
- KStateMachine owns subsystem state machines.
- Metro is the compile-time DI. Gum builds subsystem state machines and creates the robot graph at every INIT; see [ADR 0003](docs/adr/0003-gum-owned-subsystems-and-robot.md).
- OpModes are values whose setup bodies configure behavior. Gum owns execution.
- Sloth registers programs and supplies development reload.
- Programs compose subsystem commands with Ivy's groups; Gum adds no composition API ([ADR 0006](docs/adr/0006-plain-ivy-composition.md)). Slow sensors read on background threads. Only the OpMode thread writes outputs.
- Gamepad input comes from the SDK's callback, not polling, including combined, debounced, double-press, toggled, and stick or trigger threshold buttons.
- Pedro Pathing is an optional integration in TeamCode, not a Gum module; see [Using Pedro](docs/lifecycle.md#using-pedro).
- Panels shows telemetry, the field, and live-tuned values; Gum sends to it after every update.
- `debug(program)` adds a separate Driver Station entry for any program whose telemetry starts with Gum's loop time and the time each phase of the update takes; see [Debug programs](docs/lifecycle.md#debug-programs).
- A program's setup can end with the next program; after the run stops, Gum initializes it without starting it.

## Design and implementation

- [Design specification](docs/design.md): decisions, proposed interfaces, and unresolved contracts.
- [Implementation plan](docs/implementation-plan.md): ordered slices and acceptance criteria.
- [Domain language](CONTEXT.md): terms used by the project.
- [Architecture decisions](docs/adr/0001-runtime-ownership.md): reasons for the selected runtime model.
- [Research index](docs/research/README.md): source evidence and compatibility limits.
- [Implemented lifecycle](docs/lifecycle.md): executable interface and cleanup contracts.
- [Hardware configuration](docs/hardware.md): robot wiring declared in Kotlin, generated into the FTC configuration.

## Build and test

Install JDK 17. Then run:

```sh
./gradlew :core:test
```

Run `./gradlew :hardware:test` for the hardware configuration.
`:ftc` is an Android library and also needs an Android SDK through `ANDROID_HOME` or `local.properties`.
The Gradle plugin in `gradle-plugin/` is a separate build that FTC apps include through `pluginManagement`.

The wrapper pins Gradle 9.1.0 and verifies its distribution checksum.
The modules use Kotlin 2.3.20 and Ivy 1.1.1. `:core` targets Java 17 bytecode because KStateMachine 0.38.1 does; `:hardware` and `:ftc` target Java 8, and the separately built Gradle plugin targets Java 17. `:ftc` uses AGP 8.13.2 and compiles against FTC SDK 12.0.0.
Consumers can build Gum from source with a Gradle `includeBuild`; the [apex-2026](https://ampcode.com/@anshulnoori/apex-2026) robot does this.
The library modules and the Gradle plugin can also publish to a Maven repository; see [Publishing](docs/publishing.md).

In a Debian-based orb, `.agents/setup` installs JDK 17 and runs the tests.
FTC device tests remain necessary before competition use.

## License

Gum is released under the [MIT License](LICENSE).
