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
## Chaining programs

```kotlin
val redAuto = autonomous("Red Auto") { robot ->
    onStart { robot.scorePreload().schedule() }
    driverTeleOp   // the program to run next
}

val driverTeleOp = teleop("Driver") { robot -> }
```

- Setup can end with a program. After a run that started stops, by STOP or the autonomous timer, the host initializes that program; it waits in INIT for START.
- A run stopped during INIT chains nothing. Setup that ends with anything other than a program chains nothing.
- Every run belongs to a chain. A run started from the Driver Station starts a new chain; a chained run joins its predecessor's.
- The next program is created after the previous session's cleanup and subsystem resets.
- In FTC, the next program takes over the Driver Station entry with its own name, so the Driver Station shows its name and kind, and the entry's original program returns after that run.

### Chain scope

Values that should outlive one run, such as the robot's pose after autonomous, are `@SingleIn(ChainScope::class)`:

```kotlin
@Inject
@SingleIn(ChainScope::class)
class PoseMemory { var pose = Pose() }
```

The Gum Gradle plugin generates a Metro graph for `ChainScope` that each run's `RobotGraph` extends, so per-run subsystems can inject chain values. A chain value must not hold hardware; each run gets its devices again.

Status: the core contract and chain-graph reuse are JVM-tested. Initializing the next OpMode from `stop()` has not run on a Control Hub.

## Ivy commands

Ivy's `Scheduler` is a JVM-wide singleton; each session takes ownership of it:

- `initialize` resets it, dropping commands left from outside a session.
- Each active tick samples buttons and runs their actions, runs `Scheduler.execute()`, updates subsystems, then runs the program's `loop` callbacks.
- STOP resets it after every other cleanup, including returning subsystems to their initial modes. Commands still scheduled are dropped without their `end` callback.
- INIT ticks do not advance commands. Ivy starts a command when it is scheduled, so schedule commands from START or later.

## Subsystems

```kotlin
@Inject
@SingleIn(OpModeScope::class)
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

Mechanisms with feedback hold data beside their mode and run control in `loop`:

```kotlin
class Flywheel(private val motor: DcMotorEx = hardware(RobotMap.flywheel)) : Subsystem<Flywheel.Mode>(Mode.OFF) {
    enum class Mode { OFF, SPINNING_UP, READY }

    private var targetRpm = 0.0
    private val rpm get() = motor.velocity / TICKS_PER_REV * 60

    override val transitions = transitions {
        anyMode to Mode.SPINNING_UP
        anyMode to Mode.OFF
        Mode.SPINNING_UP to Mode.READY whenever { abs(targetRpm - rpm) < 100 }
    }

    override fun enter(mode: Mode) { if (mode == Mode.OFF) motor.power = 0.0 }
    override fun loop(mode: Mode) { if (mode != Mode.OFF) motor.power = KP * (targetRpm - rpm) }

    fun spinTo(rpm: Double): Command = request(Mode.SPINNING_UP) { targetRpm = rpm }.then(reach(Mode.READY))
}
```

- `loop(mode)` runs on every update after START, after commands and automatic moves.
- `request(mode) { }` runs its block only when the move is allowed, before `enter`. Requesting the current mode runs the block without re-entering, so a new target takes effect.
- `reach(mode)` reserves the subsystem and finishes once it is in `mode`.
- Operation outcomes (rejected, timed out, faulted) are not reported yet. A rejected request and a `reach` that never arrives look like any other command to Ivy.

## Controller events

```kotlin
val main = teleop("Main") { robot ->
    var alliance = Alliance.BLUE
    initLoop {
        if (gamepad1.dpadUp.wasPressed) alliance = Alliance.BLUE
        if (gamepad1.dpadDown.wasPressed) alliance = Alliance.RED
    }
    gamepad1.rightBumper.whileHeld { robot.intake.hold(Intake.Mode.COLLECTING) }
    gamepad1.circle.onPress { robot.intake.stop().schedule() }
}
```

- Gamepad buttons change from the SDK's gamepad callback, not by polling. The callback runs on the SDK's event loop thread; Gum queues each change and applies them on the OpMode thread at the start of the next update, in arrival order, so a tap between updates still presses and releases.
  The queue is a JCTools single-producer, single-consumer circular buffer (`SpscAtomicArrayQueue`, 256 changes) of preallocated changes, so a button change allocates nothing. Only the event loop thread posts. If the OpMode thread stalls long enough to fill it, the next update sets each button to its latest reported state; only taps in between are lost.
- `isDown`, `wasPressed`, and `wasReleased` describe the latest update, in INIT and after START, so INIT code polls them.
- `onPress` and `onRelease` actions run only after START, before Ivy advances, so a command they schedule starts and runs in that update. Bind them during setup.
- A button held through START, or down when first used, presses only after a release.
- `whileHeld { command }` schedules a fresh command on press and cancels it on release. `Subsystem.hold(mode)` enters `mode`, keeps running, and returns to the initial mode when cancelled or interrupted, so a newer hold on the same subsystem takes over.
- STOP from an action skips the remaining actions.
- Programs use `gamepad1`, `gamepad2`, `telemetry`, and `hardwareMap` directly. The gamepads are `Controller`s: every SDK button as a `Button` (PlayStation and Xbox names are the same button), sticks and triggers as `Double`, and `raw` for the SDK gamepad.
- `Button { condition }` checks a condition at the start of each update, for inputs with no events, such as a sensor threshold. Hub sensors have no SDK callbacks; they change when Gum bulk-reads the hubs, once per update.

## Routines

```kotlin
val score = routine(robot.intake, robot.flywheel) {
    await(robot.intake.collect())
    waitSeconds(0.5)
    await(robot.flywheel.spinTo(3000.0))
}
```

- `routine` is an Ivy command written as sequential code with Kotlin's built-in coroutines: no threads, no dispatcher. It runs inside Ivy's update; each suspension resumes on a later update.
- `await(command)` runs a command inside the routine; one that finishes at once costs no update. `waitUntil`, `waitSeconds`, and `nextUpdate` wait.
- Like Ivy's groups, a routine owns everything it runs: it reserves the subsystems it lists, and `await` fails for a command that needs anything else.
- When Ivy interrupts or cancels a routine, its awaited command ends as interrupted and the routine's `finally` blocks run. An exception from the body stops the OpMode.

## Background reads

```kotlin
class Shooter(sensor: Rev2mDistanceSensor = hardware(RobotMap.ballSensor)) : Subsystem<Shooter.Mode>(Mode.IDLE) {
    private val distance = background { sensor.getDistance(DistanceUnit.MM) }
    ...
    override fun loop(mode: Mode) { if ((distance.value ?: 999.0) < 40) ... }
}
```

- `background(periodMillis) { read }` reads on its own thread from INIT until STOP, so the loop never waits on slow inputs such as I2C sensors or vision. `value` is the latest result; `ageSeconds` says how old it is.
- The read must only read. A failed read stops the OpMode the next time the loop reads `value`.
- STOP stops readers before subsystems reset. The SDK serializes access to each hub, so a background I2C read can delay a bulk read on the same hub while it runs.

## Robot graph

TeamCode lists every subsystem once in a Metro graph extension named `RobotGraph`, in the package of its `RobotConfig` object. The Gum Gradle plugin generates the chain graph it extends and `teleop`, `autonomous`, and `utility` there, typed to it; each INIT creates a new `RobotGraph`:

```kotlin
@GraphExtension(OpModeScope::class)
interface RobotGraph : Robot {
    val intake: Intake
}
```

`@SingleIn(OpModeScope::class)` gives each run one intake. Metro uses a constructor default when the graph has no binding for that parameter. `Robot` supplies `battery`.

## Pedro Pathing

The `pedro` module makes a Pedro Pathing 3 `Follower` a Gum subsystem, the drivetrain's one owner:

```kotlin
@Inject
@SingleIn(OpModeScope::class)
class Drive(follower: Follower = Constants.create(hardware.map)) : PedroDrive(follower)

