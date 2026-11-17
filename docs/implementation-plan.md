# Implementation plan

## Baseline status

The repository has independent history and a documented design.
The first JVM lifecycle slice has a pinned Gradle/Kotlin build and dependency locks.
The remaining integrations have no completed compatibility tests.

The user approved toolchain installation and tests through program descriptors and host-driven sessions.

## 1. Execute one OpMode lifecycle without hardware

Status: implemented. See [the lifecycle interface](lifecycle.md).

Test interface: a program descriptor and its host-driven session.
Tests supply fake dependencies and observable resource behavior through that public interface.
They do not assert private collections or generated code structure.

Acceptance:

- Descriptor creation does not construct the graph.
- INIT creates one graph and runs setup once.
- Returning from setup leaves the session in INIT.
- START activates configured behavior once.
- STOP closes resources and prevents later callbacks.
- A second invocation receives a different graph.
- Initialization and update failures still trigger acquired-resource cleanup.

Start with one failing test, implement that behavior, and extend vertically.
Do not create fake Ivy or KStateMachine runtimes as substitutes for their actual contracts.

## 2. Validate the selected toolchain and Metro

Status: Android assembly verified; device evidence missing.
FTC SDK 12.0.0, AGP 8.13.2, Gradle 9.1.0, Kotlin 2.3.20, and Metro 1.5.2 assemble the apex-2026 debug APK with a Metro session graph.
Metro 1.5.2 needs Gradle to run on JDK 21 or newer and inlines Java 11 bytecode, so consumers target Java 11.
Program builders generated into TeamCode create the graph at every INIT; see [ADR 0003](adr/0003-gum-owned-subsystems-and-robot.md).

Create the smallest FTC consumer plus a JVM-testable lifecycle module.
Pin versions from authoritative compatibility information, not old Gum's catalog.
Compile a real Metro graph with qualified hardware providers and per-session subsystem instances.
Exercise graph creation and disposal through the lifecycle interface.
Record JVM test results separately from Android assembly and device evidence.

## 3. Run an Ivy command against a KStateMachine subsystem

Use one representative mechanism with fake sensor and actuator adapters.
Confirm the mechanism's real states with the user before encoding them.
Exercise success, rejected requests, faults, interruption, and persistent holding behavior.
Require each command to observe its own operation outcome.

Validate Ivy reentrant schedule/cancel behavior with its actual dependency.
Resolve scheduler mutation and cleanup policy before controller callbacks schedule arbitrary work.

Status: partly implemented with an intake (two motors, states Off, Collecting, Ejecting) in apex-2026.
Sessions drive Ivy 1.1.1's scheduler: see [Ivy commands](lifecycle.md#ivy-commands).
Gum's `Subsystem` builds a KStateMachine 0.38.1 machine from an enum of states and an optional `automatic` block; see [subsystems](lifecycle.md#subsystems) and [ADR 0005](adr/0005-requested-and-automatic-transitions.md).
Named commands request a state and finish once it is entered. A request valid only `from` certain states is refused from any other, and Gum reports the refusal; automatic transitions run after Ivy each update.
JVM tests drive real Ivy and KStateMachine, including the intake with fake motors.
Feedback control is implemented: `during(state)` runs control after automatic transitions, `request(state) { }` sets data in the transition's effect, and `reach(state)` waits for a state; JVM tests drive a flywheel with a fake sensor.
Outcomes and timeouts are implemented: a routine's `await` returns finished, interrupted, refused, or timed out, and `withTimeout` limits any command; see [outcomes and timeouts](lifecycle.md#outcomes-and-timeouts). Faults and nested states remain.

Ivy 1.1.1 reentrancy, observed against the real dependency:
a command interrupted by a command scheduled during `Scheduler.execute()` still runs `execute()` once more in that pass, after its `end(INTERRUPTED)`.
Commands scheduled from Gum tick callbacks run outside that pass and avoid this.
Slice 4 should deliver controller events the same way.

## 4. Add controller events and group ownership

Status: controller events implemented from the SDK's gamepad callback, with `and`, `or`, `not`, `debounce`, `multiPress`, `toggled`, `toggleOnPress`, and stick and trigger thresholds; see [controller events](lifecycle.md#controller-events).
Each update first applies queued button changes in arrival order, then runs press and release actions after START, before Ivy.
Buttons held through START press only after a release. Conflicts follow Ivy's priority and `ConflictBehavior`.
Rejecting overlapping requirements in parallel groups remains.

Sample each controller once per update and define event delivery order.
Exercise press, release, held-at-START behavior, and shutdown gating.
Verify whole-group reservations against conflicting commands.
Reject overlapping requirements in supported parallel compositions.

## 5. Chain programs

Status: implemented with a chain-lasting Metro scope; device evidence missing. See [chaining programs](lifecycle.md#chaining-programs) and [ADR 0004](adr/0004-program-chains-and-ivy-ownership.md).
Setup ends with the next program; the host initializes it after a started run stops. The user replaced success-only `onSuccess` handoff with this.
Verify on a Control Hub that `initOpMode`, called when the SDK's stop OpMode initializes, leaves the next program in INIT with the Driver Station showing its name and kind.

## 6. Integrate FTC registration and Sloth reload

Status: registration implemented with Sloth 0.3.2's `OpModeScanner`; device evidence missing.
`GumProgramScanner` registers public top-level programs in TeamCode's package. The Sloth Load plugin builds the reload dex (`dexSloth`).
Programs whose class initialization creates hardware would break scanning; Gum builders create nothing until INIT.

Register TeleOp, autonomous, and utility descriptors through the selected Sloth interface.
Keep hardware construction out of class initialization and scanning.
Verify names/groups and record where descriptions appear.
Reload changed TeamCode and generated Metro code between sessions.
Verify the next session uses new classes and retains no old graphs or callbacks.
Exercise dynamic successor initialization on the selected FTC SDK.

## 6b. Integrate routines, background reads, Pedro, and Panels

Status: routines (coroutines over Ivy), background reads, the Pedro drive, and Panels telemetry and field drawing are implemented and JVM-tested. Panels on a Robot Controller is not yet verified.
SDK 12 already skips motor and servo writes that repeat the last value exactly, so Gum adds no write cache.

## 7. Establish the competition baseline

Measure by comparing apex-2026 on Gum with apex-2025 on MercurialFTC, on the same hub.

Measure update latency, sensor age, GC effects, and STOP response with representative hardware and vision load.
Verify cold boot, repeated INIT/START/STOP, disconnection, and a full APK install.
Keep development tooling separate from the competition service profile.
Review current rules before match use and record device/SDK versions with results.

## Commit and release policy

Keep the design baseline separate from executable implementation commits.
Commit completed, reviewed slices locally. Do not copy old Gum's history or implementation wholesale.
Inspect final commit messages for prohibited attribution before a push.
Publishing, releases, and deployment remain separate actions from local implementation.
