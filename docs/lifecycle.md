# Implemented lifecycle interface

The `core` module runs on the JVM without Android or robot hardware.
It implements the first lifecycle slice, not the full Gum runtime.
The `ftc` module hosts these programs in the FTC SDK; see [FTC host](#ftc-host).

## Define a program and acquire dependencies

```kotlin
val main = teleop<Hub, Robot>("Main", group = "Competition", dependencies = {
    val robot = Robot(environment)
    onStop { robot.close() }
    robot
}) { robot ->
    initLoop { robot.reportStatus() }
    onStart { robot.enable() }
    loop { robot.update() }
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
Sloth integration remains separate work.

## Ivy commands

Ivy's `Scheduler` is a JVM-wide singleton; each session takes ownership of it:

- `initialize` resets it, dropping commands left from outside a session.
- Each active tick runs `Scheduler.execute()`, then updates subsystems, then runs the program's `loop` callbacks.
- STOP resets it after every other cleanup, including returning subsystems to their initial modes. Commands still scheduled are dropped without their `end` callback.
- INIT ticks do not advance commands. Ivy starts a command when it is scheduled, so schedule commands from START or later.

## Subsystems

```kotlin
class Intake(
    private val left: DcMotorSimple = hardware(RobotMap.intakeLeft),
    private val right: DcMotorSimple = hardware(RobotMap.intakeRight),
) : Subsystem<Intake.Mode>(initial = Mode.OFF) {
    enum class Mode(val power: Double) { OFF(0.0), COLLECTING(1.0), EJECTING(-1.0) }

    override val transitions = transitions {   // optional; without it any mode can follow any other
        Mode.OFF to Mode.COLLECTING
        Mode.OFF to Mode.EJECTING
        anyMode to Mode.OFF
        Mode.COLLECTING to Mode.OFF whenever { full }
    }

    override fun enter(mode: Mode) {
        left.power = mode.power
        right.power = mode.power
    }

    fun collect(): Command = request(Mode.COLLECTING)
}
```

- A subsystem joins the session running on its thread when it is constructed; constructing one outside a session fails.
- `request(mode)` is an Ivy command that reserves the subsystem, moves to `mode`, and finishes at once. A move `transitions` does not list leaves the mode unchanged.
- `whenever` makes a move automatic: Gum takes the first matching one each update, after commands. Commands cannot request automatic moves.
- `enter` runs on every entry, including the initial mode, which Gum enters on first use.
- STOP returns each subsystem to its initial mode, whatever `transitions` allows.

The FTC module creates subsystems per session through `Robot`:

```kotlin
val Robot.intake by subsystem(::Intake)

val intakeTest = teleop("Intake Test") { robot ->
    onStart { robot.intake.collect().schedule() }
}
```

## FTC host

The `ftc` module provides `teleop`, `autonomous`, and `utility` builders whose setup receives the session's `Robot` and whose environment is `FtcEnvironment`:
the session's `hardware`, `hardwareMap`, `telemetry`, `gamepad1`, and `gamepad2`.
TeamCode registers programs, with the robot's [hardware configuration](hardware.md), from a static method that the SDK discovers:

```kotlin
@OpModeRegistrar
fun registerOpModes(manager: OpModeManager) = registerPrograms(manager, RobotMap, main, scoreAndPark)
```

Each SDK run checks the active configuration for every declared device, then initializes a new session in `init`, ticks it in `init_loop` and `loop`, starts it in `start`, and stops it in `stop`.
The SDK calls these methods on one OpMode thread, which satisfies the session thread contract.
The host owns bulk reads: it sets every hub to manual caching and clears the cache once before each dispatch.
The SDK updates Driver Station telemetry after each dispatch.
FTC SDK 12.0 `OpModeMeta` carries the description; whether the Driver Station shows it is unverified.

Status: compiled into an APK with FTC SDK 12.0. Not yet run on a Control Hub.