val main = teleop("Main") { robot ->
    onStart { robot.drive.drive({ -gamepad1.leftStickY }, { -gamepad1.leftStickX }, { -gamepad1.rightStickX }).schedule() }
}
```

- Gum updates the follower every update after START, after commands. `mode` mirrors the follower's mode: `IDLE`, `FOLLOWING`, `HOLDING`, or `DRIVER`.
- `follow(path)` and `hold(pose)` are Ivy's Pedro commands (`com.pedropathing.ivy:pedro`) with the drive reserved, so a newer drive command interrupts an older one. `drive(...)` reads its suppliers every update until interrupted.
- STOP stops the drivetrain. Pedro's device names should come from `RobotMap`, for example `RobotMap.frontLeft.name`.
- After START, it draws the robot and the path it follows on the Panels field, in Pedro's coordinates. It draws only on updates when Panels sends the field.

## Panels

Gum includes Panels (`com.bylazar:fullpanels:1.0.20`), which starts with the Robot Controller app and serves on port 8001.

- `telemetry` goes to both the Driver Station and Panels. The SDK's items, lines, and log are unchanged; Panels shows each line as text, taking a `Func` value when it is added.
- `field` is the Panels field: `field.moveCursor(x, y); field.circle(9.0)`. Draw it again each update.
- After every update, Gum sends that update's telemetry and drawing to Panels and clears both. Panels limits how often it transmits.
- Live-tuned values use Panels' `@Configurable` on a class or object; Panels picks up its static, non-final fields:

```kotlin
@Configurable
object IntakeTuning {
    @JvmField var collectPower = 1.0
}
```

## FTC host

The generated `teleop`, `autonomous`, and `utility` builders give setup the session's robot graph and an `FtcEnvironment`:
the session's `hardware`, `hardwareMap`, `telemetry`, `gamepad1`, and `gamepad2`.

Gum registers every public top-level program in the `org.firstinspires.ftc.teamcode` package; TeamCode has no registration code.
`GumProgramScanner` is a Sloth `OpModeScanner`. Sloth runs it when the Robot Controller app starts and after each hot reload (`./gradlew :TeamCode:deploySloth`), replacing the previous registrations.
Make a program `private` to hide it from the Driver Station.

Each SDK run checks the active configuration for every device the [hardware configuration](hardware.md) declares, then initializes a new session in `init`, ticks it in `init_loop` and `loop`, starts it in `start`, and stops it in `stop`.
The SDK calls these methods on one OpMode thread, which satisfies the session thread contract.
The host owns bulk reads: it sets every hub to manual caching and clears the cache once before each dispatch.
The SDK updates Driver Station telemetry after each dispatch.
Then the host sends the update's telemetry and field drawing to Panels.
FTC SDK 12.0 `OpModeMeta` carries the description; whether the Driver Station shows it is unverified.

Status: compiled into an APK with FTC SDK 12.0 and Sloth 0.3.2; a JVM check confirmed which fields the scanner selects. Not yet run on a Control Hub, so Sloth registration and reload are unverified.
