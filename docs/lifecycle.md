# Implemented lifecycle interface

The `core` module runs on the JVM without Android or robot hardware.
It implements the lifecycle every Gum program runs through.
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

Status: the core contract is JVM-tested, and so is the host's handoff for both stop orders, by replaying the SDK's calls. Chain-graph reuse is not tested, nor is taking over and restoring the Driver Station entry, which the tests replace. Chaining has not run on a Control Hub.

## Ivy commands

Ivy's `Scheduler` is a JVM-wide singleton; each session takes ownership of it:

- `initialize` resets it, dropping commands left from outside a session.
- Each active tick runs every subsystem's `beforeCommands`, applies button changes and runs their actions, runs `Scheduler.execute()`, updates subsystems, then runs the program's `loop` callbacks.
- STOP resets it after every other cleanup, including returning subsystems to their initial states. Commands still scheduled are dropped without their `end` callback.
- INIT ticks do not advance commands. Ivy starts a command when it is scheduled, so schedule commands from START or later.

## Subsystems

A subsystem is a mechanism made of one or more regions. Each region is in one state of its own enum at a time, and all of a subsystem's regions are active at once. Most mechanisms have one region:

```kotlin
@Inject
@SingleIn(OpModeScope::class)
class Intake(
    private val left: DcMotorSimple = hardware(RobotMap.intakeLeft),
    private val right: DcMotorSimple = hardware(RobotMap.intakeRight),
) : Subsystem() {
    enum class State(val power: Double) { OFF(0.0), COLLECTING(1.0), EJECTING(-1.0) }

    val rollers = region(State.OFF) {
        onEntry {
            left.power = it.power
            right.power = it.power
        }
    }

    fun collect(): Command = rollers.request(State.COLLECTING)
    fun eject(): Command = rollers.request(State.EJECTING, from = State.OFF)   // only from Off
    fun stop(): Command = rollers.request(State.OFF)
    fun collecting(): Command = rollers.hold(State.COLLECTING)                 // for whileHeld
}
```

- A subsystem joins the session running on its thread when it is constructed; constructing one outside a session fails.
- Named commands are a subsystem's whole API. `request`, `hold`, and `reach` are protected, so programs call `intake.collect()`, not `intake.rollers.request(...)`. Programs read a region's state, as in `intake.rollers.state`.
- `request(state)` is an Ivy command that reserves the region, transitions it to `state`, and finishes at once. Any state can be requested from any other.
- `request(state, from = ...)` is valid only from the named state, or from a set of states. `guard = { ... }` refuses the request when it returns false, and can read other regions. A refused request leaves the state, runs no effect, and reports nothing; a team that wants a log checks the state itself. Requesting a state the region is already in runs its effect, if the guard allows it.
- `onEntry` sets outputs and must be safe to repeat: Gum also reruns it, outermost state first, when a hub that lost its outputs reconnects. A request it makes then waits until every region has been reapplied.
- `onEntry` runs on every entry, including the initial state, which each region enters at INIT, after setup. A subsystem created later enters it on its first update.
- STOP returns each region to its initial state, whatever its commands allow.
  This resets the region's state, not the mechanism: by the time Gum cleans up, the SDK has fail-safed every hub and drops nonzero motor powers and velocities, target positions, and every servo write until the next OpMode initializes (`LynxModule.sendCommand`, `OpModeManagerImpl.callActiveOpModeStop`). An initial state's `onEntry` can still set motor power 0 and change modes. When `runOpMode` ends by throwing, the SDK has not blocked writes, so cleanup reaches the hardware.

Mechanisms with feedback hold data beside their state, run control in `during`, and take automatic transitions:

```kotlin
class Flywheel(private val motor: DcMotorEx = hardware(RobotMap.flywheel)) : Subsystem() {
    enum class State { OFF, SPINNING_UP, READY }

    private var targetRpm = 0.0
    var misses = 0
        private set
    private val rpm get() = motor.velocity / TICKS_PER_REV * 60

    val wheel = region(State.OFF) {
        automatic {
            State.SPINNING_UP to State.READY whenever { abs(targetRpm - rpm) < 100 }
            State.READY to State.SPINNING_UP whenever { abs(targetRpm - rpm) > 300 } effect { misses++ }
        }
        onEntry { if (it == State.OFF) motor.power = 0.0 }
        during { if (it != State.OFF) motor.power = KP * (targetRpm - rpm) }
    }

    fun spinTo(rpm: Double): Command = wheel.request(State.SPINNING_UP) { targetRpm = rpm }.then(wheel.reach(State.READY))
}
```

