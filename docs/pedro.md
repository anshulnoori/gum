# Pedro Pathing setup and tuning

Gum's `pedro` module brings Pedro Pathing 3 (`com.pedropathing:core` and `com.pedropathing:revhub` 3.0.2) and the `PedroDrive` subsystem; [lifecycle.md](lifecycle.md#pedro-pathing) describes how the drive runs.
The `pedro-tuning` module brings Pedro's AutoTune (`com.pedropathing:tuning` 1.0.3) and lists its procedures for the robot.

Pedro 3 is a rewrite of Pedro 2 (`com.pedropathing:ftc`). Configs are `ConfigVar` objects such as `MecanumConfig`, `PinpointConfig`, and `ForesightConfig`; Foresight replaces Pedro 2's PIDF constants; paths are built with `Paths.line` and `Paths.curve` instead of `pathBuilder` and `PathChain`, and have no callbacks. Pedro 2 constants and paths do not carry over, and a Pedro 2 robot is tuned again.

## Declare the robot's parts

TeamCode declares its drivetrain, localizer, and algorithm once, as a static `PedroRobot`:

```java
public final class PedroConstants {
    public static MecanumConfig drivetrainConfig = new MecanumConfig(c -> { ... });
    public static PinpointConfig localizerConfig = new PinpointConfig(c -> { ... });
    public static ForesightConfig foresightConfig = new ForesightConfig(c -> { ... });

    public static final PedroRobot ROBOT = new PedroRobot(
            map -> new Mecanum(map, drivetrainConfig),
            map -> new PinpointLocalizer(map, localizerConfig),
            () -> new Foresight(foresightConfig));
}
```

```kotlin
@Inject
@SingleIn(OpModeScope::class)
class Drive(follower: Follower = PedroConstants.ROBOT.follower(hardware.map)) : PedroDrive(follower)
```

AutoTune writes its results as Java, so a Java constants file takes them unchanged.
Device names should still come from `RobotMap`, such as `RobotMap.INSTANCE.getFrontLeft().getName()`, so wiring stays in one place.
Pedro checks a config's required values only when it builds the follower, at INIT. A TeamCode test that reads every `ConfigVar` catches a pasted config that misses one.

`ForesightConfig` has no defaults for its tuned values. Until the Foresight Tuner runs, use Pedro's sample values and do not follow paths or hold poses with them.

## Tuning builds

Pedro's AutoTune serves a web page from the Robot Controller app: port 10158 for the page and 12649 for its WebSocket. It starts whenever the tuning library is installed and has no off switch.
Rule R704(D) prohibits additional logging and streaming services, naming FTC Dashboard and Panels, so only a tuning build includes it:

```groovy
// TeamCode/build.gradle
if (providers.gradleProperty('pedroTuning').present) {
    implementation 'com.anshulnoori.gum:pedro-tuning:0.1.0-SNAPSHOT'
}
```

1. `./gradlew :TeamCode:installDebug -PpedroTuning` installs a tuning build.
2. `./gradlew :TeamCode:installDebug` installs a match build, without AutoTune. Install it after tuning and before every event.

Sloth's `deploySloth` sends only TeamCode classes, so it neither adds nor removes AutoTune; switching builds needs a full install.
Panels has its own switch, its Enable/Disable OpMode; turn Panels off before an event as well.

In a tuning build, `PedroTunerScanner` finds the static `PedroRobot` in TeamCode when the app starts and after each Sloth reload, and lists these procedures for it on the AutoTune page:

1. **Mecanum Tuner**: spins each wheel and records motor directions.
2. **Pinpoint Tuner**: records pod directions and offsets.
3. **Foresight Tuner**: measures velocities, decelerations, and braking, and fits the Foresight gains, with the robot's localizer and drivetrain.
4. **Tests**: hold, line, curve, interpolation, localization, odometry, driving, and pose tests, with the robot's parts.

TeamCode writes no `@Tuner` methods. The procedures are copied from Pedro's Quickstart; [pedro-tuning/NOTICE.md](../pedro-tuning/NOTICE.md) names the commit. A robot with another localizer needs that localizer's procedure added to `PedroTunerScanner`.

## Tune a robot

Tuning moves the robot. Each step needs the ones before it. Pedro's [tuning guide](https://pedropathing.com/docs/pathing/tuning) explains each procedure.

1. Check that the active robot configuration has the drive motors and the Pinpoint under their `RobotMap` names. Pedro finds them by name in the hardware map. Gum activates the configuration generated from `RobotMap` when its wiring is new, so its ports should match the robot.
2. Install a tuning build. Connect to the Robot Controller and open `http://192.168.43.1:10158`.
   When the robot is reached through adb instead of its Wi-Fi network, forward both ports first:
   `adb forward tcp:10158 tcp:10158` and `adb forward tcp:12649 tcp:12649`, then open `http://localhost:10158`.
3. With the robot on blocks, run the **Mecanum Tuner**. Enter the `RobotMap` names of the four drive motors. Paste the directions from the generated `MecanumConfig` into `PedroConstants.drivetrainConfig`, keeping the `RobotMap` names.
4. Send the change with `./gradlew :TeamCode:deploySloth`, which keeps the installed tuning build. Run **Tests**, then **Driving**, and drive with gamepad 1.
5. Run the **Pinpoint Tuner** with the Pinpoint's `RobotMap` name. Paste the generated pod type, offsets, and directions into `PedroConstants.localizerConfig`, and add the imports it needs.
6. Send the change. Run **Tests**, then **Localization**, **Odometry**, and **Pose**, and check the pose as you push and turn the robot.
7. On the floor, with at least 48 inches clear ahead and to the left, run the **Foresight Tuner**. Paste the generated `ForesightConfig` over `PedroConstants.foresightConfig`.
8. Send the change. Run **Tests**, then **Hold**, **Line**, **Curve**, and **Interpolation**.
9. Run the TeamCode tests, then install a match build. `http://192.168.43.1:10158` should no longer connect.

Status: the tuning and match builds and their contents are verified by building them. No procedure has run on a robot yet.
