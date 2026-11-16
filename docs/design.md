# Gum v2 design

This specification records decisions from the design discussion.
Accepted behavior, provisional technology, and proposed syntax have different status.
No source-only review establishes device compatibility or competition readiness.

## Goals

- Provide responsive, predictable FTC robot control.
- Keep user code command-first and light on custom DSL syntax.
- Support complex hierarchical subsystem states through KStateMachine.
- Integrate dependency lifetimes with OpMode lifetimes.
- Preserve fast development through Sloth reload.
- Prepare a usable competition baseline before expanding tooling.

The first event date, robot hardware, and performance targets remain unknown.

## Accepted responsibilities

| Owner | Responsibility |
| --- | --- |
| Gum | Program descriptors, lifecycle, controller events, execution ordering, shutdown, integration diagnostics |
| Ivy | Commands, requirements, priorities, conflict policies, sequence/parallel/race/deadline composition |
| KStateMachine | Subsystem hierarchy, guards, transitions, entry/exit behavior, concurrent state regions |
| DI library | Compile-time graph validation and generated construction |
| Subsystem | Hardware ownership, control algorithms, valid operations, readiness, faults, safe responses |
| Sloth | Development class reload and registration facilities |

Metro is the DI library. Gum creates the robot graph at every INIT and builds subsystem state machines ([ADR 0003](adr/0003-gum-owned-subsystems-and-robot.md)). KSP is not a requirement.
Kotlinx.coroutines is the optional coroutine library, not the control clock.
No dependency versions are frozen until a consumer application compiles and the relevant contracts pass tests.

## Program model

An OpMode is a value that stores metadata, a dependency factory, and a setup body.
Top-level initialization must not create hardware, sessions, coroutine scopes, or subsystem graphs.
The host invokes the dependency factory during INIT and passes its typed graph to the setup body.

The setup body configures behavior. Its return does not stop the session.
The host continues execution until STOP or a fatal error; a program can then chain to the next one.
Ordinary program setup does not require suspension or an `awaitStop()` call.

Program kinds include TeleOp, autonomous, and utility.
Names, groups, and descriptions belong to metadata.
Descriptions need a Gum display surface unless the selected FTC SDK supports them.

The following syntax illustrates intent. Names and signatures remain proposed.

```kotlin
val main = teleop(
    name = "Main",
    group = "Competition",
    description = "Driver controls",
    dependencies = ::createRobotGraph,
) { robot ->
    controls.a.onPress {
        robot.lift.moveTo(HIGH)
            .then(robot.claw.open())
            .schedule()
    }
}
```

## Lifecycle

| Phase | Contract |
| --- | --- |
| INIT | Create a fresh graph, resolve required hardware, configure bindings and registered periodic work |
| INIT updates | Permit explicit initialization behavior; normal driver command bindings remain gated |
| START | Enable driver bindings and schedule startup routines |
| Active updates | Advance control-affecting work on the robot-loop thread |
| Shutdown | Reject new work, cancel routines, apply safe responses, close resources, release graph references |
| Next program INIT | After a started run stops, initialize the program setup ended with; await a separate START |

Homing, vision warmup, and selection menus need explicit initialization behavior.
Constructors must not schedule commands or move actuators.
Initialization failures require cleanup of resources already acquired.
A cleanup failure must not prevent attempts to make other mechanisms safe.
The exact cleanup order and error-reporting interface need implementation tests.

## Program chaining

A program's setup can end with the program to run next, so programs chain: `autonomous(...) { ...; driver { robot.drive.pose } }`.
The user chose this over an `onSuccess` hook, after MercurialFTC's `exec(): Program?`.

When a run that started stops, by Driver Station STOP or the autonomous timer, the host puts the next program into INIT under its own name and kind and waits for a separate START.
A run stopped during INIT chains nothing. Safe shutdown completes before the next graph exists.
Data passes through closures the next program reads during its setup, such as a pose. They must not keep old subsystems working.

SDK support for initializing an OpMode from `stop()` requires a device test and a current rules review.

## Dependency ownership

Each OpMode invocation receives a fresh graph.
Subsystems share one instance per graph. Commands are fresh values created when their factories run.
Qualified providers resolve individual motors and sensors through HardwareMap during INIT.
Compile-time validation proves provider availability, not physical device presence.

