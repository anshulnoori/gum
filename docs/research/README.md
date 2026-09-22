# Research evidence

This index preserves source references from the Gum v2 design discussion.
It does not certify dependency compatibility or runtime performance.
The detailed platform and motion reports remain in the preserved old checkout until their claims receive a fresh review.

## Existing team code

[apex-2025 HardwareContainer](https://github.com/FTC-APEX-24513/apex-2025/blob/5e624b6dda1abb25f9f681d8762d378725ee7419/TeamCode/src/main/java/org/firstinspires/ftc/teamcode/di/HardwareContainer.kt)
uses kotlin-inject, a HardwareScope, generated construction, and explicit periodic startup.
Subsystems receive HardwareMap and perform their own device lookup.
The [TeleOp](https://github.com/FTC-APEX-24513/apex-2025/blob/5e624b6dda1abb25f9f681d8762d378725ee7419/TeamCode/src/main/java/org/firstinspires/ftc/teamcode/opmodes/teleop/DriverTeleOp.kt)
already uses a top-level program value.

## Runtime and registration references

- [Ivy pinned source](https://github.com/Pedro-Pathing/Ivy/tree/d35242aaaf2db863540154a73190b05c25136079): scheduler, command composition, and end reasons.
- [MercurialFTC pinned source](https://github.com/Dairy-Foundation/MercurialFTC/tree/44317da698df54b1f644c91866596181ee6ff4af): program descriptors, field discovery, FTC host, and successor initialization.
- [Sloth](https://github.com/Dairy-Foundation/Sloth): reload, scanner, and registrar interfaces. This URL is mutable.
- [KStateMachine](https://github.com/KStateMachine/kstatemachine): hierarchy and event APIs. This URL is mutable.
- [KStateMachine threading](https://kstatemachine.github.io/kstatemachine/pages/multithreading.html): integration constraints, not a benchmark.
- [Kotlin coroutines](https://kotlinlang.org/docs/coroutines-basics.html): standard coroutine model and structured concurrency.

MercurialFTC's inspected functional callback is ordinary Kotlin.
Its continuation builders and fibers are optional to the caller, but its FTC wrapper initializes the Mercurial runtime.
Its limited coroutine adapter is not general kotlinx.coroutines integration.
The older Mercurial default branch is not evidence for MercurialFTC's v2 dependency.

## DI alternatives

- [Metro](https://zacsweers.github.io/metro/): compiler-plugin DI and compiler compatibility requirements.
- [kotlin-inject](https://github.com/evant/kotlin-inject): KSP-generated DI, already used by the team.
- [Dagger](https://dagger.dev/): generated DI alternative.

Compile-time generation does not prove one option is faster on a Control Hub.
Metro and Sloth generated-code compatibility remains untested.

## FTC platform and motion research

- [Official FTC SDK](https://github.com/FIRST-Tech-Challenge/FtcRobotController): obtain the current release and tagged consumer template before version selection.
- [Competition manual](https://ftc-resources.firstinspires.org/ftc/game/manual): mutable authoritative rule source; record the revision used for competition validation.
- [REV Control Hub](https://docs.revrobotics.com/duo-control/control-system-overview/control-hub-basics): hardware constraints.
- [Pedro Pathing](https://github.com/Pedro-Pathing): candidate motion integration and Ivy sources.
- [Road Runner](https://rr.brott.dev/): alternative motion integration.

Earlier reports identified substantial Pedro API changes and separate motion/localization update ownership concerns.
No motion stack winner, loop-frequency target, or hardware compatibility result was selected in the discussion.
