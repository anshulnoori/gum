# Robot operation

Gum coordinates FTC robot programs and the mechanisms they control.

## Language

**Program descriptor**:
A reusable definition of an OpMode, including its metadata and setup behavior. It is not an active robot session.

**OpMode session**:
One invocation of a program, from INIT through shutdown. A new invocation is a new session. Also called a run.

**Chain**:
The runs linked by successors, starting with a run started from the Driver Station. Chain-scoped values, such as a pose, last for the whole chain and hold no hardware.

**Setup body**:
The program body that configures behavior during INIT. Its return does not end the session.

**Subsystem**:
A robot mechanism, made of one or more regions, that owns its hardware and valid operations.

**Region**:
One part of a subsystem that is in one state at a time, alongside the subsystem's other regions, such as an intake arm's arm and roller. Each region is its own reservation.

**Operation**:
A specific request for a subsystem to perform behavior. Whether it succeeded is read from the subsystem's state.

**State**:
What a region is doing, one at a time, such as Collecting. Not "mode".

**Parent state**:
A state that contains other states, such as Raised containing Raised Low and Raised High. A region is always in a state without children; being in a child also means being in its parent.

**History**:
Whether entering a parent state returns to where the region last was inside it.

**Transition**:
A change from one state to another. A named command requests it, or the subsystem takes it by itself as an automatic transition. Not "move".

**Effect**:
The action a transition runs when it is taken, before the target state's entry.

**Request**:
A command that transitions a region to a state and finishes at once; the region stays in that state afterward. It can be valid only from certain states, or only while a guard allows it.

**Hold**:
A command that enters a state and keeps running. When it is cancelled or interrupted, the region returns to its initial state. If an automatic transition leaves the state, the hold ends and leaves the state alone.

**Reach**:
A command that finishes once a region is in a given state.

**Refused request**:
A request made from a state it is not valid from, or while its guard does not allow it. The state stays, its effect does not run, and Gum reports nothing.

**Routine**:
A coordinated command group built with Ivy's groups, such as scoring followed by parking.

**Reservation**:
Exclusive scheduled ownership of a region. A command group reserves every region its commands use for its lifetime.

**Handoff**:
Data a run leaves for later runs of its chain, kept in chain-scoped values. It does not transfer live subsystem ownership or hardware.

**Successor**:
The program a setup body ends with. After a run that started stops, by STOP or the autonomous timer, and its session has shut down, the host initializes the successor, which waits for its own START. Success is not required; a run that fails or stops during INIT has no successor.

**Safe response**:
The behavior defined for a mechanism after interruption, fault, or shutdown. These situations can require different responses.
