# Gum

Gum v2 is a Kotlin-first framework for FIRST Tech Challenge robot programs.
It connects functional OpModes, lifecycle-scoped dependency injection, controller events, and state-machine subsystems.

**Status: the first JVM lifecycle slice is implemented and tested. No FTC integration or published artifact exists yet.**

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

The wrapper pins Gradle 9.1.0 and verifies its distribution checksum.
The JVM module uses Kotlin 2.3.20 and targets Java 8 bytecode.
These pins support this slice. They do not certify the future Android dependency combination.

In a Debian-based orb, `.agents/setup` installs JDK 17 and runs the tests.
FTC device tests remain necessary before competition use.
