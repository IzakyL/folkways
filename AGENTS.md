# RTS design review

Changes to player-facing RTS rules, command semantics, selection, control groups, formations, queues, or battle UI must receive an independent RTS design audit before implementation is finalized. Reuse the `rts_design_auditor` subagent in the current conversation; if it is unavailable, create an agent with that specialist role. Keep engineering verification separate from design approval.

The audit must check the current user-approved input and lifecycle contracts, modifier conflicts, server authority, observable failure states, and acceptance scenarios. Record accepted changes and deferred work in the combat architecture documentation. Do not expand a small implementation batch into the entire RTS roadmap or describe a static audit as gameplay validation.
