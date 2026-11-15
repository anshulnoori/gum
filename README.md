# Gum

Gum v2 is a Kotlin-first framework for FIRST Tech Challenge robot programs.
It connects functional OpModes, lifecycle-scoped dependency injection, controller events, and state-machine subsystems.

**Status: the JVM lifecycle and a minimal FTC host are implemented. The host compiles into an FTC SDK 12.0 app but has not run on a robot. Ivy, KStateMachine, controller events, handoff, and Sloth are not integrated, and no artifact is published.**

This repository starts with independent Git history. The previous implementation remains in [old-gum](https://github.com/anshulnoori/old-gum).

## Direction

- Ivy owns commands, composition, and subsystem reservations.
- KStateMachine owns subsystem state machines.
- Metro is the provisional compile-time DI choice, pending compatibility tests.
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

## Build and test

Install JDK 17. Then run:

```sh
./gradlew :core:test
```

`:ftc` is an Android library and also needs an Android SDK through `ANDROID_HOME` or `local.properties`.

The wrapper pins Gradle 9.1.0 and verifies its distribution checksum.
Both modules use Kotlin 2.3.20 and target Java 8 bytecode. `:ftc` uses AGP 8.13.2 and compiles against FTC SDK 12.0.0.
Consumers can build Gum from source with a Gradle `includeBuild`; the [apex-2026](https://ampcode.com/@anshulnoori/apex-2026) robot does this.

In a Debian-based orb, `.agents/setup` installs JDK 17 and runs the tests.
FTC device tests remain necessary before competition use.
