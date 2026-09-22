# Robot operation

Gum coordinates FTC robot programs and the mechanisms they control.

## Language

**Program descriptor**:
A reusable definition of an OpMode, including its metadata and setup behavior. It is not an active robot session.

**OpMode session**:
One invocation of a program, from INIT through shutdown. A new invocation is a new session.

**Setup body**:
The program body that configures behavior during INIT. Its return does not end the session.

**Subsystem**:
A robot mechanism with one owner for its hardware, state, and valid operations.

**Operation**:
A specific request for a subsystem to perform behavior, with an outcome that belongs to that request.

**Routine**:
A coordinated command or command group, such as scoring followed by parking.

**Reservation**:
Exclusive scheduled ownership of a subsystem. A command group reserves all participating subsystems for its lifetime.

**Handoff**:
Explicit data transferred from a completed session to its successor. It does not transfer live subsystem ownership.

**Successor**:
The program selected for initialization after a successful autonomous routine and safe shutdown of its session.

**Safe response**:
The behavior defined for a mechanism after interruption, fault, or shutdown. These situations can require different responses.
