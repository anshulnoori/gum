# Programs chain by return value; Ivy requirements stay the ownership model

Status: accepted. Amended by [ADR 0006](0006-plain-ivy-composition.md): routines are removed, so read "routine" below as an Ivy group, which reserves its children's requirements. Amended by [ADR 0007](0007-subsystems-made-of-regions.md): commands reserve a subsystem's region, not the whole subsystem.

## Decision

A program's setup can end with the program to run next. After a run that started stops, the host initializes that program, which waits for its own START.
Every run belongs to a chain. A run started from the Driver Station starts a new chain, and each chain has a Metro `ChainScope` graph that every run's `RobotGraph` extends, so values such as a pose last for the whole chain.
This replaces the earlier success-only `onSuccess` handoff design.

Ivy requirements remain how Gum decides who controls a subsystem. Subsystem commands (built with `request` and `hold`) reserve their subsystem, and routines reserve everything they await.

## Consequences

Chains do not depend on operation outcomes; a manual STOP after START also chains.
Chain values must not hold hardware, because each run gets its devices again.
A small request can interrupt a larger routine that reserves the same subsystem. Teams shape this with routine granularity, Ivy priorities, and `ConflictBehavior`.

## Alternatives

MercurialFTC re-registers the same OpMode name with the next program; Gum uses the next program's own name and kind so the Driver Station timer matches it.
Dropping requirements so that subsystems arbitrate requests themselves, as MercurialFTC does, was proposed and rejected: requirements model a robot's physical resources directly.
`request(state)` leaves a state in place after its command ends, which is less Ivy-like than `hold(state)`. Both stay until porting apex-2025 shows which autos need.
