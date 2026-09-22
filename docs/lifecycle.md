# Implemented lifecycle interface

The `core` module runs on the JVM without Android or robot hardware.
It implements the first lifecycle slice, not the full Gum runtime.

## Define a program and acquire dependencies

```kotlin
val main = teleop("Main", dependencies = {
    val robot = createRobotGraph()
    onStop { robot.close() }
    robot
}) { robot ->
    onInitTick { robot.reportStatus() }
    onStart { robot.enable() }
    onTick { robot.update() }
}
```

The robot methods illustrate application-owned behavior, not a Gum robot interface.
The factory is ordinary Kotlin. It can call a generated DI factory later.
Gum does not close arbitrary graph objects automatically.

If construction acquires multiple resources, the factory registers cleanup immediately after each acquisition.
A graph factory that throws before it exposes a resource must clean up that resource itself.
Gum cannot discover resources that never register cleanup.

## Drive the session

```kotlin
val session = main.initialize()
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

- Declaration creates no dependency graph. Each `initialize()` call invokes the factory again.
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

Metadata beyond the program name, other program kinds, controller events, and success handoff are not implemented yet.
Metro, Ivy, KStateMachine, FTC, and Sloth adapters remain separate integration work.