- `automatic { A to B whenever { condition } }` declares a transition the region takes by itself; `anyState to B` starts from any other state. Each update, after commands, Gum takes the first declared transition whose source the region is in, whose target it is not in, and whose condition is true. Declaration order decides between matches, and Gum takes at most one automatic transition per region per update.
- Conditions must only read, and can read other regions: Gum may evaluate any of them on any update. An automatic transition without `whenever` fails when the subsystem is created.
- Automatic transitions are not commands and take no requirement. Commands cannot request them.
- `effect { }` runs when its transition is taken, before the old state's `onExit` and the target's `onEntry`. On `request(state) { }` the block is the effect: it runs only when the transition is taken. Requesting the current state runs the effect without re-entering, so a new target takes effect.
- `onExit { }` runs each time the region leaves a state, after the effect and before the next `onEntry`, for cleanup that belongs to the state.
- `onEntry` may read the region's `state`, which is already the state entered. A request that `onEntry` or `onExit` makes runs after the current transition finishes; KStateMachine queues it.
- `during { }` runs on every update after START, after commands and every region's automatic transitions.
- `beforeCommands()` is overridden on the subsystem and runs on every update after START, before button actions and commands, for reads that commands decide from in the same update, such as a localizer's pose.
- `reach(state)` reserves the region and finishes once it is in `state`.
- A `reach` that never arrives waits forever; Ivy limits it, as in `reach(state).raceWith(waitMs(2000.0))`. See [composing commands](#composing-commands). Faults are not reported yet.

### Regions, parent states, and history

A mechanism whose parts change state independently has one region per part. An intake arm is raised or lowered, and its roller spins or stops, in every combination:

```kotlin
class IntakeArm(
    private val servo: Servo = hardware(RobotMap.armServo),
    private val motor: DcMotorEx = hardware(RobotMap.roller),
) : Subsystem() {
    enum class Arm(val position: Double?, override val parent: Arm? = null) : Nested<Arm> {
        LOWERED(0.1), RAISED(null), RAISED_LOW(0.6, RAISED), RAISED_HIGH(0.9, RAISED)
    }
    enum class Roller(val power: Double) { STOPPED(0.0), SPINNING(1.0) }

    val arm = region(Arm.LOWERED, history = History.SHALLOW) {
        onEntry { state -> state.position?.let { servo.position = it } }   // a parent has no position of its own
    }
    val roller = region(Roller.STOPPED) {
        onEntry { motor.power = it.power }
        automatic { Roller.SPINNING to Roller.STOPPED whenever { arm.isIn(Arm.RAISED) } }
    }

    fun raise(): Command = arm.request(Arm.RAISED)
    fun lower(): Command = arm.request(Arm.LOWERED)
    fun collect(): Command = roller.request(Roller.SPINNING, guard = { arm.isIn(Arm.LOWERED) })
    fun stow(): Command = roller.request(Roller.STOPPED).with(arm.request(Arm.RAISED_HIGH))
}
```

- The subsystem is one KStateMachine machine whose root is a parallel state; each region is one of its children, which KStateMachine calls regions.
- Each region is its own Ivy requirement. A command for the arm and one for the roller run at the same time; a command for one region interrupts only that region's command. A group of commands for both, such as `stow`, requires both.
- Rules between regions are guards and automatic transitions that read the other region.
- A state enum that implements `Nested` can give a state a `parent`, declared before it. A region is always in a state without children; requesting a parent enters its first child, or the child on the way to the region's initial state. `isIn(parent)` is true in any state inside it, and `state` names the innermost state. A parent's `onEntry` runs before its child's, and its `onExit` after; moving between two children of one parent leaves the parent entered.
- `from`, `reach`, `hold`, and automatic transitions on a parent cover every state inside it. Requesting a parent the region is already in changes no state; its effect runs if the guard allows it.
- `history = History.SHALLOW` makes requesting a parent return to the child the region was last in; `History.DEEP` returns to the innermost state it was last in. The default, `History.NONE`, enters the first child every time.

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
  The queue is a JCTools multi-producer, single-consumer circular buffer (`MpscAtomicArrayQueue`, 256 changes) of preallocated changes, so a button change allocates nothing. The event loop thread and the OpMode thread may both post. If the OpMode thread stalls long enough to fill it, the next update sets each button to its latest reported state; only taps in between are lost.
- `isDown`, `wasPressed`, and `wasReleased` describe the latest update, in INIT and after START, so INIT code polls them.
- `onPress` and `onRelease` actions run only after START, before Ivy advances, so a command they schedule starts and runs in that update. Bind them during setup.
- A button held through START, or down when first used, presses only after a release.
- `whileHeld { command }` schedules a fresh command on press and cancels it on release. A region's hold command, built with `hold(state)`, enters `state`, keeps running, and returns the region to its initial state when cancelled or interrupted, so a newer hold on the same region takes over. A hold set to `InterruptedBehavior.SUSPEND` keeps its state's place instead: when Ivy resumes it, it takes its state back on the next update. If an automatic transition leaves `state`, or its `from` or `guard` refuses the request, the hold ends and leaves the state alone.
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

## Composing commands

Gum has no composition API of its own. Programs compose subsystem commands with Ivy's groups and decorators:

```kotlin
val score = robot.intake.collect()
    .then(waitMs(500.0))
    .then(robot.flywheel.spinTo(3000.0).raceWith(waitMs(2000.0)))
    .then(conditional({ robot.flywheel.wheel.state == Flywheel.State.READY }, robot.transfer.fire(), robot.flywheel.stop()))
```

- `then`, `with`, `raceWith`, `until`, `sequential`, `parallel`, `race`, `deadline`, `conditional`, `match`, `repeat`, `loop`, and `lazy` come from Ivy, with Ivy's requirements: a group reserves everything its children reserve.
- Whether a step succeeded is read from state, not from the command: a request `from` states that do not include the current one leaves the state as it is, and a `reach` that has not arrived leaves the subsystem short of its state.
- `lazy { }` builds its command when it starts, so it reserves nothing by itself; add `.requiring(...)` for the regions it uses, such as `.requiring(robot.intake.rollers)`.
- Ivy has no timeout decorator. `cmd.raceWith(waitMs(ms))` limits a command; a team that wants a name for it can write one line: `fun Command.timeout(ms: Double) = raceWith(waitMs(ms))`.

## Background reads

```kotlin
class Shooter(sensor: Rev2mDistanceSensor = hardware(RobotMap.ballSensor)) : Subsystem() {
    private val distance = background { sensor.getDistance(DistanceUnit.MM) }
    val feeder = region(State.IDLE) {
        during { if ((distance.value ?: 999.0) < 40) ... }
    }
}
```

- `background(periodMillis) { read }` reads on its own thread from when it is created until STOP, so the loop does not wait for each read of a slow input such as an I2C sensor. A subsystem's reader starts at INIT, because Gum creates every subsystem then. `value` is the latest result; `ageSeconds` says how old it is.
- Vision processors and the Limelight already deliver results on their own threads; reading them through `background` would poll them twice.
- The read must only read. A failed read stops the OpMode the next time the loop reads `value`.
- The read runs on another thread, so it cannot use `hardware` or `robot.battery`, which belong to the OpMode thread. Get the device first, as the constructor default above does.
- STOP asks every reader to stop, then waits for all of them against one 400 ms deadline, before subsystems reset. The SDK sends to the hubs under one transmission lock, shared by both hubs on a portal, so a background I2C read can delay a bulk read on either hub while it runs.

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
INIT reads only accessors, the graph's abstract members; it never calls a `@Provides` function, which has a body, so a public provider does not build an unscoped extra. A subsystem accessor that returns a new instance on each read, because the subsystem lacks `@SingleIn(OpModeScope::class)`, fails INIT with a message saying so.

## Using Pedro

Gum has no Pedro Pathing module. Pedro is an optional integration that TeamCode sets up the way Pedro's own docs do, and Gum runs Pedro's Ivy commands like any other command:

- TeamCode depends on `com.pedropathing:revhub` and keeps Pedro's configs in its own constants file, with device names taken from `RobotMap`, such as `RobotMap.frontLeft.name`, so the generated configuration and the INIT device check cover Pedro's devices.
- TeamCode's robot graph provides one `Follower` per INIT with a Metro `@Provides @SingleIn(OpModeScope::class)` function built from `hardware.map`. Subsystems that need the follower take it as a constructor parameter.
- A TeamCode drive subsystem updates the follower in `beforeCommands`, before commands run, as Pedro's docs do, and stops the drivetrain at STOP. STOP returns a subsystem to its initial state only from another state, so the drive tracks whether the follower is moving with two states and automatic transitions.
- Follower commands require the follower, so Ivy gives the drivetrain one owner. A stick drive runs as a low-priority command that Ivy suspends: `.setPriority(Int.MIN_VALUE).setInterruptedBehavior(InterruptedBehavior.SUSPEND)`.
- Tuning uses Pedro's AutoTune and its `@Tuner` methods, in a TeamCode source set that only tuning builds include, because AutoTune serves a web page whenever it is installed.

## Panels

Gum includes Panels (`com.bylazar:fullpanels:1.0.20`), which starts with the Robot Controller app and serves on port 8001.

- `telemetry` goes to both the Driver Station and Panels. The SDK's items, lines, and log are unchanged; Panels shows each line as text, taking a `Func` value when it is added.
- Lines reach Panels only on updates when Panels will transmit them: a laptop is connected and Panels' send interval has passed. On every other update, including every update in a match with no laptop, nothing is formatted, evaluated, or serialized for Panels.
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
- The phases line is the average time per update in each phase, in the order they run: Input (clearing the bulk cache, checking for a hub that reconnected and reapplying outputs if one did, and taking the newest gamepad packet), Buttons, Ivy (`Scheduler.execute`), Subsystems (`beforeCommands`, which runs before Buttons, then automatic transitions and `during`), Program (the program's `initLoop`, `onStart`, or `loop` callbacks), Telemetry (sending to the Driver Station), and Panels. Other is the time outside every phase, such as the SDK's own checks between updates and adding these two lines.
- A hub's bulk read happens at the first hardware read after the cache clears, so its time counts in the phase that reads first, usually Subsystems.
- Ivy and Subsystems run only after START, so they show 0.00 in INIT. The first second after START also includes the last INIT updates and the START update. Nothing shows until the first second has passed.
- Gum reads the clock once per phase and adds to fixed counters, so timing allocates nothing per update. It formats the two lines once per second; each update adds the same two strings again.
- A chain runs the program setup ends with, as it is. A debug auto that ends with `main` chains to the plain TeleOp; end it with `debugMain` to keep the timing.

`UpdateTiming` in `core` does the timing, so another host can show the same phases: it calls `begin()` at the start of each update and `end(phase)` as each phase finishes, and passes it to `Program.initialize`, whose ticks end the Buttons, Ivy, and Subsystems phases.

Status: JVM-tested. Not yet run on a Control Hub; the figures above are an illustration, not a measurement.

## FTC host

The generated `teleop`, `autonomous`, and `utility` builders give setup the session's robot graph and an `FtcEnvironment`:
the session's `hardware`, `hardwareMap`, `telemetry`, `gamepad1`, and `gamepad2`.

Gum registers every public program in TeamCode: a public top-level `val`, or a public property of an `object`, in any package; TeamCode has no registration code.
`GumProgramScanner` is a Sloth `OpModeScanner`. Sloth runs it when the Robot Controller app starts and after each hot reload (`./gradlew :TeamCode:deploySloth`), replacing the previous registrations.
Make a program `private` to hide it from the Driver Station.

Each SDK run is a `LinearOpMode` whose `runOpMode` initializes a new session, whose dependencies first check the active configuration for every device the [hardware configuration](hardware.md) declares, ticks it until START, starts it, ticks it until STOP, and stops it in a `finally` block.
All of this runs on the SDK's one OpMode thread, which satisfies the session thread contract. Active updates run back to back, without `opModeIsActive()`, which yields the thread on every call; the SDK's iterative `OpMode` would sleep 1 ms after each and copy both gamepads before each. INIT updates pause 1 ms each, because INIT has nothing to keep up with.
The SDK interrupts the OpMode thread at STOP; cleanup runs with the interrupt cleared, then restores it.
The host owns bulk reads: it sets every hub to manual caching and clears the cache once before each update.
The host checks each update whether a hub that stopped responding responds again, such as after it lost power. That hub has lost its outputs, so the host forgets every write cache and runs each subsystem's `onEntry` for its current state again. The SDK reports no reset event; a reset short enough that no command times out goes unnoticed.
For a [debug program](#debug-programs), the host adds the timing lines before each update. After each update, the host sends Driver Station telemetry, then the update's telemetry and field drawing to Panels.
FTC SDK 12.0 `OpModeMeta` carries the description; whether the Driver Station shows it is unverified.

Status: compiled into an APK with FTC SDK 12.0 and Sloth 0.3.2. JVM tests run `GumOpMode` through every phase, by replaying the SDK's calls; no test covers `GumProgramScanner`. Not yet run on a Control Hub, so Sloth registration and reload are unverified.
