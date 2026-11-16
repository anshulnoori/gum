# Gum owns subsystem state machines and creates the Metro robot graph

Status: accepted. Amends ADR 0001, ADR 0002, and the design's non-goals.

Team code should read as mechanisms and commands, not framework wiring.
After writing an intake against raw KStateMachine and Metro, the user chose to move the state machine behind Gum and to have Gum create the Metro graph.

## Decision

Subsystems extend `Subsystem<Mode>`: an enum of modes, an `enter(mode)` that sets outputs, commands built with `request(mode)`, and an optional `transitions { }` block.
Gum builds the KStateMachine machine from these, takes automatic moves after Ivy each update, and returns every subsystem to its initial mode at STOP.
Team code does not use KStateMachine directly.

Metro remains the DI library. TeamCode declares one graph that lists every subsystem:

```kotlin
@DependencyGraph(OpModeScope::class)
interface RobotGraph : Robot {
    val intake: Intake
}
```

Subsystems are `@Inject @SingleIn(OpModeScope::class)` classes with one constructor.
They find their own devices through constructor defaults such as `hardware(RobotMap.intakeLeft)`; Metro uses a default when the graph has no binding for that parameter, and tests pass fakes instead.
The Gum Gradle plugin generates `teleop`, `autonomous`, and `utility` in the package of the `RobotConfig` object, typed to the `RobotGraph` there, so programs read `teleop("Main") { robot -> }`. Each INIT creates a new graph.

## Consequences

Programs and hand-written TeamCode never call `createGraph`. Metro replaces that intrinsic only in source it compiles, so the generated builders, which compile with TeamCode, call it.
TeamCode's graph must be named `RobotGraph`, live in the `RobotConfig` object's package, and declare no factory. Session inputs come from `hardware` and the `Robot` supertype, not factory parameters.
A missing subsystem dependency still fails to compile.
Metro needs Gradle to run on JDK 21 or newer, and TeamCode targets Java 11 because Metro's runtime inlines Java 11 bytecode.
`hardware` and subsystem registration rely on the OpMode thread; using either elsewhere fails with a message.
`Subsystem` is a required Gum DSL, which the design previously listed as a non-goal.
Nested modes, timeouts, and operation outcomes extend `Subsystem` when a mechanism needs them.

## Alternatives

Generating the robot graph itself with a Gum KSP processor would remove `RobotGraph` from TeamCode. The user judged that not worth a processor.
Generic builders such as `teleop<RobotGraph>(...)` worked by finding Metro's generated companion through reflection; the user rejected the type argument.
A Gum-created `Robot` with `val Robot.intake by subsystem(::Intake)` properties replaced Metro briefly without the user's approval and was reverted.
