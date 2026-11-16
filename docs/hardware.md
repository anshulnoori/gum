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

Register programs with the configuration. Every INIT then checks that the active configuration contains each declared device, and fails with one message listing any that are missing:

```kotlin
@OpModeRegistrar
fun registerOpModes(manager: OpModeManager) = registerPrograms(manager, RobotMap, main)
```

## Build

Apply `com.anshulnoori.gum.hardware` to the FTC app module after `com.android.application`. For each variant it:

1. Compiles `src/hardware/kotlin` on the build machine with `kotlin-compiler-embeddable`.
2. Runs Gum's generator, which writes `res/xml/<name>.xml` and a Java class with an `@OnCreate` hook.
3. Adds `src/hardware/kotlin` to the app's `main` sources.

The generator writes the format the FTC SDK 12.0 writes: a `LynxUsbDevice` with serial number `(embedded)` and parent module address 173, one `LynxModule` per hub, and device tags from the SDK's device annotations.

## Activation

The SDK lists every `res/xml` resource whose root is `<Robot type="FirstInspires-FTC">` as a read-only configuration.
The Robot Controller activity calls `@OnCreate` hooks at the end of `onCreate`, before its service connects and it builds the hardware map from the active configuration.
The generated hook makes the generated configuration active then, so each app start uses it without a robot restart.

Choosing another configuration on the Driver Station lasts until the app restarts.
A read-only configuration cannot be edited on the robot; change the Kotlin and reinstall.

Status: generation, packaging, and the hook's presence in the APK are verified. Activation on a Control Hub is not yet verified.
