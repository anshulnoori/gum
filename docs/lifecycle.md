# Implemented lifecycle interface

The `core` module runs on the JVM without Android or robot hardware.
It implements the first lifecycle slice, not the full Gum runtime.
The `ftc` module hosts these programs in the FTC SDK; see [FTC host](#ftc-host).

## Define a program and acquire dependencies

```kotlin
val main = teleop<Hub, Bench>("Main", group = "Competition", dependencies = {
    val bench = Bench(environment)
    onStop { bench.close() }
    bench
}) { bench ->
    initLoop { bench.reportStatus() }
    onStart { bench.enable() }
    loop { bench.update() }
}
```

This is the `core` builder, which hosts and JVM tests use. FTC programs use the builders the Gum Gradle plugin generates, `teleop("Main") { robot -> }`, which supply the dependency factory; see [Robot graph](#robot-graph) and [FTC host](#ftc-host).
`Hub` and `Bench` stand for application types; `Bench` and its methods are not Gum's `Robot` interface.
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
The [FTC host](#ftc-host) supplies actual INIT, START, update, and STOP signals from a `LinearOpMode`.
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
- A run that fails chains nothing.
- In FTC, the next program takes over the Driver Station entry with its own name, so the Driver Station shows its name and kind, and the entry's original program returns after that run.

### Chain scope

Values that should outlive one run, such as the robot's pose after autonomous, are `@SingleIn(ChainScope::class)`:

```kotlin
@Inject
@SingleIn(ChainScope::class)
class PoseMemory { var pose = Pose() }
```

The Gum Gradle plugin generates a Metro graph for `ChainScope` that each run's `RobotGraph` extends, so per-run subsystems can inject chain values. A chain value must not hold hardware; each run gets its devices again.

In FTC, the host queues the next program when the SDK's stop OpMode initializes, after the run has stopped. The SDK stops a run in one of two orders: Driver Station STOP queues the stop OpMode and then stops the run, while `requestOpModeStop`, a `runOpMode` that returns, and app teardown stop the run and then queue the stop OpMode over anything queued during the stop. Queuing the next program when the stop OpMode initializes works for both.

Status: the core contract is JVM-tested, and so is the host's handoff for both stop orders, by replaying the SDK's calls. Chain-graph reuse is not tested. Chaining has not run on a Control Hub.

## Ivy commands

Ivy's `Scheduler` is a JVM-wide singleton; each session takes ownership of it:

- `initialize` resets it, dropping commands left from outside a session.
- Each active tick samples buttons and runs their actions, runs `Scheduler.execute()`, updates subsystems, then runs the program's `loop` callbacks.
- STOP resets it after every other cleanup, including returning subsystems to their initial states. Commands still scheduled are dropped without their `end` callback.
- INIT ticks do not advance commands. Ivy starts a command when it is scheduled, so schedule commands from START or later.

## Subsystems

```kotlin
@Inject
@SingleIn(OpModeScope::class)
class Intake(
    private val left: DcMotorSimple = hardware(RobotMap.intakeLeft),
    private val right: DcMotorSimple = hardware(RobotMap.intakeRight),
) : Subsystem<Intake.State>(initial = State.OFF) {
    enum class State(val power: Double) { OFF(0.0), COLLECTING(1.0), EJECTING(-1.0) }

    override fun onEntry(state: State) {
        left.power = state.power
        right.power = state.power
    }

    fun collect(): Command = request(State.COLLECTING)
    fun eject(): Command = request(State.EJECTING, from = State.OFF)   // only from Off
    fun stop(): Command = request(State.OFF)
    fun collecting(): Command = hold(State.COLLECTING)                 // for whileHeld
}
```

- A subsystem joins the session running on its thread when it is constructed; constructing one outside a session fails.
- Named commands are a subsystem's whole API. `request`, `hold`, and `reach` are protected, so programs call `intake.collect()`, not `intake.request(...)`.
- `request(state)` is an Ivy command that reserves the subsystem, transitions to `state`, and finishes at once. Any state can be requested from any other.
- `request(state, from = ...)` is valid only from the named state, or from a set of states. From any other state the request is refused: the state stays, and Gum reports it. Requesting the current state is never refused.
- A refused request is never silent. Gum passes a message naming the subsystem, the requested state, and the current state to `onRefused` callbacks; FTC programs log it to RobotLog and add it to the Driver Station's telemetry log. Without a callback, Gum prints it to standard error. The request also ends with the refused outcome, which a routine's `await` returns.
- `onEntry` runs on every entry, including the initial state, which each subsystem enters at INIT, after setup. A subsystem created later enters it on its first update.
- STOP returns each subsystem to its initial state, whatever its commands allow.

Mechanisms with feedback hold data beside their state, run control in `during`, and take automatic transitions:

```kotlin
class Flywheel(private val motor: DcMotorEx = hardware(RobotMap.flywheel)) : Subsystem<Flywheel.State>(State.OFF) {
    enum class State { OFF, SPINNING_UP, READY }

    private var targetRpm = 0.0
    var misses = 0
        private set
    private val rpm get() = motor.velocity / TICKS_PER_REV * 60

    override val automatic = automatic {
        State.SPINNING_UP to State.READY whenever { abs(targetRpm - rpm) < 100 }
        State.READY to State.SPINNING_UP whenever { abs(targetRpm - rpm) > 300 } effect { misses++ }
    }

    override fun onEntry(state: State) { if (state == State.OFF) motor.power = 0.0 }
    override fun during(state: State) { if (state != State.OFF) motor.power = KP * (targetRpm - rpm) }

    fun spinTo(rpm: Double): Command = request(State.SPINNING_UP) { targetRpm = rpm }.then(reach(State.READY))
}
```

- `automatic { A to B whenever { condition } }` declares a transition the subsystem takes by itself; `anyState to B` starts from any other state. Each update, after commands, Gum takes the first declared transition whose source is the current state and whose condition is true. Declaration order decides between matches, and Gum takes at most one automatic transition per update.
- Conditions must only read: Gum may evaluate any of them on any update. An automatic transition without `whenever` fails when the subsystem is created.
- Automatic transitions are not commands and take no requirement. Commands cannot request them.
- `effect { }` runs when its transition is taken, before the target's `onEntry`. On `request(state) { }` the block is the effect: it runs only when the transition is taken. Requesting the current state runs the effect without re-entering, so a new target takes effect.
- `during(state)` runs on every update after START, after commands and automatic transitions.
- `reach(state)` reserves the subsystem and finishes once it is in `state`.
- A `reach` that never arrives waits forever; `reach(state).withTimeout(seconds)` gives it a limit. See [outcomes and timeouts](#outcomes-and-timeouts). Faults are not reported yet.

## Controller events

```kotlin
val main = teleop("Main") { robot ->
    var alliance = Alliance.BLUE
    initLoop {
        if (gamepad1.dpadUp.wasPressed) alliance = Alliance.BLUE
        if (gamepad1.dpadDown.wasPressed) alliance = Alliance.RED
    }
    gamepad1.rightBumper.whileHeld { robot.intake.collecting() }
    gamepad1.circle.onPress { robot.intake.stop().schedule() }
    gamepad1.above(Axis.RIGHT_TRIGGER, 0.5).whileHeld { robot.intake.ejecting() }
    (gamepad1.leftBumper and gamepad1.dpadLeft).onPress { robot.lift.climb().schedule() }
    gamepad1.triangle.doublePress(0.3).onPress { robot.lift.stow().schedule() }
    gamepad2.a.toggleOnPress { robot.flywheel.spinning() }
    val slow = gamepad1.leftStickButton.toggled()
    loop { telemetry.addData("Slow mode", slow.isDown) }
}
```

- Gamepad buttons change from the SDK's gamepad callback, not by polling. The callback runs on the SDK's event loop thread; Gum queues each change and applies them on the OpMode thread at the start of the next update, in arrival order, so a tap between updates still presses and releases.
  The queue is a JCTools single-producer, single-consumer circular buffer (`SpscAtomicArrayQueue`, 256 changes) of preallocated changes, so a button change allocates nothing. Only the event loop thread posts. If the OpMode thread stalls long enough to fill it, the next update sets each button to its latest reported state; only taps in between are lost.
- `isDown`, `wasPressed`, and `wasReleased` describe the latest update, in INIT and after START, so INIT code polls them.
- `onPress` and `onRelease` actions run only after START, before Ivy advances, so a command they schedule starts and runs in that update. Bind them during setup.
- A button held through START, or down when first used, presses only after a release.
- `whileHeld { command }` schedules a fresh command on press and cancels it on release. A subsystem's hold command, built with `hold(state)`, enters `state`, keeps running, and returns to the initial state when cancelled or interrupted, so a newer hold on the same subsystem takes over. If an automatic transition leaves `state`, or the hold's request is refused, the hold ends and leaves the state alone.
- STOP from an action skips the remaining actions.
- Programs use `gamepad1`, `gamepad2`, `telemetry`, and `hardwareMap` directly. The gamepads are `Controller`s: every SDK button as a `Button` (PlayStation and Xbox names are the same button), sticks and triggers as `Double`, and `raw` for the SDK gamepad. Sticks and triggers also come from the SDK's callback: each update reads the newest packet that arrived before it started. `raw` is copied on another thread, so values read from it can mix two packets.
- `Button { condition }` checks a condition at the start of each update, for inputs with no events, such as a sensor threshold. Hub sensors have no SDK callbacks; they change when Gum bulk-reads the hubs, once per update.
- `above(axis, threshold)`, `below(axis, threshold)`, and `between(axis, low, high)` on a `Controller` turn a stick or trigger (`Axis.LEFT_STICK_X` through `Axis.RIGHT_TRIGGER`) into a `Button` that changes from the SDK's callback like any other button, so a trigger pull is an event, not a poll. `above` and `below` are strict; `between` is inclusive. Stick Y is positive down, as in the SDK. The SDK reports axes as `Float`, so a value equal to a `Double` threshold such as 0.2 can land on either side of it.
- Derived buttons change as their sources change, in the same update and in arrival order, so a tap between updates still counts. They are ordinary `Button`s: every binding and every combinator works on them, and they can be chained, such as `gamepad1.a.debounce(0.05).doublePress(0.3)`.
  - `a and b`, `a or b`, and `!a` combine buttons, including buttons from both gamepads and condition buttons. `(!a).onPress { }` runs when `a` is released.
  - `debounce(pressSeconds, releaseSeconds = 0.0)` goes down once its source has stayed down for `pressSeconds`, and up once it has stayed up for `releaseSeconds`. A change that reverses first is ignored. Each update first applies debounce times that have run out, then the changes that arrived since the last update.
  - `multiPress(presses, windowSeconds)` goes down at the last of `presses` presses within `windowSeconds` of the first, and up at the next release; `doublePress(windowSeconds)` is two presses. The next press starts a new count. The source's own actions still run at every press.
  - `toggled(down = false)` flips at each press of its source, for a latched setting read with `isDown`.
  - Times count from the start of the update that applies each change, so they are accurate to one update.
  - A derived button starts in the state its sources give it, so a source held at creation or through START does not press it. It cannot `post`.
- `toggleOnPress { command }` schedules a fresh command at a press and cancels it at the next one. If the command has ended by then, the press schedules a fresh one.

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
- Each routine records where it was created. When its body throws, the error carries a suppressed `RoutineCreation` whose stack trace starts at the `routine { }` call, so the log shows which routine failed as well as the failing line. Recording it costs one stack capture each time `routine { }` runs, not each time the routine is scheduled.

### Outcomes and timeouts

```kotlin
val score = routine(robot.intake, robot.flywheel) {
    if (await(robot.flywheel.spinTo(3000.0).withTimeout(2.0)) == Outcome.TIMED_OUT) return@routine
    await(robot.intake.collect())
}
```

- `await(command)` returns the command's `Outcome`: how it ended.
  - `FINISHED`: it did its work. A request entered its state, a `reach` arrived, a routine's body returned. Any non-Gum command that finishes reports `FINISHED`.
  - `REFUSED`: its subsystem refused the request, so the state stayed. Both `request` and `hold` report it.
  - `INTERRUPTED`: it ended before doing its work. A `hold` never finishes by itself, so a hold whose state the subsystem left by an automatic transition reports it. A routine or `reach` that Ivy interrupts or cancels reports it too.
  - `TIMED_OUT`: its `withTimeout` ran out first.
- The routine continues whatever the outcome. Check it before a step that needs the last one to succeed.
- `command.withTimeout(seconds)` works on any command, including a routine. Its time counts from when it starts, and Gum checks it once per update. When it runs out, Gum ends the command as interrupted, so the command runs its own interrupt handling: a routine's `finally` blocks run, and a `follow` holds where the robot is. The outcome is then `TIMED_OUT`. A command that finishes in time keeps its own outcome, such as `REFUSED`.
- The timed command keeps the command's requirements, priority, and behaviors, so Ivy schedules it the same way. Scheduled with Ivy instead of awaited, it still ends at the timeout; nothing reads its outcome.
- Ivy's own groups, such as `then`, `with`, and `raceWith`, do not pass outcomes on: `request(...).then(reach(...))` reports `FINISHED` even when its request is refused. Await each step in a routine to see its outcome.

## Background reads

```kotlin
class Shooter(sensor: Rev2mDistanceSensor = hardware(RobotMap.ballSensor)) : Subsystem<Shooter.State>(State.IDLE) {
    private val distance = background { sensor.getDistance(DistanceUnit.MM) }
    ...
    override fun during(state: State) { if ((distance.value ?: 999.0) < 40) ... }
}
```

- `background(periodMillis) { read }` reads on its own thread from when it is created until STOP, so the loop does not wait for each read of a slow input such as an I2C sensor. A subsystem's reader starts at INIT, because Gum creates every subsystem then. `value` is the latest result; `ageSeconds` says how old it is.
- Vision processors and the Limelight already deliver results on their own threads; reading them through `background` would poll them twice.
- The read must only read. A failed read stops the OpMode the next time the loop reads `value`.
- The read runs on another thread, so it cannot use `hardware` or `robot.battery`, which belong to the OpMode thread. Get the device first, as the constructor default above does.
- STOP stops readers before subsystems reset. The SDK sends to the hubs under one transmission lock, shared by both hubs on a portal, so a background I2C read can delay a bulk read on either hub while it runs.

## Robot graph

TeamCode lists every subsystem once in a Metro graph extension named `RobotGraph`, in the package of its `RobotConfig` object. The Gum Gradle plugin generates the chain graph it extends and `teleop`, `autonomous`, and `utility` there, typed to it; each INIT creates a new `RobotGraph`:

```kotlin
@GraphExtension(OpModeScope::class)
interface RobotGraph : Robot {
    val intake: Intake
}
```

`@SingleIn(OpModeScope::class)` gives each run one intake. Metro uses a constructor default when the graph has no binding for that parameter. `Robot` supplies `battery`.
Each INIT reads every accessor `RobotGraph` declares before setup runs, so every subsystem exists, and enters its initial state, during INIT, even one a program first uses after START.

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

- Gum updates the follower every update after START, after commands. `state` mirrors the follower's mode through automatic transitions: `IDLE`, `FOLLOWING`, `HOLDING`, or `DRIVER`.
- `follow(path)`, `hold(pose)`, `holdHere()`, and `drive(...)` reserve the drive, so a newer drive command interrupts an older one. `drive(...)` reads its suppliers every update.
- A `drive(...)` that is cancelled or interrupted stops the drivetrain. A `follow(path)` that ends before its path does holds the pose the robot is at; one that finishes holds the path's end pose.
- STOP stops the drivetrain. Pedro's device names should come from `RobotMap`, for example `RobotMap.frontLeft.name`.
- After START, it draws the robot and the path it follows on the Panels field, in Pedro's coordinates. It draws on updates when the field's `shouldUpdateCanvas` is true, which it checks before Gum sends the field, so some sent frames lack the robot.

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

## Debug programs

`debug(program)` makes a separate program that runs the same setup and shows Gum's timing at the top of its telemetry, on the Driver Station and in Panels. Declare it next to the program it wraps; Gum registers it like any other public top-level program, as its own entry named "<name> (Debug)" with the same kind, group, and description:

```kotlin
val auto = autonomous("Auto") { robot -> /* ... */ }
val debugAuto = debug(auto)

val main = teleop("Main") { robot -> /* ... */ }
val debugMain = debug(main)
```

The original program shows no timing and does no timing work. A debug program shows two lines:

```text
Gum loop : 4.31 ms avg, 9.80 ms max, 232 Hz
Gum phases (ms) : Input 0.02, Buttons 0.01, Ivy 0.30, Subsystems 3.10, Program 0.40, Telemetry 0.20, Panels 0.10, Other 0.18
```

- The loop line is the time from the start of one update to the start of the next: its average and maximum over the last second, and updates per second.
- The phases line is the average time per update in each phase, in the order they run: Input (clearing the bulk cache and taking the newest gamepad packet), Buttons, Ivy (`Scheduler.execute`, including routines), Subsystems (automatic transitions and `during`), Program (the program's `initLoop`, `onStart`, or `loop` callbacks), Telemetry (sending to the Driver Station), and Panels. Other is the time outside every phase, such as the SDK's own checks between updates and adding these two lines.
- A hub's bulk read happens at the first hardware read after the cache clears, so its time counts in the phase that reads first, usually Subsystems.
- Ivy and Subsystems run only after START, so they show 0.00 in INIT. The first second after START also includes the last INIT updates and the START update. Nothing shows until the first second has passed.
- Gum reads the clock once per phase and adds to fixed counters, so timing allocates nothing per update. It formats the two lines once per second; each update adds the same two strings again.
- A chain runs the program setup ends with, as it is. A debug auto that ends with `main` chains to the plain TeleOp; end it with `debugMain` to keep the timing.

`UpdateTiming` in `core` does the timing, so another host can show the same phases: it calls `begin()` at the start of each update and `end(phase)` as each phase finishes, and passes it to `Program.initialize`, whose ticks end the Buttons, Ivy, and Subsystems phases.

Status: JVM-tested. Not yet run on a Control Hub; the figures above are an illustration, not a measurement.

## FTC host

The generated `teleop`, `autonomous`, and `utility` builders give setup the session's robot graph and an `FtcEnvironment`:
the session's `hardware`, `hardwareMap`, `telemetry`, `gamepad1`, and `gamepad2`.

Gum registers every public top-level program in the `org.firstinspires.ftc.teamcode` package; TeamCode has no registration code.
`GumProgramScanner` is a Sloth `OpModeScanner`. Sloth runs it when the Robot Controller app starts and after each hot reload (`./gradlew :TeamCode:deploySloth`), replacing the previous registrations.
Make a program `private` to hide it from the Driver Station.

Each SDK run is a `LinearOpMode` whose `runOpMode` checks the active configuration for every device the [hardware configuration](hardware.md) declares, initializes a new session, ticks it until START, starts it, ticks it until STOP, and stops it in a `finally` block.
All of this runs on the SDK's one OpMode thread, which satisfies the session thread contract. Updates run back to back; the SDK's iterative `OpMode` would sleep 1 ms after each and copy both gamepads before each.
The SDK interrupts the OpMode thread at STOP; cleanup runs with the interrupt cleared, then restores it.
The host owns bulk reads: it sets every hub to manual caching and clears the cache once before each update.
For a [debug program](#debug-programs), the host adds the timing lines before each update. After each update, the host sends Driver Station telemetry, then the update's telemetry and field drawing to Panels.
FTC SDK 12.0 `OpModeMeta` carries the description; whether the Driver Station shows it is unverified.

Status: compiled into an APK with FTC SDK 12.0 and Sloth 0.3.2. JVM tests run `GumOpMode` through every phase, by replaying the SDK's calls; no test covers `GumProgramScanner`. Not yet run on a Control Hub, so Sloth registration and reload are unverified.
