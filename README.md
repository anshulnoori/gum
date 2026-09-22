# Gum

Gum v2 is a Kotlin-first framework for FIRST Tech Challenge robot programs.
It connects functional OpModes, lifecycle-scoped dependency injection, controller events, and state-machine subsystems.

**Status: design baseline. No runnable implementation or published artifact exists yet.**

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

The first implementation slice covers the public OpMode lifecycle with fake dependencies and a controllable host.
FTC device tests remain necessary before competition use.
