# Gum

Gum v2 is a Kotlin-first framework for FIRST Tech Challenge robot programs.
It connects functional OpModes, lifecycle-scoped dependency injection, controller events, and state-machine subsystems.

**Status: the JVM lifecycle, a minimal FTC host, hardware configuration from code, and Ivy scheduling are implemented, and apex-2026 runs a KStateMachine intake on them. They compile into an FTC SDK 12.0 app but have not run on a robot. Operation outcomes, controller events, handoff, and Sloth are not implemented, and no artifact is published.**

This repository starts with independent Git history. The previous implementation remains in [old-gum](https://github.com/anshulnoori/old-gum).

## Direction

- Ivy owns commands, composition, and subsystem reservations.
- KStateMachine owns subsystem state machines.
- Gum builds subsystem state machines and creates subsystems per session; see [ADR 0003](docs/adr/0003-gum-owned-subsystems-and-robot.md).
- OpModes are values whose setup bodies configure behavior. Gum owns execution.
- Sloth supplies development reload and registration integration.
- Coroutines are optional. The control loop does not launch subsystem jobs on a thread pool.
- Successful autonomous completion immediately initializes its TeleOp successor, without starting it.

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
The modules use Kotlin 2.3.20 and Ivy 1.1.1. `:core` targets Java 17 bytecode because KStateMachine 0.38.1 does; the other modules target Java 8. `:ftc` uses AGP 8.13.2 and compiles against FTC SDK 12.0.0.
Consumers can build Gum from source with a Gradle `includeBuild`; the [apex-2026](https://ampcode.com/@anshulnoori/apex-2026) robot does this.

In a Debian-based orb, `.agents/setup` installs JDK 17 and runs the tests.
FTC device tests remain necessary before competition use.
