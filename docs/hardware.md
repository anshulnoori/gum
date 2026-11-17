# Hardware configuration from code

A robot's wiring is declared once, in Kotlin, in the FTC app module.
The build generates the FTC robot configuration from it, the Robot Controller activates that configuration at app start, and code resolves devices through the same declarations.

## Declare the wiring

Put one `RobotConfig` object in `TeamCode/src/hardware/kotlin`:

```kotlin
object RobotMap : RobotConfig("apex") {
    private val control = controlHub()
    private val expansion = expansionHub(address = 2)

    val frontLeft by control.motor(0, GoBilda5202Series)
    val claw by control.servo(0)
    val imu by control.i2c(0, ControlHubImuBhi260ap)
    val pinpoint by control.i2c(1, GoBildaPinpoint)
    val liftLeft by expansion.motor(0, GoBilda5202Series)
}
```

Each property name is the device's configuration name.
The configuration name must use lowercase letters, digits, and underscores, because it is also an Android resource name.
Declaring a port the hub lacks, a port twice, or a name twice fails the build with a message naming the problem.
Expansion Hubs must connect to the Control Hub over RS485; hubs connected only over USB need serial numbers and are not supported.

These sources are compiled for the build machine as well as the robot, so they can use Gum's `hardware` module and the Kotlin standard library, but not the FTC SDK.

## Use the devices

`hardware` resolves a declared device to its SDK type: `hardware(RobotMap.frontLeft)` is a `DcMotorEx`, and `hardware(RobotMap.pinpoint)` is a `GoBildaPinpointDriver`.
It works on the OpMode thread while an OpMode runs. A subsystem resolves only the devices it owns, in its constructor defaults.
A mistyped device is a compile error.

Programs built with the generated `teleop`, `autonomous`, and `utility` check the configuration at every INIT: if the active configuration lacks a declared device, INIT fails with one message listing every missing device.

## Write caching

Each write to a motor or servo is a command on the hub's serial bus, so a loop that writes nearly the same value every update spends bus time for no change.
A motor, servo, or CR servo can opt in with a cache tolerance:

```kotlin
val lift by expansion.motor(0, GoBilda5202Series, cacheTolerance = 0.01)
val claw by control.servo(0, cacheTolerance = 0.005)
```

`hardware(RobotMap.lift)` then returns a wrapper of the SDK device that skips a power, or a servo position, within the tolerance of the last value it sent.
The comparison is with the last value sent, not the last one asked for, so a slow ramp still moves the mechanism once it has moved more than the tolerance.
It always sends exact 0 and a change of sign, so a mechanism always stops and reverses.
Changing the direction, run mode, velocity, motor enable, servo range, or PWM settings through the wrapper makes the next write go through.
The wrapper lasts for the session, and servo wrappers keep the SDK's `PwmControl`.

The default tolerance is 0: Gum returns the SDK device itself, which already skips a write equal to the last one.
Only writes through `hardware(...)` are cached; a library that finds the device by name in `hardware.map`, such as Pedro, writes to the SDK device directly.
Motor velocity and target position are not cached.
A tolerance below 0 or not a number fails the build.

## Build

Apply `com.anshulnoori.gum.hardware` to the FTC app module after `com.android.application`. For each variant it:

1. Compiles `src/hardware/kotlin` on the build machine with `kotlin-compiler-embeddable`.
2. Runs Gum's generator, which writes `res/xml/<name>.xml`, a Java class with an `@OnCreate` hook, and `GumPrograms.kt`: `teleop`, `autonomous`, and `utility` in the config object's package, typed to the `RobotGraph` there.
3. Adds `src/hardware/kotlin` to the app's `main` sources.

The generator writes the format the FTC SDK 12.0 writes: a `LynxUsbDevice` with serial number `(embedded)` and parent module address 173, one `LynxModule` per hub, and device tags from the SDK's device annotations.

## Activation

The SDK lists every `res/xml` resource whose root is `<Robot type="FirstInspires-FTC">` as a read-only configuration.
The Robot Controller activity calls `@OnCreate` hooks at the end of `onCreate`, before its service connects and it builds the hardware map from the active configuration.
The generated hook makes the generated configuration active then, so each app start uses it without a robot restart.

Choosing another configuration on the Driver Station lasts until the app restarts.
A read-only configuration cannot be edited on the robot; change the Kotlin and reinstall.

Status: generation, packaging, and the hook's presence in the APK are verified. Activation on a Control Hub is not yet verified.
