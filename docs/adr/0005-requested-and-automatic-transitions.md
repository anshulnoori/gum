# Named commands request transitions; automatic transitions are declared apart

Status: accepted. Amended by [ADR 0006](0006-plain-ivy-composition.md), which removes the refusal log and the refused outcome described below, and [ADR 0007](0007-subsystems-made-of-regions.md), which moves states, requests, and automatic transitions into a subsystem's regions.

## Decision

A subsystem is in one **state** at a time and changes state by a **transition**, following UML state machines. `Subsystem<S>` has `onEntry(state)`, which sets outputs on entry, and `during(state)`, which runs every update while in a state.

Named commands are a subsystem's whole API. `request`, `hold`, and `reach` are protected; a subsystem exposes commands such as `collect()` and `collecting()`.
There is no shared allow-list of transitions. A command that is valid only from certain states says so: `request(State.HIGH, from = State.MID)`. A request's block is the transition's **effect**, which runs before the target's `onEntry`.

A refused request is never silent (reversed by ADR 0006). Gum hooks KStateMachine's `IGNORED` result and passes a message to `ProgramScope.onRefused` callbacks; the FTC host logs it to RobotLog and the Driver Station's telemetry log. A refused request also ends with the refused outcome, which a routine's `await` returns.

Transitions the subsystem takes by itself go in their own block: `automatic { A to B whenever { condition } effect { … } }`. Gum checks them once per update, after commands. Conditions must only read. The first declared match wins, and at most one is taken per update.

Ivy requirements stay the ownership model. Automatic transitions are not commands and take no requirement. If an automatic transition leaves the state a `hold` asked for, that hold ends.

## Consequences

A student cannot turn every request into a silent no-op by declaring one automatic rule, which the old `transitions` block did.
PedroDrive mirrored the follower with `anyState` automatic transitions and had no requested transitions. It was later removed; Pedro is now a TeamCode integration ([lifecycle](../lifecycle.md#using-pedro)).
Programs that called `hold(mode)` directly now call a named hold command.

## Alternatives

Keeping an allow-list beside the automatic block was dropped: named commands already decide which requests exist.
The design draws on UML 2.5.1, KStateMachine 0.38.1, XState v5, MercurialFTC 2, FRC 254 (2019, 2022), FRC 6328 (2024, 2025), and WPILib Commands v3.
