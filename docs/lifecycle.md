# Implemented lifecycle interface

The `core` module runs on the JVM without Android or robot hardware.
It implements the first lifecycle slice, not the full Gum runtime.
The `ftc` module hosts these programs in the FTC SDK; see [FTC host](#ftc-host).

## Define a program and acquire dependencies

```kotlin
val main = teleop<Hub, Robot>("Main", group = "Competition", dependencies = {
    val robot = createRobotGraph(environment)
    onStop { robot.close() }
    robot
}) { robot ->
    onInitTick { robot.reportStatus() }
    onStart { robot.enable() }
    onTick { robot.update() }
}
```

The robot methods illustrate application-owned behavior, not a Gum robot interface.
The factory is ordinary Kotlin. It can call a generated DI factory.
`environment` is the value the host passes to `initialize`, such as FTC hardware access.
It is available during dependency creation and setup. Do not retain it beyond the session.
Gum does not close arbitrary graph objects automatically.

If construction acquires multiple resources, the factory registers cleanup immediately after each acquisition.
A graph factory that throws before it exposes a resource must clean up that resource itself.
Gum cannot discover resources that never register cleanup.

## Drive the session

```kotlin
val session = main.initialize(hub)
try {
    session.tick()  // INIT callbacks only
    session.start()
    session.tick()  // Active callbacks only
} finally {
    session.stop()
}
```

The host calls all methods on the thread that initialized the session.
The eventual FTC host supplies actual INIT, START, update, and STOP signals.
This example does not register an FTC OpMode or implement a timed robot loop.

## Contracts

- Declaration creates no dependency graph. Each `initialize(environment)` call invokes the factory again.
- Dependencies and setup see the environment passed to that `initialize` call.
- Factories must return fresh scoped graphs. Gum cannot prevent a factory from returning a singleton.
- Setup runs once. Its return leaves the session in INIT.
- Registration closes when setup returns. It cannot resume during updates or after STOP.
- START callbacks run once. Repeated host START signals have no effect.
- INIT and active callbacks run in registration order, in their respective phases.
- Callback failures stop the session before later callbacks run.
- STOP runs all cleanup callbacks in reverse registration order, including before START.
- The first cleanup failure propagates. Later cleanup failures appear as suppressed errors.
- An existing setup or update failure remains primary when cleanup also fails.
- STOP clears callback references. Repeated STOP does not repeat cleanup.
- STOP inside a callback prevents later callbacks in that dispatch.
- The current callback must return after it requests STOP. Gum cannot preempt synchronous user code.
- Recursive START or update dispatch is invalid. Wrong-thread access is invalid.

Program metadata holds a name, kind (TeleOp, autonomous, or utility), group, and description.
Controller events and success handoff are not implemented yet.
Ivy, KStateMachine, and Sloth adapters remain separate integration work.

## FTC host

The `ftc` module provides `teleop`, `autonomous`, and `utility` builders whose environment is `FtcEnvironment`:
the session's `hardware`, `hardwareMap`, `telemetry`, `gamepad1`, and `gamepad2`.
TeamCode registers programs, with the robot's [hardware configuration](hardware.md), from a static method that the SDK discovers:

```kotlin
@OpModeRegistrar
fun registerOpModes(manager: OpModeManager) = registerPrograms(manager, Apex, main, scoreAndPark)
```

Each SDK run checks the active configuration for every declared device, then initializes a new session in `init`, ticks it in `init_loop` and `loop`, starts it in `start`, and stops it in `stop`.
The SDK calls these methods on one OpMode thread, which satisfies the session thread contract.
The host owns bulk reads: it sets every hub to manual caching and clears the cache once before each dispatch.
The SDK updates Driver Station telemetry after each dispatch.
FTC SDK 12.0 `OpModeMeta` carries the description; whether the Driver Station shows it is unverified.

Status: compiled into an APK with FTC SDK 12.0 and a Metro graph. Not yet run on a Control Hub.
