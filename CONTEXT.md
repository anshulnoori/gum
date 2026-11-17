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
A robot mechanism with one owner for its hardware, state, and valid operations.

**Operation**:
A specific request for a subsystem to perform behavior, with an outcome that belongs to that request.

**State**:
What a subsystem is doing, one at a time, such as Collecting. Not "mode".

**Transition**:
A change from one state to another. A named command requests it, or the subsystem takes it by itself as an automatic transition. Not "move".

**Effect**:
The action a transition runs when it is taken, before the target state's entry.

**Request**:
A command that transitions a subsystem to a state and finishes at once; the subsystem stays in that state afterward. It can be valid only from certain states.

**Hold**:
A command that enters a state and keeps running. When it is cancelled or interrupted, the subsystem returns to its initial state. If an automatic transition leaves the state, the hold ends and leaves the state alone.

**Reach**:
A command that finishes once a subsystem is in a given state.

**Refused request**:
A request made from a state it is not valid from. The state stays, and Gum reports it.

**Routine**:
A coordinated command or command group, such as scoring followed by parking.

**Reservation**:
Exclusive scheduled ownership of a subsystem. A command group reserves all participating subsystems for its lifetime.

**Handoff**:
Data a run leaves for later runs of its chain, kept in chain-scoped values. It does not transfer live subsystem ownership or hardware.

**Successor**:
The program a setup body ends with. After a run that started stops, by STOP or the autonomous timer, and its session has shut down, the host initializes the successor, which waits for its own START. Success is not required; a run that fails or stops during INIT has no successor.

**Safe response**:
The behavior defined for a mechanism after interruption, fault, or shutdown. These situations can require different responses.
