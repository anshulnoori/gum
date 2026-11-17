# Hardware configuration from code

A robot's wiring is declared once, in Kotlin, in the FTC app module.
The build generates the FTC robot configuration from it, the Robot Controller activates that configuration at app start when its wiring is new, and code resolves devices through the same declarations.

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
    val limelight by limelight()
    val camera by webcam("A1B2C3D4")
}
```

Each property name is the device's configuration name.
The configuration name must use lowercase letters, digits, and underscores, because it is also an Android resource name.
Declaring a port the hub lacks, a port twice, or a name twice fails the build with a message naming the problem.
Expansion Hubs must connect to the Control Hub over RS485; hubs connected only over USB need serial numbers and are not supported.

USB devices belong to the robot, not to a hub:

1. `limelight(usbId = 0)` declares a Limelight 3A. Its address is 172.29.<usbId>.1, where `usbId` is the USB ID on the Limelight's settings page; leave it at 0 unless that was changed. Limelight supports one per Control Hub, so a second is a build error.
2. `webcam(serialNumber)` declares a USB webcam. The SDK finds a webcam by its serial number, so copy it from the webcam's entry in Configure Robot on the Driver Station after a scan. Two webcams with one serial number are a build error.

These sources are compiled for the build machine as well as the robot, so they can use Gum's `hardware` module and the Kotlin standard library, but not the FTC SDK.

## Use the devices

`hardware` resolves a declared device to its SDK type: `hardware(RobotMap.frontLeft)` is a `DcMotorEx`, `hardware(RobotMap.pinpoint)` is a `GoBildaPinpointDriver`, `hardware(RobotMap.limelight)` is a `Limelight3A`, and `hardware(RobotMap.camera)` is a `WebcamName`.
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
USB devices follow the `LynxUsbDevice`, directly under `<Robot>`: a `Webcam` with its serial number, and an `EthernetDevice` with the Limelight's `ipAddress`, which the SDK passes to `Limelight3A`. The SDK names a scanned Limelight after the hub's own address on that link; Gum uses the Limelight's address, because the SDK only uses that serial number to name the device.

## Activation

The SDK lists every `res/xml` resource whose root is `<Robot type="FirstInspires-FTC">` as a read-only configuration.
The Robot Controller activity calls `@OnCreate` hooks at the end of `onCreate`, before its service connects and it builds the hardware map from the active configuration.
The generated hook runs then and decides whether to make the generated configuration active:

1. New wiring: the build stores a SHA-256 hash of the generated XML in the hook, and the Robot Controller keeps the hash of the last wiring it started with. When the hash differs, as on the first install or after the Kotlin wiring changes, the hook activates the generated configuration and logs the one it replaced.
2. Unchanged wiring: the hook keeps whatever the Driver Station chose and logs that it kept it. A configuration chosen in Configure Robot stays active through app restarts and reinstalls of the same wiring.
3. Renumbered resource: the SDK saves the active configuration as its name and resource ID, and loads the resource by ID. A rebuild can renumber resources, so when the generated configuration is active under an old ID, the hook activates it again with the new one.

When another configuration is active and lacks a declared device, INIT fails and names the configuration to select.
A read-only configuration cannot be edited on the robot; change the Kotlin and reinstall.

Status: generation, packaging, and the hook's presence in the APK are verified. The Limelight and webcam serial numbers parse to the SDK's serial number types on the JVM, and `Limelight3A` accepts them. On a Control Hub (Android 7.1.2), an earlier hook that activated at every app start replaced the configuration the team had selected at install. Keeping a Driver Station choice across restarts, and a Limelight or webcam from this configuration, have not yet run on a Control Hub.
