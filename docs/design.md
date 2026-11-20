# Gum v2 design

This specification records decisions from the design discussion.
Accepted behavior, provisional technology, and proposed syntax have different status.
No source-only review establishes device compatibility or competition readiness.

## Goals

- Provide responsive, predictable FTC robot control.
- Keep user code command-first and light on custom DSL syntax.
- Model subsystem states with KStateMachine.
- Integrate dependency lifetimes with OpMode lifetimes.
- Preserve fast development through Sloth reload.
- Prepare a usable competition baseline before expanding tooling.

The first event date, robot hardware, and performance targets remain unknown.

## Accepted responsibilities

| Owner | Responsibility |
| --- | --- |
| Gum | Program descriptors, lifecycle, controller events, execution ordering, shutdown, integration diagnostics |
| Ivy | Commands, requirements, priorities, conflict policies, sequence/parallel/race/deadline composition |
| KStateMachine | Subsystem transitions and their guards, entry and exit behavior, and queueing a transition requested while another runs |
| DI library | Compile-time graph validation and generated construction |
| Subsystem | Hardware ownership, control algorithms, valid operations, readiness, faults, safe responses |
| Sloth | Development class reload and registration facilities |

Metro is the DI library. Gum creates the robot graph at every INIT and builds subsystem state machines ([ADR 0003](adr/0003-gum-owned-subsystems-and-robot.md)). KSP is not a requirement.
Versions are pinned now that apex-2026 compiles and the contracts pass tests; the [README](../README.md#build-and-test) lists them.

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

The implemented syntax follows. The `teleop` builder the Gum Gradle plugin generates supplies the dependency factory ([ADR 0003](adr/0003-gum-owned-subsystems-and-robot.md)); `lift.moveTo` and `claw.open` stand for a team's named commands.

```kotlin
val main = teleop(
    name = "Main",
    group = "Competition",
    description = "Driver controls",
) { robot ->
    gamepad1.a.onPress {
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
| START | Enable driver bindings and schedule startup commands |
| Active updates | Advance control-affecting work on the robot-loop thread |
| Shutdown | Reject new work, run cleanup callbacks, stop background reads, return subsystems to their initial states, then drop scheduled commands without their `end`, release graph references |
| Next program INIT | After a started run stops, initialize the program setup ended with; await a separate START |

Homing, vision warmup, and selection menus need explicit initialization behavior.
Constructors must not schedule commands or move actuators.
Initialization failures require cleanup of resources already acquired.
A cleanup failure must not prevent attempts to make other mechanisms safe.
The exact cleanup order and error-reporting interface need implementation tests.

## Program chaining

A program's setup can end with the program to run next, so programs chain: `autonomous("Red Auto") { robot -> ...; driverTeleOp }`.
The user chose this over an `onSuccess` hook, after MercurialFTC's `exec(): Program?`.

When a run that started stops, by Driver Station STOP or the autonomous timer, the host puts the next program into INIT under its own name and kind and waits for a separate START.
A run stopped during INIT chains nothing. Safe shutdown completes before the next graph exists.
Data that outlives a run, such as a pose, lives in `@SingleIn(ChainScope::class)` values ([ADR 0004](adr/0004-program-chains-and-ivy-ownership.md)). They must not hold hardware or keep old subsystems working.

The FTC host queues the next program when the SDK's stop OpMode initializes ([lifecycle](lifecycle.md#chain-scope)). This requires a device test and a current rules review.

## Dependency ownership

Each OpMode invocation receives a fresh graph.
Subsystems share one instance per graph. Commands are fresh values created when their factories run.
Subsystems look up their motors and sensors through constructor defaults such as `hardware(RobotMap.intakeLeft)` ([ADR 0003](adr/0003-gum-owned-subsystems-and-robot.md)). Gum creates every subsystem during INIT, so the lookups run then.
Compile-time validation proves provider availability, not physical device presence.

Programs register periodic work with `initLoop` and `loop`, cleanup with `onStop`, and slow reads with `background`; subsystems join the session when they are created and Gum updates them ([lifecycle](lifecycle.md)).
DI scopes govern instance reuse. Gum, not the DI annotation, governs shutdown and resource disposal.
Framework objects must not retain TeamCode instances across reload.

## Commands and subsystem machines

Subsystems expose ordinary Kotlin command factories, such as `lift.moveTo(height)`.
Commands declare requirements and request operations. They do not bypass the machine to force internal states.
KStateMachine evaluates valid transitions and owns ongoing mechanism behavior.
Entry actions remain short; later sensor updates report physical progress.
Command completion does not stop continuous holding behavior.

Commands do not report outcomes, and Gum does not log requests that `from` does not allow ([ADR 0006](adr/0006-plain-ivy-composition.md)).
A step that needs an earlier one to succeed checks subsystem state, such as with Ivy's `conditional`; a team that wants a log of skipped requests writes it.
Faults are not modeled.

## Reservations and cancellation

A group reserves the union of its child requirements for its entire lifetime.
This includes resources used only by later sequential steps.
Reservations prevent competing scheduled owners, not direct hardware writes.
Hardware remains private to subsystem implementations.

Parallel children with overlapping requirements must be rejected. Gum has no composition interface of its own; teams compose with Ivy's groups, and the rejection is not implemented yet.
Sequential children can share requirements.
Raw Ivy interoperability needs a documented validation policy; Gum cannot silently promise checks on code that bypasses it.

Conflicts follow Ivy's priorities and `ConflictBehavior` ([ADR 0004](adr/0004-program-chains-and-ivy-ownership.md)). Gum's commands use Ivy's defaults, priority 0 and `OVERRIDE`, so a newer command on the same subsystem interrupts the one that owns it.
Normal interruption, fault, and STOP can require different subsystem responses.
Holding current position, completing a previous target, and recovery movement are mechanism-specific decisions.

## Execution and responsiveness

Control-affecting work executes on one robot-loop thread.
There is no per-tick `async(Dispatchers.Default)` fan-out for subsystems.
Controller sampling produces edge events at a defined point in the loop.
Bindings construct fresh commands and share scheduler confinement.

One owner controls manual bulk-cache invalidation and sensor sampling.
Bulk data does not include all I2C sensors or imply simultaneous multi-hub measurements.
Each active update runs every subsystem's `beforeCommands`, applies button changes and runs their actions, runs `Scheduler.execute()`, then updates each subsystem (at most one automatic transition, then `during`), then runs the program's `loop` callbacks ([lifecycle](lifecycle.md#ivy-commands)). Outputs are written by commands, `onEntry`, and `during`.
That order must avoid stale completion observations and conflicting output owners.

Optional background work returns timestamped data. It does not write motors independently.
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
- Reentrant schedule/cancel policy for commands scheduled inside Ivy's update. The [implementation plan](implementation-plan.md) records Ivy 1.1.1's behavior. Gum's own commands report done only until they end, so Ivy does not end them twice; a policy for raw Ivy commands remains.
- Fault handling (conflicts use Ivy's priority and `ConflictBehavior`)
- State-machine topology for coordinated mechanisms

Settled since this list was written:

- Compatible FTC/Kotlin/Gradle/Metro/Sloth/Ivy/KStateMachine versions: pinned; see the [README](../README.md#build-and-test).
- Public lifecycle test interfaces and the dependency/cleanup contract: see [lifecycle contracts](lifecycle.md#contracts).
- Motion/localization integration and its single update owner: Pedro is a TeamCode integration; TeamCode's drive subsystem updates the follower once per update, and follower commands require the follower ([lifecycle](lifecycle.md#using-pedro)).
- Registration metadata and transition payload interface: `ProgramMetadata` registers through Sloth ([FTC host](lifecycle.md#ftc-host)), and a request's effect sets the data a state uses ([subsystems](lifecycle.md#subsystems)).

The implementation plan resolves these through small executable examples rather than additional speculative framework layers.
