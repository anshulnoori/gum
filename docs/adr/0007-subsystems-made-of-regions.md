# Subsystems are made of regions, with parent states and history

Status: accepted on 2026-11-20. Amends [ADR 0003](0003-gum-owned-subsystems-and-robot.md) and [ADR 0005](0005-requested-and-automatic-transitions.md): a subsystem's states, requests, holds, and automatic transitions now belong to its regions.

## Decision

1. **Every subsystem is a plain `Subsystem` made of one or more regions.** `region(initial) { onEntry { }; onExit { }; during { }; automatic { } }` declares one. There is no separate type for mechanisms with several regions, and no type parameter on `Subsystem`; a one-region mechanism declares one region.
2. **The subsystem is one KStateMachine machine with a parallel root.** Each region is a child of that root, which KStateMachine calls a region. KStateMachine selects every transition, queues transitions requested while another runs, and runs entry and exit actions. The one exception is reapplying outputs after a hub reconnects: Gum calls the entry actions itself, outermost first, and holds requests made meanwhile until it finishes.
3. **Each region is its own Ivy requirement.** `request`, `hold`, and `reach` are protected extensions on `Region`, so commands for different regions of one subsystem run at the same time. A group of commands for several regions requires them all.
4. **Rules between regions** are `guard = { }` on requests and holds, and automatic transitions whose conditions read other regions. A refused request runs no effect.
5. **Parent states** come from a state enum that implements `Nested`. A region is always in a state without children; `isIn(parent)` covers every state inside it, and requests, holds, `from`, `reach`, and automatic transitions on a parent cover them too.
6. **History** is a region option: `History.SHALLOW` or `History.DEEP` makes requesting a parent return to where the region last was inside it. Shallow history uses KStateMachine's history state. Deep history is tracked by Gum, because KStateMachine's deep history misses a move between two grandchildren of one parent. Releasing a hold and STOP return to the initial state itself, whatever history would choose.
7. **KStateMachine state names** carry the region's position, because KStateMachine needs every name in a machine to be unique and two regions may share an enum or a state name.

## Consequences

An intake arm is one subsystem with an arm region and a roller region, so it can be raised and spinning, raised and stopped, lowered and spinning, or lowered and stopped, with a driver holding one while the other changes.
Programs read state from a region: `intake.rollers.state`. Existing subsystems moved from `Subsystem<State>(initial)` and overridden `onEntry` and `during` to `region(initial) { }`.
Gum still checks automatic conditions itself on each update and sends KStateMachine an event only when one is true, because an event per region per update would cost far more than the check.

## Alternatives

A separate `ParallelSubsystem` beside `Subsystem<S>` kept existing code unchanged, but gave two ways to write a mechanism. Keeping `Subsystem<S>` with extra regions made one region arbitrarily the main one.
A subsystem with one requirement for all its regions would have serialized the arm and roller commands, which defeats regions.
