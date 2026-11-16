# Gum owns subsystem state machines and the robot graph

Status: accepted. Supersedes the Metro choice in ADR 0002 and amends ADR 0001 and the design's non-goals.

Team code should read as mechanisms and commands, not framework wiring.
After writing an intake against raw KStateMachine and Metro, the user chose to move both behind Gum.

## Decision

Subsystems extend `Subsystem<Mode>`: an enum of modes, an `enter(mode)` that sets outputs, commands built with `request(mode)`, and an optional `transitions { }` block.
Gum builds the KStateMachine machine from these, takes automatic moves after Ivy each update, and returns every subsystem to its initial mode at STOP.
Team code does not use KStateMachine directly.

Programs receive a `Robot` per session. Team code declares each subsystem once, as `val Robot.intake by subsystem(::Intake)`.
Subsystems find their own devices through constructor defaults such as `hardware(RobotMap.intakeLeft)`; tests pass fakes instead.
Gum, not a DI library, creates subsystems and scopes them to the session.

## Consequences

Metro, its annotations, and its JDK 21 Gradle requirement are gone. TeamCode keeps the template's Java 8 target.
A missing subsystem dependency is a missing constructor argument, so it still fails to compile.
Subsystems are created on first use, which can be during an update; Gum updates and resets them wherever they were created.
`hardware` and subsystem registration rely on the OpMode thread; using either elsewhere fails with a message.
`Subsystem` is a required Gum DSL, which the design previously listed as a non-goal.
Nested modes, timeouts, and operation outcomes extend `Subsystem` when a mechanism needs them.

## Alternatives

Generating the robot graph with a Gum KSP processor would let programs skip the extension properties.
The user judged that not worth a processor; Gum glue is enough.