Gum receives explicitly registered periodic and disposable resources, potentially through DI multibindings.
The registration interface is not selected yet.
DI scopes govern instance reuse. Gum, not the DI annotation, governs shutdown and resource disposal.
Framework objects must not retain TeamCode instances across reload.

## Commands and subsystem machines

Subsystems expose ordinary Kotlin command factories, such as `lift.moveTo(height)`.
Commands declare requirements and request operations. They do not bypass the machine to force internal states.
KStateMachine evaluates valid transitions and owns ongoing mechanism behavior.
Entry actions remain short; later sensor updates report physical progress.
Command completion does not stop continuous holding behavior.

An operation has a request-specific outcome.
Success, rejection, interruption, timeout, and fault remain distinguishable.
A state such as Holding is not sufficient proof that a particular movement succeeded.
A failed step must not advance a success-dependent routine.
The representation and composition of these outcomes remain to be implemented.

## Reservations and cancellation

A group reserves the union of its child requirements for its entire lifetime.
This includes resources used only by later sequential steps.
Reservations prevent competing scheduled owners, not direct hardware writes.
Hardware remains private to subsystem implementations.

Parallel children with overlapping requirements must be rejected by the supported Gum composition interface.
Sequential children can share requirements.
Raw Ivy interoperability needs a documented validation policy; Gum cannot silently promise checks on code that bypasses it.

Conflict defaults are unresolved: reject, queue, or interrupt.
Normal interruption, fault, and STOP can require different subsystem responses.
Holding current position, completing a previous target, and recovery movement are mechanism-specific decisions.

## Execution and responsiveness

Control-affecting work executes on one robot-loop thread.
There is no per-tick `async(Dispatchers.Default)` fan-out for subsystems.
Controller sampling produces edge events at a defined point in the loop.
Bindings construct fresh commands and share scheduler confinement.

One owner controls manual bulk-cache invalidation and sensor sampling.
Bulk data does not include all I2C sensors or imply simultaneous multi-hub measurements.
The exact order of event delivery, Ivy execution, state transitions, control calculations, and outputs remains open.
That order must avoid stale completion observations and conflicting output owners.

Optional background work returns timestamped data. It does not write motors independently.
Optional coroutine work belongs to the session and cancels on shutdown.
KStateMachine integration must follow its threading and event-processing contracts.
Neither parallel state regions nor Ivy parallel groups require simultaneous execution on different threads.

Performance evidence must include input-to-output latency, sensor age, loop-duration tails, missed deadlines, and STOP response.
No target frequency or fastest-library claim is accepted without representative device measurements.

## Integration risks

- Ivy reset clears scheduler state without command cleanup in the inspected version.
- Ivy lifecycle callbacks can mutate scheduling during an execution pass.
- A rejected Ivy command can lack a terminal callback.
- Ivy suspension is not terminal completion.
- Natural Ivy completion is not necessarily operation success.
- Metro-generated graphs and program registrars must reload with TeamCode.
- Stable objects must release references to previous reload classloaders.
- Dependency changes can require a full APK install rather than Sloth reload.

Development reload and dashboards remain in scope.
Competition packaging and active network services need separate validation against current FIRST rules.
Research is not permission to run development services during matches.

## Non-goals for the first implementation

- A custom coroutine language, command scheduler, statechart engine, or DI compiler
- Automatic injection into annotated top-level OpMode functions
- Automatic TeleOp START after autonomous completion
- Undocumented runtime performance guarantees
- A motion-library migration before adapter requirements and hardware are known

## Decisions still required

- First event date, robot hardware, team workflow, and measurable reliability targets
- Compatible FTC/Kotlin/Gradle/Metro/Sloth/Ivy/KStateMachine versions
- Public lifecycle test interfaces and the dependency/cleanup contract
- Reentrant schedule/cancel policy for commands scheduled inside Ivy's update
- Command outcome propagation (conflicts use Ivy's priority and `ConflictBehavior`)
- State-machine topology for coordinated mechanisms
- Motion/localization integration and its single update owner
- Registration metadata and transition payload interface

The implementation plan resolves these through small executable examples rather than additional speculative framework layers.
