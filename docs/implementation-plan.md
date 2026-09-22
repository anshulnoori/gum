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

## 4. Add controller events and group ownership

Sample each controller once per update and define event delivery order.
Exercise press, release, held-at-START behavior, and shutdown gating.
Verify whole-group reservations against conflicting commands.
Reject overlapping requirements in supported parallel compositions.

## 5. Implement success-only autonomous handoff

Run a routine to success and request the next descriptor with explicit data.
Verify old-session cleanup completes before successor graph creation.
Verify the successor remains in INIT until a separate START.
Exercise fault, timeout, interruption, manual STOP, and simultaneous STOP/completion.
Do not infer success from Ivy's natural end reason alone.

## 6. Integrate FTC registration and Sloth reload

Register TeleOp, autonomous, and utility descriptors through the selected Sloth interface.
Keep hardware construction out of class initialization and scanning.
Verify names/groups and record where descriptions appear.
Reload changed TeamCode and generated Metro code between sessions.
Verify the next session uses new classes and retains no old graphs or callbacks.
Exercise dynamic successor initialization on the selected FTC SDK.

## 7. Establish the competition baseline

Measure update latency, sensor age, GC effects, and STOP response with representative hardware and vision load.
Verify cold boot, repeated INIT/START/STOP, disconnection, and a full APK install.
Keep development tooling separate from the competition service profile.
Review current rules before match use and record device/SDK versions with results.

## Commit and release policy

Keep the design baseline separate from executable implementation commits.
Commit completed, reviewed slices locally. Do not copy old Gum's history or implementation wholesale.
Inspect final commit messages for prohibited attribution before a push.
Publishing, releases, and deployment remain separate actions from local implementation.
