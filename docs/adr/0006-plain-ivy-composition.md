# Commands compose with plain Ivy: no outcomes, timeouts, routines, or refusal log

Status: accepted on 2026-11-20. Reverses part of [ADR 0001](0001-runtime-ownership.md) (the adapter for operation outcomes) and part of [ADR 0005](0005-requested-and-automatic-transitions.md) (the refused outcome and the refusal log), and the earlier design decision that a refused request "logs and ends with a refused outcome".

## Decision

Gum does not rebuild what Ivy already provides.

1. **Outcomes are removed.** `Outcome` and Gum's `CommandBuilder` subclass that carried it are gone. A program reads whether a step succeeded from subsystem state, for example with Ivy's `conditional`.
2. **The refusal log is removed.** A request whose `from` does not include the current state leaves the state as it is. Gum logs nothing and has no `onRefused` callback. A team that wants a log writes it.
3. **`withTimeout` is removed.** Ivy limits a command with `raceWith(waitMs(ms))` or `until { }`.
4. **Routines are removed**, with `await`, `waitUntil`, `waitSeconds`, and `nextUpdate`. Programs compose with Ivy's groups: `then`, `with`, `raceWith`, `until`, `sequential`, `parallel`, `race`, `deadline`, `conditional`, `match`, `repeat`, `loop`, and `lazy`.
5. **`request`, `hold`, and `reach` stay**, as small `CommandBuilder` commands. They tie a subsystem's KStateMachine transition to an Ivy requirement, which Ivy has no equivalent for.

## Consequences

apex-2025's programs need none of the removed pieces: its autos are timed sequences, and its waits check sensor state.
`lazy { }` reserves nothing by itself, so a lazily built command that uses a subsystem adds `.requiring(...)`.
Ivy's group bugs, such as a cancelled `Sequential` ending steps that never started, now reach programs directly; they are fixed upstream in Ivy, not in Gum.
The double-end guards in `request`, `hold`, and `reach` stay until Gum builds against an Ivy release with Ivy#10.

## Alternatives

Keeping outcomes and `withTimeout` gave routines a typed result and kept a timed command's interrupted, conflict, and blocked behaviors, which Ivy's `Race` resets. No apex program branched on a result, and the behaviors matter only for low-priority suspendable commands such as a default drive.
Keeping routines gave sequential code with local variables and `finally` blocks, at the cost of a second composition language beside Ivy's groups.
