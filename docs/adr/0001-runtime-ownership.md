# Use Ivy commands with KStateMachine subsystems

Status: accepted and implemented; see [lifecycle](../lifecycle.md). Amended by [ADR 0003](0003-gum-owned-subsystems-and-robot.md). This ADR first named kotlinx.coroutines; routines use the standard library's `kotlin.coroutines` instead.

Gum uses Ivy for command composition and whole-group reservations, and KStateMachine for complex subsystem states.
The user prefers command-first Kotlin interfaces over Mercurial's custom continuation builders or a new Gum scheduling language.
Gum owns the FTC lifecycle and integration contracts rather than replacing either engine.

## Consequences

Subsystems expose ordinary command factories. Gum builds their KStateMachine machines from declared states and transitions; see [ADR 0003](0003-gum-owned-subsystems-and-robot.md).
The robot loop serializes control-affecting work. Coroutine work does not establish a second actuator owner.
Ivy's completion and cancellation behavior needs an adapter for operation outcomes and safe shutdown.

## Alternatives

MercurialFTC provides functional registration, lifecycle helpers, and optional continuation/fiber programming.
Its inspected v2 model does not supply the whole-group requirements model selected here.
Its functional OpMode and reload concepts remain useful references.

A custom scheduler or statechart implementation would duplicate upstream responsibilities without a demonstrated need.
