# Assistant tools

## Inline building patterns
<!-- guide:pattern -->

Path points and area corners are the ground cells the building stands on, as clicked with the Colony Book.
Building content starts at Y+1. Foundations and grading can also work at or below the marked Y.
See the `anchors` field returned by `patterns()` for each pattern's marking instructions.
`draft()` returns the resolved world coordinates in `anchors`.

Use `print(help("pattern"))` for the draft vocabulary and examples. Pass a Starlark program defining
`draw(site)` to `draft(source=..., dimension=..., at=...)` to inspect its block count, bounds and materials.
Use the diagnostic location to revise the program, then pass the same source and mark to `set("builds/site", {"source": ..., "dimension": ..., "at": ...})`
to request confirmation. Both tools accept either `pattern` or `source`. Inline patterns can load installed pattern
libraries. The server stores the program with the confirmed order.

List build orders with `ls("builds")`. Read `builds/<id>` for the pattern, marks and any available progress.
Delete that path to request cancellation. Write `facilities/<name>` with `kind`, `dimension` and either
`from` and `to` or `points` to file a zone or path, with optional initial `parameters`.
Write `surveys/<name>` with `dimension` and `points` to request a survey. Creation names label proposed
writes in the current script. Use the assigned IDs after confirmation.

## Engine concepts
<!-- guide:concepts -->

Use these terms to reason about what the tools return. Explain results to the player in gameplay words, not these terms.

**Holdings and facilities.** The colony only knows what it holds: blocks registered with the book, drawn zones and
paths, networks, vehicles and player-defined facilities. Anything the colony operates is a facility, read under
`facilities/<id>`. A facility's extent owns every block and entity in it exclusively; automatic work such as farming,
building and damage control skips those blocks.

**Demand and the plan.** Work exists only because something asks for it: an order, a stock target, a zone setting, a
facility routine or a resident's own need. The planner periodically turns demand into a graph of nodes, the steps of
work such as fetching, carrying, crafting, operating or building. A node needs goods, a stand (a place to stand while
working), a way (a route there) and a hand (a resident who is permitted, equipped and free). Plan reads describe the
last plan, not the live world: check `capture_tick` and `age_ticks` before comparing them with the world.

**Outcomes.** `trace()` gives each node one outcome. `assigned`: a resident has it. `waiting`: nobody can take it yet,
with an unassigned code. `stalled`: it waits on another node; follow `root` to the node that actually blocks.
`refused`: a shortfall code says the goods cannot be supplied. `cooling`: it failed recently and retries at
`retry_at` after `failures` attempts. `structural: true` marks a code the planner should never produce.

**Reservations.** When the plan commits goods, it books them so two jobs cannot use the same items. Bookings are lots
(goods in a store), transit (goods on their way to a store), space (room in a store), cargo (goods in a resident's
pack) and handed (goods one step passes to the next). Stock counts come before bookings, so a store can show items that
are already promised elsewhere.

**Suspended work.** A resident can be pulled off work, for example by a need such as sleep or food, a direct command, an
unload or a revoked permission. The work keeps its booked goods and resumes later; snapshot reads list it under
`suspended` with the reason.

**Facility parts.** Controls are operation points: a signal sets a value (a lever, a gear, an angle) and a port moves
items, fluid or energy. Readings are the values a facility reports; parameters are its settings. A routine is a
sequence of operations that becomes plan work, so residents carry it out like any other work; remote controls run
without a resident. Triggers start routines: manual (card buttons and `run`), schedule, condition, standing and
demand (a workshop pulled by the plan). Projections tell the rest of the colony what the facility means: gates
residents may pass, lanes that carry goods, keep-out areas, workshops that offer recipes and protected cells.

**Facility runs.** `recent` holds the last run's routine, ending and reason; `runs/<id>` holds an active script run. A
card button is greyed out while its routine is running (`running`), while readings disagree with `enabled_when`
(`state`), or while readings cannot be taken (`unknown`, usually because the facility is unloaded). A script fault
pauses a condition trigger until it is switched back on.

**Fire control.** Resident sightings and facility probes report hostiles to the colony. Fire control assigns targets
to facilities whose trigger uses `folkways_core:fire_control`; an RTS attack order marks a target for five seconds.

**Unknown is not zero.** Unloaded chunks, unopened loot, unsupported storage and network data from an old snapshot are
unknown. Never report them as empty.

## Troubleshooting
<!-- guide:troubleshooting -->

When work is not happening, read in this order and stop once the cause is clear:

1. `get("colony/problems")` for stalls the colony already explains.
2. `trace(vocation=..., site=...)` for the work in question: its outcome and code.
3. `plan_node(id)` for a node's dependencies, diagnostics and cooldown; for a stalled node, read its `root`.
4. The check that the code points to (below).
5. `reachable(item)` and `stock(item)` when goods are involved.
6. `plan_events(node=...)` for what happened to a node over time: offered, started, failed, released.
7. `recent_errors()` for exceptions.

Classify what you find. A code whose text names supplies, access or a work permission is a configuration problem:
explain how the player fixes it. A source that `reachable` reports as `unsupported_storage` is a limit: explain it and
suggest a feature request. A related exception stack is a bug: suggest reporting it with the stack. Enough visible
supplies alongside `no_source`, an unknown code, or a `structural: true` code whose setting is already correct means
a suspected bug: suggest reporting it with both readings. Check snapshot age and incomplete sources before claiming a
contradiction.

### Shortfall codes

`trace()` reports codes in lower case under `code` and in upper case under `reason_code`.

These mean the plan cannot supply the goods a node needs; `items` lists what is missing.

- `NO_SOURCE`: no reachable stock and no way to make it. Read `reachable(item)`; look at sources and at the reasons
  listed under `invisible`.
- `NO_ROOM`: nowhere to put the output. Check that registered stores have space and allow putting the item.
- `NO_WAY`: supplies exist but no route reaches them. Check `plan_inputs(kind="ways")` and the terrain between them.
- `NO_HAND`: nobody permitted and equipped can make the goods. Check work permissions, tools and
  `plan_inputs(kind="workshops")`.
- `TURNED`: an operation refused the work for its own reason. Look at the site, the facility's `recent`, and
  `plan_events` for the failure.
- `COOLING`: it failed recently and waits until `retry_at`. Read `plan_events(node=...)` for the failure.

### Unassigned codes

These mean the work exists but nobody can be given it yet.

- `NO_FREE_HAND`: every suitable resident is busy. Normal under load; compare the work priority settings.
- `NO_LICENCE`: nobody has the work permission. Check `residents/<id>/work/<vocation>`. Structural.
- `NO_TOOL`: nobody carries a suitable tool. Check tools in storage and equipment profiles. Structural.
- `NO_WAY`: no route to the work site. Check terrain, doors and gates on the way.
- `WAY_PENDING`: a route is still being searched. Wait for the next plan.
- `PACK_FULL`: the resident's inventory cannot hold the goods. Structural.
- `PACK_BUSY`: the resident must deliver what they carry first.
- `CLAIM_TAKEN`: another resident has claimed it.
- `SAME_WORKER_SPLIT`: linked steps must go to one resident and could not. Structural.
- `WORKER_AWAY`: the assigned resident is unloaded or far away.
- `NO_FREE_STAND`: no free place to stand at the site. Look for blocked or occupied stands.
- `BEYOND_HORIZON`: someone could take it, but later than the plan looks ahead. Wait for a later plan.

### Invisible sources

`reachable(item)` lists stores the plan cannot use under `invisible`, each with a reason: `unloaded`,
`unauthorized`, `managed_by_facility` (a facility port, used only by that facility's work), `unsupported_storage`,
`not_container`, `unknown_contents` (unopened loot), `take_disabled` (the store does not allow taking) and
`no_slots`. Network sources can report their own reason.

### Facilities

For a facility that does not act, read `facilities/<id>/readings`, `recent` and `runs`. A failed or interrupted
`recent` gives the reason. A paused condition trigger needs `triggers/<name>/enabled` set back to true. A greyed card
button means `running`, `state` or `unknown`, as described in `help("concepts")`. Routines that need a resident show up
in `trace()` like any other work.

## Changing colony state
<!-- guide: changing-colony-state -->

Use `help("state")` for paths and value shapes. List facilities with `ls("facilities")` and use
`facilities/<id or name>/parameters/<key>` for held facilities and colony settings.
Read with `get(path)`, stage changes with `set(path, value)`, and use `delete("facilities/<id>")`
to release a held zone or path. One confirmation covers the final changes.

```python
id = ls("residents")[0]
set("residents/" + id + "/name", "Ada")
```

## Assistant client data
<!-- guide: assistant-scratch -->

The assistant runs Starlark through `run`. Use `print(help("client"))` for the client paths.
`client/view` records the player's viewpoint and crosshair target when they asked.
`client/marks` records their Colony Book marks at the same moment. It gives the tool, dimension, last marking
tick and the marked corners or path points.
Use `ls("client/history")` to list earlier conversations and read their text by session path.

Use `get` and `set` to read and write `client/scratch` for the current conversation.
New sessions start with an empty dictionary. Each snapshot can hold up to 256 KiB of UTF-8 JSON.
Writes take effect immediately and are available in later runs without approval.

With model image input enabled, use `capture()` to request a nearby view. The client takes
the screenshots after the script succeeds and attaches up to six images per run; sets of more than two are smaller.
`capture(around=..., views=...)` frames a region from several sides in one call: `around` is `"changes"` for what
the same script staged, an area `{"from": ..., "to": ...}` or two XYZ corners. `views` lists the sides the cameras
stand on (`north`, `east`, `south`, `west`, the four diagonals or `top`) or gives a count from 1 to 5 (diagonals,
then top); it defaults to the four diagonals. Each camera stands outside the region, in front of anything that
would block it, and looks at the centre. Each image's `pose` names its view.

Check a build draft or a facility change before the player reviews it. Stage it and call
`capture(around="changes")` in the same script: staged builds show as their drawn blocks and zones or paths as
their marks. Look for misalignment with existing structures, broken joins, wrong heights and wrong orientation.
A script that captures staged changes returns `held` and is not sent for review. Fix and check again if needed,
then run the final script without `capture()`.

```python
set("builds/site", {"pattern": "<pattern id>", "dimension": "minecraft:overworld",
                    "points": [{"x": 0, "y": 64, "z": 0}, {"x": 24, "y": 64, "z": 0}]})
capture(around="changes", views=["north", "southeast", "top"])
```

## Assistant facilities
<!-- guide: facilities -->

Express the player's intended behavior in Starlark. Choose standard modules and library routines when they
already implement a useful part of that behavior. The gate below uses the optional `write_all` routine and
reading comparisons for simple lever operation.

The `parts` dict in `controls()` and `mechanism()` summarizes the part IDs in that result. Before writing
a routine for an unfamiliar part, read its full entry with `help('parts/<part id>')` using the discovered ID.

Use `controls(area)` to find operation points in an inclusive box. Use `controls(structure="<UUID>")`
for the whole moving structure, whose coordinates are local to the structure. The result includes
`extent`, `controls`, `prefabs`, and `overlaps`. Check the part, coordinates, signal type and feedback
to identify the lever or gearbox that operates the gate. For a gearbox, use its discovered part and
a target accepted by its signal type. When the list is large, keep it in conversation `client/scratch`
and return only counts or a few matching candidates.

Discovery proposes non-air blocks, controls and entities, with moving blocks in their structure's local coordinates.
Edit `extent.elements` before applying. Use `{"space": space, "block": [x, y, z]}` for one block,
`{"space": space, "min": [...], "max": [...]}` for an inclusive box, or `{"entity": "UUID"}` for an entity.
A space has a `dimension` or `structure` key. Pass the edited extent to `controls(extent=...)` to inspect it.
For an entity control, copy its `entity` and `part` into the control declaration. For an entity-only extent,
provide `anchor_space` and `anchor` for the facility's site.

For this example the lever is at (119, 64, -30) and the iron door occupies the two cells immediately east.
Use the actual coordinates of the selected gate.

```python
area = {"dimension": "minecraft:overworld",
        "from": {"x": 119, "y": 64, "z": -30},
        "to": {"x": 120, "y": 65, "z": -30}}
found = controls(area)
set("client/scratch/north_gate_candidates", found["controls"])
result = {"count": len(found["controls"]), "prefabs": len(found["prefabs"])}
```

After checking the candidate against the build, use its part and position to define the gate.
Control offsets are relative to the anchor. Gate cells use coordinates in the facility's space.
The preview lists prefab coverage changes and the gate and protection projections.

```python
lever = [c for c in get("client/scratch/north_gate_candidates")
         if c["part"] == "lever" and (c["x"], c["y"], c["z"]) == (119, 64, -30)][0]
set("facilities/north_gate/definition", {
    "name": "north_gate",
    "extent": {"elements": [{"space": {"dimension": lever["dimension"]},
               "min": [119, 64, -30], "max": [120, 65, -30]}]},
    "anchor": [lever["x"], lever["y"], lever["z"]],
    "controls": {"lever": {"part": lever["part"], "offset": [0, 0, 0]}},
    "routines": {
        "open": {"use": "folkways_core:write_all", "with": {"lever": True}},
        "close": {"use": "folkways_core:write_all", "with": {"lever": False}},
    },
    "triggers": {
        "open_button": {"kind": "manual", "routine": "open"},
        "close_button": {"kind": "manual", "routine": "close"},
        "morning": {"kind": "schedule", "routine": "open", "dayTime": 1000, "enabled": True},
        "night": {"kind": "schedule", "routine": "close", "dayTime": 13000, "enabled": True},
    },
    "projections": [{"gate": {"cells": [[120, 64, -30], [120, 65, -30]],
                              "axes": ["x"], "open_when": {"lever": True}}}],
    "ui": {"card": [{"button": "open", "icon": "minecraft:iron_door", "enabled_when": {"lever": False}},
                    {"button": "close", "icon": "minecraft:iron_bars", "enabled_when": {"lever": True}},
                    {"toggle": "morning", "icon": "minecraft:clock"},
                    {"toggle": "night", "icon": "minecraft:clock"},
                    {"readout": "lever", "as": "light", "label": "Gate open"}]},
})
```

A gate projection can set `axes` to `["x"]`, `["z"]`, or both. By default,
a row of cells crosses perpendicular to the row, and a single column allows both axes.
`open_when` accepts either a reading comparison dictionary or source defining `when(readings)`, for example
`"def when(readings): return readings.get('lever', False)"`. The gate stays closed until its predicate has returned
`True`. Routing uses the cached boolean.
Use a routine to operate the gate controls.

Any colony member can use the RTS command card. Its status line shows the running routines or the
latest result. `ui.card` lists the elements: `{"button": routine}` runs a routine that has a manual trigger, without
arguments (routine defaults still apply); `{"toggle": trigger}` switches a scheduled or conditional trigger; `{"readout": reading, "as": style}` shows
a reading as `text`, `light`, `progress` or `slots`. A button or toggle may set `icon` to an item ID; pick an item
that suggests what it does (a door to open, bars to close, a clock for a schedule). A button, target, or choice
accepts the same comparison dictionary or `when(readings)` source in `enabled_when`. The card disables the
action until the condition is satisfied, and while its routine is running. A readout may set `label`, the text shown instead of the
reading's name; write labels and routine names in the player's language. Keep every name on the card short: one or
two words for the action or the state, such as Dig, Open or Parked, never a sentence saying what it does. Use the
same names when you tell the player how to use the card.

Add `{"target": routine, "pick": "entity|pos"}` for a manual routine that takes `target`.
Click its card slot and left-click an entity or block in the world. Press Esc to cancel.
Set `pick` to `entity` for an entity UUID, `pos` for the block's world coordinates `[x, y, z]`,
or `entity|pos` for either, with the entity taking precedence. Use `item` for a dropped item's UUID,
`block` for a clicked block, or `ground` for the standing position above it. Use `box` for an area: the player
clicks two blocks as opposite corners and `target` holds `[x0, y0, z0, x1, y1, z1]`, lowest corner first; a box
is never a right-click default. Whatever the pick, the value arrives as `args["target"]` and under no other name, so
read it there and pass it as `target` when you trial the routine. On a world right-click,
the card uses the first target with `default: True` that accepts what is under the cursor.
Targets accept `icon`, `label` and `enabled_when`, and are disabled while their routine runs.

A conditional trigger uses `{"kind": "condition", "routine": "close", "enabled": True,
"when": {"lever": True}, "period": 20, "cooldown": 100, "hysteresis": 2}`. Every reading in
`when` must match. Evaluations require loaded facility chunks and run at least `period` ticks apart
(minimum 10, default 10). `hysteresis` is the number of consecutive matching evaluations (default 1).
While the condition holds, the routine can run again once the previous run has ended and
`cooldown` ticks have elapsed since submission (default 0).
A registered `source` can supply arguments and serve as the condition by itself or alongside `when`.
Set `"source": "folkways_core:fire_control"` to require a fire-control assignment and pass `target`
as an entity UUID string or a three-number world position.

A reading such as `"nearby": {"probe": "folkways_vanilla:hostile_within", "range": 48}` in `readings` counts loaded hostile
entities within a spherical range of the facility and reports them to colony fire control.
The range is greater than 0 and at most 128 blocks. An optional `offset` selects a block inside
the facility as the scan origin, defaulting to the anchor. Residents' sightings also contribute.
An accepted RTS attack on a hostile marks it for five seconds of server game time. Fire control
prioritizes it, and the commanding player sees a marker above the target.

List facilities with `ls("facilities")`. Read `facilities/north_gate/definition` for the editable definition,
and the `readings`, `recent`, and `runs` paths under that facility for live information.
Delete `facilities/north_gate/definition` to remove the facility and restore the covered prefab.
Delete `facilities/<id>/runs/<run id>` to stage cancellation of an active script run.

Write `facilities/<id>/runs/<new name>` with `routine` to stage a manual trigger. `args` is an optional
dictionary of routine arguments; values may be nested dicts and lists, so a trial of a workshop routine can pass
the `recipe` a workshop run would get. The routine needs a trigger of kind `manual`. The same confirmation lists the state changes and manual runs.
On approval, all state writes apply before any run, in the following example too.

```python
set("facilities/north_gate/runs/open", {"routine": "open"})
set("facilities/north_gate/triggers/night/enabled", False)
```

A card can let the player pick one of several values and adjust a number. Keep each in `parameters`, for example
`{"mode":"a","range":96}`, and add these elements to `ui.card`.

```json
[
  {"choice":"mode","options":["a","b"],"labels":["First","Second"],"icons":["<item id>","<item id>"]},
  {"number":"range","min":32,"max":160,"step":16,"label":"Range"}
]
```

The card lays a choice's options side by side and marks the current one; click an option to choose it,
or press the choice's hotkey for the next one. The plus and minus buttons change the number by one step.
Both controls save the value to the facility. A choice also accepts `label` (its name on the card), `icon`,
`icons` (one item ID per option, `""` for none), `default` (the starting option when the parameter is unset,
otherwise the first) and `enabled_when`. With `"run": routine`, choosing also runs that manual routine with the
chosen option as the argument named after the parameter. A routine's `with` can read a parameter's
current value as `{"param": name}`, for example `{"ammunition": [[{"param": "shell"}, 1]]}`. Explicit run arguments
still override defaults.

A target with `"default": true` also runs when the player right-clicks the world with the facility selected in RTS,
so `{"target": "move", "pick": "pos", "default": true}` sends the facility to the clicked point.

### Rides

A ride lets the planner send residents between the stops of a player-built lift, platform, or ferry.
Add a `ride` entry to `projections`. Stop cells are standing positions in the facility's space,
including structure-local coordinates for a facility anchored in a moving structure. Include the stops
and the space that carries riders between them in the extent.

For a lift with two stops, use a projection like this.

```json
{"ride": {
  "stops": {"ground": {"cells": [[120, 64, -30]]}, "top": {"cells": [[120, 72, -30]]}},
  "routine": "ride",
  "ticks": 200,
  "capacity": 2,
  "available_when": {"ready": true}
}}
```

Each pair of stops offers travel in both directions while `available_when` matches the signal
feedback, or while a Starlark `when(readings)` source given as a string returns true, as `open_when` does.
Omit it for an always-available ride. Set `ticks` to the expected trip time, including
any time needed to fetch the cabin. It contributes to the route cost and sets the scale for timeouts.
The facility runs one ride batch at a time, with up to `capacity` residents waiting for the same
trip. The routine receives `args["from"]`, `args["to"]`, and `args["riders"]` (resident UUID strings).
The leg finishes once the routine succeeds and the resident is standing in a landing cell.
If the routine fails, the resident leaves the ride, or the leg times out, the leg returns a travel refusal.

Use `controls(area)` to find the lift's inputs and feedback. Try candidate values with
`probe(control={"x": x, "y": y, "z": z, "part": part}, value=value, at=cabin_pos)`. Try both directions,
check which input moves the cabin, and record the arrival reading and travel time at each stop.
Use those values in `routines.ride.source`. A remote control can be written with only the rider present.
Provide an operator for a local control. This example assumes `destination` selects a floor and
`cabin_at` reports the floor the cabin has reached. It also assumes the cabin is already at the
boarding stop or the machine fetches it as part of serving the request.

```python
def step(state, readings, args):
    if readings["_failure"] != None:
        return state, [fail(readings["_failure"])]
    if state == None:
        floor = {"ground": 0, "top": 1}[args["to"]]
        return floor, [write("destination", floor), await("cabin_at", floor, 200)]
    return state, [done]
```

### Positioning

A machine that moves along axes exposes each axis as a `motion` part (`position`, `moving`), driven by one
or more signal channels. Name each motion part and channel in `controls`. Use `carried_by` and `per_unit` for parts
whose address changes when another axis moves.

Bind the card's target to a manual script. The optional `@std//position` module uses `@std//ik` to solve for
axis extensions. Its `frame` gives the resting point and one vector per motion part. `response` maps each axis
to its calibrated channel, stop value and rates (see [Calibrating mechanisms](#calibrating-mechanisms)).
`order` specifies the movement order, `hold` keeps selected axes at their current extension, and `clearance`
can provide a world XYZ waypoint for a separate move before the target.

```python
load("@std//position", "position_step", "position_readings", "position_ready", "position_check")

def step(state, readings, args):
    return position_step(state, readings, args)

def readings(input, args):
    return position_readings(input, args)

def ready(readings, args):
    return position_ready(readings, args)

def check(args):
    return position_check(args)
```

A routine named `move` publishes `move.bounds` and `move.current`. A `keepout` may use `while_moving` and
`margin`, or `{"source": "..."}` defining `keepout(readings)` returning world boxes
`[[minX, minY, minZ, maxX, maxY, maxZ], ...]`.

### Facility scripts

A facility can declare a `posts` map keyed by post name. Each `stance` gives a `footing` or `seat_block`
offset from the anchor, or a `seat_entity` UUID with an optional `seat` index. In-reach signals and
ports are covered automatically. Use the optional `controls` list to add named controls. Set
`staffing: "standing"` for a standing post or `staffing: "on_demand"` for timed runs that need staffing.
A card light can read `posts.<name>.staffed`. A staffed post executes `deliver` and `collect` from the
occupant's pack. Regular hauling can bring missing goods to the occupant before delivery.

Use `folkways_core:write_sequence` with `steps` sorted by `atTick`, starting at tick 0. Signal steps
take `control` and `value`. Item steps take `item`, `count`, and a named `from` port, `into` port,
or both. Omit one endpoint to use the resident's pack. Have the goods ready before their scheduled
transfer. One staffed post can perform the whole sequence on schedule. With a shared stance, one
resident can also perform the sequence after arriving.

A part with a `level` signal takes whole levels from 0 to 15. Its feedback is what the part emits, which need not
equal the level written: compare the two in a probe before relying on it. A part that comes with its own seat
appears in `controls()` with an on-demand post; a timed sequence over the controls of one such post starts at tick 0
once the resident is seated.

A facility routine can use `{"source": "...", "with": {}}`. Its source
defines `step(state, readings, args)` and returns `(state, actions)`. Each run starts with `None`.
State can contain booleans, finite numbers, strings, lists, string-keyed dictionaries and `None`.
`args` contains the routine defaults and the arguments supplied for this run. `readings` is a fresh
snapshot of the readings and facility parameters, with unavailable values omitted. `readings["_failure"]`
is `None` or the previous batch's failure reason. A batch interrupted by a reload reports an interruption.

Return actions in execution order. The plan runs the entire batch before calling the next step.

- `write(control, value)` writes a named signal through its staffed post or sends a resident to operate it.
- `write_sequence(steps)` runs timed steps with the same fields as the library routine.
- `await(reading, value, timeout)` waits for a named reading to agree, with a timeout in ticks. `within=`
  accepts each number of the reading within that tolerance, such as a turret's aim that settles near the target.
- `run(library_routine, arguments)` calls a routine library ID with an argument dictionary.
- `deliver(port, items)` brings items to an item port. `collect(port, items=None)` takes items
  from the port to colony stores. Item lists contain `{item, count}` entries; a delivery entry can add
  `components`, a data component map the item must carry, such as `{"createbigcannons:power": 4}`.
- `done` finishes successfully. `fail(reason)` reports a retryable failure, and
  `fail_permanent(reason)` reports a permanent failure. Use any of these alone in the list.

An action can set `on_fail="recover"` to call `recover(state, failure, input, args)` after
its node fails. The handler returns `(state, actions)` and receives `action_index` (zero-based),
`kind`, `reason` and `retryable` in `failure`.

For a facility with `approach_left` as a reading and `left` and `right` as signals, the source could be

```python
def step(state, readings, args):
    if readings["_failure"] != None:
        return state, [fail(readings["_failure"])]
    if state == None:
        door = "left" if readings.get("approach_left", False) else "right"
        return door, [write(door, True), await(door, True, 60)]
    return state, [done]
```

In `readings`, use `{"source": "def read(readings, params): return readings.get('lever', False)"}` to
publish a derived value. Return `None` when the reading is unavailable. Probe readings declare a `probe` ID
and an anchor-relative `offset`. Core probes include `folkways_core:block_state` (`id` and `properties`),
`folkways_core:block_entity` (block entity data), `folkways_core:item_counts` (counts by item ID, with a
`part` naming the port), and `folkways_core:entity_data` (entity data, with an `entity` UUID at the offset).

A routine's source can also define these hooks.

- `path(readings, args)` returns world XYZ points for an `ui.overlay` entry such as `{"path": "move"}`, or
  `{"points": [...], "strike": True}` to stop the drawn path at the first block it meets and mark it there.
- `readings(readings, args)` returns a dictionary. Each key is published as `<routine>.<key>`. A `center` key, a
  world XYZ, centres the facility's range rings.
- `check(args)` returns `None` to accept or a string giving the refusal reason.
- `ready(readings, args)` returns a boolean. A pending run waits for `True` before its first step.

Path and reading hooks receive resolved routine defaults. Run hooks receive the submitted arguments over
those defaults. Condition triggers accept source defining `when(readings)`, just like gates and card actions.
These observations are sampled at intervals of at least 10 ticks while loaded. The server snapshots inputs,
a worker evaluates the source within step, time and memory budgets, and a later tick publishes cached results.
Faults pause the script and appear in the latest result. Use the trigger switch to re-enable a faulted
condition or update the definition to re-enable other faulted samples.

Use `load("//scripts/<name>", ...)` for colony libraries. Available standard modules include `@std//math`,
`@std//text`, `@std//ik`, `@std//position`, `@std//sweep` and `@std//ballistics` (see [Weapons](#weapons)).

With `@std//ik`, probe each moving joint at a few control values and fit it from the measured positions.
When every control moves the end along a straight line, `fit_straight(samples)` fits them together from
`[[c0, c1, ...], [x, y, z]]` samples, each a control vector tried and where the end stood, and returns the chain, or
`None` when the samples do not span every control. Otherwise sweep each control from one shared starting value while
holding the others there, fit it with `fit_prismatic` or `fit_revolute`, and build the chain at that pose: set each
fit's `zero` to its starting value and give `chain` the end's starting position as `tip`. Then solve for a target,
check the returned `error`, and write the returned control values.

A facility on a moving structure reads that structure's frame as `readings["_frame"]`: the world `origin` of its
space and the world direction of its `x`, `y` and `z` axes. `to_local(frame, point)` and `to_world(frame, point)` from
`@std//ik` carry points between the world and that space, so a chain fitted in the structure's space stays fitted
wherever the structure goes.

Use `@std//sweep` in a facility routine to cover a picked box with a calibrated tool. A plane is a box one cell
thick. The sweep orders stops automatically from the tool's current position and uses `readings._target` to
route its footprint through open space, allowing contact with standing cells at the chosen working stop.
Set `offset` to the world-axis displacement from the chain end to the tool's working point, and use
`at_stop` for timed control writes at each working stop. The routine supplies the tool action.
Mobile facilities reposition automatically when the tool runs out of reachable cells. Set `relocate=False`
to keep the current stand. When `sweep_step` returns no actions, use `worked` and `left` to track coverage.
Load the library in the routine source and try it with `probe(routine=...)`. Pass the picked box as `target`,
the calibration samples as `fits`, and the tool's half-size as `reach`. For a tool calibrated in its moving
structure's space, set `local=True`. Put the calibration in the routine's `with` defaults so the card's pick
only adds `target`.

First trial any routine that breaks, places or moves blocks with
`probe(routine=<routine>, definition=<definition>, args=<arguments>)` or
`probe(routine=<routine>, facility=<id>, args=<arguments>)`.
Run it in the world only after the trial ends `done` and changes nothing outside what the player allowed.
Debug the routine only with trials. After a world run, check the surrounding blocks and stop if anything outside the
allowed area changed.

### Processing workshop

Set `workshop` to `{"routine": "process"}` to let the planner call the named Starlark
routine when it needs goods. The script can define `recipes(input)`, which returns a list
of batches with `inputs`, `outputs`, `ticks` and an optional `id` and `scale`. The workshop refreshes
this list when sampled readings change. Inside `recipes(input)`, use `recipes_of("minecraft:smelting")` to
read the native batches and filter or combine them in Starlark. A fixed batch list in `workshop.recipes` is
also available. Input items can use tags such as `#c:ingots`.

When the planner chooses a workshop, Core reserves its recipe inputs for the run. Any work
produced by a script step draws from that reservation when its needs match. Residents carry
deliveries to the ports, and deliveries to separate blocks can run in parallel. Goods the run
puts into colony storage count toward the booked outputs.

```python
process = """
def recipes(input):
    return recipes_of("minecraft:smelting")

def step(state, input, args):
    if input["_failure"] != None:
        return state, [fail(input["_failure"])]
    if state == None:
        return "loaded", [deliver("inlet", args["recipe"]["inputs"])]
    if state == "loaded":
        return "collected", [await("outlet.count", 1, 600),
                             collect("outlet", args["recipe"]["outputs"])]
    return state, [done]
"""
set("facilities/workshop/definition", {
    "name": "workshop",
    "extent": {"elements": [{"space": {"dimension": "minecraft:overworld"},
               "min": [100, 64, 20], "max": [102, 64, 20]}]},
    "controls": {
        "inlet": {"offset": [0, 0, 0], "part": "folkways_core:item/up"},
        "outlet": {"offset": [2, 0, 0], "part": "folkways_core:item/down"},
    },
    "routines": {
        "process": {"source": process},
    },
    "workshop": {"routine": "process"},
})
```

`await` waits until the reading equals the value, so this example suits a batch of one output item with an empty outlet.
In `step`, use `args["recipe"]` for the selected batch and `args["demand"]` for the
requested item and count. Use `controls(area=...)` to find the machine's ports and signals,
and `mechanics("workshops")` to see the recipes it offers. Supply any fuel the machine needs.

### Workshops that depend on the machine's state

`recipes(input)` sees the facility's readings, so a workshop can offer batches only while its machine can work them.
Return `[]` while a reading says the machine cannot run and the planner leaves the workshop out until it can.
`controls()` lists the readings integrations offer for the blocks in the extent, each with its probe, offset, config
and current value. Add the ones that tell whether the machine can work and what is left to do to `readings`. A
recipe's `scale` lets one run take up to that many batches at once. When a run loads a part the machine works on,
give its actions `on_fail="recover"` and take back what was delivered in `recover`, so a failed run leaves nothing
behind.

```python
process = """
def recipes(input):
    if not input.get("ready"):
        return []
    return [{"id": "workshop:batch", "scale": 16, "ticks": 200,
             "inputs": [{"item": "<input item>", "count": 1}],
             "outputs": [{"item": "<output item>", "count": 1}]}]

def recover(state, failure, input, args):
    return "stopped", [collect("slot"), write("switch", False)]

def step(state, input, args):
    if input["_failure"] != None:
        return state, [fail(input["_failure"])]
    if state == None:
        return "started", [write("switch", True), await("ready", True, 200, on_fail="recover")]
    if state == "started":
        return "loaded", [deliver("slot", args["recipe"]["inputs"]),
                          await("left", 0, 600, on_fail="recover")]
    if state == "loaded":
        return "collected", [collect("slot", args["recipe"]["outputs"], on_fail="recover"),
                             write("switch", False)]
    if state == "stopped":
        return state, [fail("the machine stopped before the batch was done")]
    return state, [done]
"""
```

Here `switch` is the probed control that starts the machine, `slot` the item port it works on, and `ready` and
`left` readings of that port. Probe the control first to learn which value starts it.

### Transport lanes

A facility can carry goods between two of its item ports without a resident, as a conveyor does. Declare a lane
from the inlet to the outlet. The plan can use it for matching requests and sends a resident to collect the goods
when the outlet's item count rises. If the outlet times out, the plan can choose another way to move the goods.

```python
set("facilities/lane/definition", {
    "name": "lane",
    "extent": {"elements": [{"space": {"dimension": "minecraft:overworld"},
               "min": [80, 64, -10], "max": [96, 66, -10]}]},
    "anchor": [80, 65, -10],
    "controls": {
        "inlet": {"offset": [0, 0, 0], "part": "folkways_core:item"},
        "outlet": {"offset": [16, 0, 0], "part": "folkways_core:item"},
    },
    "projections": [{"lane": {
        "from": "inlet", "to": "outlet", "automatic": True,
        "items": ["<item id>"], "latency": 80, "rate": {"ticks": 20, "count": 8},
    }}],
})
```

`latency` is the expected time in ticks for the first group of items. The optional `rate` adds
`ticks` for every subsequent group of `count` items. Omit `items` to accept any item. Both named
controls must be item ports inside the facility extent. With `automatic` omitted or set to `False`,
a resident carries the shipment to the inlet first. Use a dedicated outlet and keep the machine's
intermediate buffers out of colony storage. An outlet registered as storage still counts its
physical contents.

### Weapons

A gun's `aim` part publishes `aim.ballistics` (named after the control): where the gun pivots and how far out its
muzzle is, how its mount turns against the world, the limits of its aim, its cycle, whether it is loaded and, when the
weapon mod has one, its native flight model. A Create Big Cannons big cannon also reports its bore cell by cell
(`bore`, with each Big Cartridge's `power` and whether it is `spent`), its tube, chambers, safe propellant stress, Ram
Rod limits and the native figures of every big cannon munition (`munitions`), so a routine can work out the flight of
a load before loading it. A Superb Warfare emplacement reports its selected shell (`ammunition`), how many it holds
(`magazine`), its range, damage and blast. Define a facility over the weapon, include its cell in the extent and its
entity when it is one, and fire with the optional `@std//ballistics` module or a routine of your own.

`@std//ballistics` lays the gun on `args["target"]`, completes the load through a port and fires. Its `shoot_*`
functions make a whole routine:

```python
SHOOT = """
load("@std//ballistics", "shoot_step", "shoot_path", "shoot_readings", "shoot_check")

def step(state, readings, args):
    return shoot_step(state, readings, args)

def path(readings, args):
    return shoot_path(readings, args)

def readings(readings, args):
    return shoot_readings(readings, args)

def check(args):
    return shoot_check(args)
"""
set("facilities/gun/definition", {
    "name": "gun",
    "extent": {"elements": [{"space": {"dimension": "minecraft:overworld"},
               "min": [130, 64, -30], "max": [130, 64, -30]}]},
    "controls": {
        "aim": {"part": "aim", "offset": [0, 0, 0]},
        "fire": {"part": "fire", "offset": [0, 0, 0]},
        "breech": {"part": "breech", "offset": [0, 0, 0]},
    },
    "routines": {"shoot": {"source": SHOOT, "with": {
        "aim": "aim", "fire": "fire", "breech": "breech",
        "ammunition": [["createbigcannons:solid_shot", 1], ["createbigcannons:powder_charge", 2]],
    }}},
    "readings": {"nearby": {"probe": "folkways_vanilla:hostile_within", "range": 48}},
    "triggers": {
        "auto": {"kind": "condition", "routine": "shoot",
                 "source": "folkways_core:fire_control", "enabled": True, "cooldown": 120},
        "manual": {"kind": "manual", "routine": "shoot"},
    },
    "ui": {"card": [{"target": "shoot", "pick": "entity|pos",
                     "default": True, "icon": "minecraft:target"}],
           "overlay": [{"range": "nearby"}, {"path": "shoot"}]},
})
```

This is a Create Big Cannons big cannon on a Cannon Mount with a quick-firing breech, fired directly with a solid shot
and two Powder Charges; every quick-firing breech is rated for two unless a datapack raises it. For a Superb Warfare
emplacement, copy each part name exactly as `controls()` lists it, since an entity weapon's parts carry its UUID, and
load through its `ammo` port:

```python
gun = "<UUID>"
set("facilities/turret/definition", {
    "name": "turret",
    "extent": {"elements": [{"space": {"dimension": "minecraft:overworld"},
               "min": [130, 64, -30], "max": [130, 64, -30]}, {"entity": gun}]},
    "controls": {"aim": {"part": "aim/" + gun, "offset": [0, 0, 0]},
                 "fire": {"part": "fire/" + gun, "offset": [0, 0, 0]},
                 "ammo": {"part": "ammo/" + gun, "offset": [0, 0, 0]}},
    "routines": {"shoot": {"source": SHOOT, "with": {
        "aim": "aim", "fire": "fire", "ammo": "ammo",
        "ammunition": [["superbwarfare:large_shell_he", 1]]}}},
    "triggers": {"manual": {"kind": "manual", "routine": "shoot"}},
    "ui": {"card": [{"target": "shoot", "pick": "entity|pos", "default": True, "icon": "minecraft:target"}],
           "overlay": [{"path": "shoot"}]},
})
```

The resident sits in the gunner's seat, lays the gun, puts a shell in the gun's container when it has none and fires
once the gun has reloaded. Fire control supplies `target` from the colony's reported contacts, and the manual trigger
takes one as well: `set("facilities/gun/runs/shoot", {"routine": "shoot", "args": {"target": [145, 64, -30]}})`.

Arguments of `shoot_step` (each except `target` falls back to a facility parameter of the same name):

- `target`: an entity UUID, aimed at the centre of its bounding box, or a world `[x, y, z]`.
- `aim` (default `"aim"`) and `fire`: the aim control and the fire pulse; without `fire` the routine lays and loads.
- `breech`, `ammo` or `muzzle`: the port the load goes through. Through `muzzle`, `ram` (default `"ram"`) names the
  Ram Rod pulse.
- `ammunition`: entries in the order they go through the port: `[item, count]`, `[item, count, components]` or
  `{"item", "count", "components"}`. `components` is a data component map such as `{"createbigcannons:power": 4}`.
- `indirect` prefers the high arc, `range` caps the target distance in blocks, `damage` is what fire control expects
  of one shot (default the weapon's, else 20), and `avoid: True` refuses a shot whose predicted flight or blast
  reaches the colony.
- `speed` (blocks/tick), `gravity` (blocks/tick², downward) and `retention` (fraction of velocity kept each tick): a
  measured flight for a weapon without a native one.

The run fails permanently, with the native reason, when the weapon cannot take the load, and fails for a retry when
the target is out of range or no arc reaches it. A step that finds the bore holding a load it did not place fails.
`shoot_path` draws the flight at the gun's current aim to its first block and marks the impact; `shoot_readings`
publishes `<routine>.center` for the range ring and `<routine>.engagement` (`aim`, `model`, `indirect`, `range`,
`damage`, `blast`) for colony fire control. Any routine that publishes an `engagement` reading takes part: fire control
assigns a contact only when that flight, traced through the loaded world, ends at it and keeps clear of colony
entities and blocks. Player-chosen targets skip this prediction. Same-colony entities and player members cannot damage
one another, and explosions follow the weapon mod's native rules.

The pieces, for a routine of your own: `weapon_of(readings, aim="aim")`; `model(weapon, ammunition=None,
measured=None)` gives `{speed, gravity, retention}` of a load (a big cannon's from its projectile and every charge,
another gun's own); `load_problem(weapon, ammunition, port)`; `fed(weapon, ammunition, port)` counts what is in place,
leaving out a spent Big Cartridge; `load_actions(weapon, ammunition, port, name, ram="ram")` returns the deliveries and
Ram Rod strokes that complete the load; `target_point(readings, args)`, `reach(weapon, model)` and
`blast(weapon, ammunition)`. The flight arithmetic runs in Java: `aim(weapon, model, target, indirect=False)` returns
the part's angles or `None`, `flight(weapon, model, angles, target=None)` returns `{points, arrives}` in free flight,
`solve(model, offset)` returns both arcs to a relative point, and `avoids_colony(points, blast, margin=0.35)` checks a
flight against a snapshot of the colony taken for the routine. A routine that picks its own targets automatically
must check them with `avoids_colony`. This one fires with the fewest charges that reach:

```python
FEWEST = """
load("@std//ballistics", "PULSE", "aim", "load_actions", "load_problem", "model", "target_point", "weapon_of")

def step(state, readings, args):
    if readings["_failure"] != None:
        return state, [fail(readings["_failure"])]
    if state != None:
        return state, [done]
    gun = weapon_of(readings)
    point = target_point(readings, args)
    for charges in [1, 2]:
        load = [["createbigcannons:solid_shot", 1], ["createbigcannons:powder_charge", charges]]
        flight = model(gun, load)
        angles = aim(gun, flight, point) if flight and not load_problem(gun, load) else None
        loading = load_actions(gun, load, "breech", "breech") if angles != None else None
        if loading != None:
            return "fired", ([write("aim", angles), await("aim", angles, 200, within = 1)] + loading +
                             [write("fire", PULSE)])
    return state, [fail("no load reaches the target")]
"""
```

For a big cannon, list the load in the order a hand puts it in. Through the quick-firing `breech` the projectile goes
first and then each charge, one item per delivery, the breech ramming between charges. Through the `muzzle` the
charges go first and the projectile last, each pushed into the muzzle and rammed down to the load with Ram Rod
strokes; any big cannon with an open muzzle offers the `muzzle` and `ram` parts, whatever its breech. Request a filled
Big Cartridge with its power, such as `["createbigcannons:big_cartridge", 1, {"createbigcannons:power": 2}]`; the flight
counts that power. After a cartridge shot the spent case stays in the chamber until the next breech load opens the
breech and ejects it, as a player's hand does.

A sliding or screw breech, or a Cannon Loader, has no `breech` part: load such a cannon through its `muzzle`, or
operate the player's loading structure. Find its levers, clutches and gearshifts with `mechanism()`, then `probe()`
each one to learn which opens and closes the breech, extends and retracts the rammer, and assembles the mount. Give the
facility those controls and readings that show each step finished, such as the rammer's `motion` position and
`{"probe": "folkways_core:cbc/assembled"}` and `{"probe": "folkways_core:cbc/charges"}` at the mount, then sequence
them in a routine source and fire with the mount's `fire` part:

```python
LOAD = """
STEPS = [("assemble", False, "assembled", False), ("breech", True, "breech_open", True),
         ("rammer", True, "rammer_out", True), ("rammer", False, "rammer_out", False),
         ("breech", False, "breech_open", False), ("assemble", True, "assembled", True)]

def step(state, readings, args):
    if readings["_failure"] != None:
        return state, [fail(readings["_failure"])]
    if state == None:
        actions = []
        for control, value, reading, shows in STEPS:
            actions += [write(control, value), await(reading, shows, 400)]
        return "loaded", actions + [await("charges", args["charges"], 40)]
    return state, [done]
"""
```

A mounted autocannon takes a filled Autocannon Ammo Container through its `ammo` port and fires while its `fire`
level is above zero. A drop mortar launches a round dropped into its `ammo` port. Neither reports `ballistics`, so
write their routines yourself.

`ui.overlay` draws in the world while the facility is selected in RTS mode. `{"range": name}` draws a ring
centred on the weapon. The name is a numeric parameter, a numeric reading, or a probe that declares its range.
`{"path": "shoot"}` draws the path supplied by the facility's `shoot` routine.

### Moving facilities

A facility can live on a moving structure. Use `controls(structure="<UUID>")` on it: coordinates are in the
structure's own space, and the facility goes wherever the structure goes. A control whose part takes a world position,
such as `destination`, drives the structure there; `folkways_core:move_to` writes the routine's `target` to
`destination` by default, and the run lasts until the structure arrives or fails. Write any parking or brake part the
structure exposes with `folkways_core:write_all`. Probe the controls to learn what each one moves before relying on
it.

```python
found = controls(structure="replace-with-the-structure-uuid")
drive = [c for c in found["controls"] if c["part"] == "destination"][0]
set("facilities/vehicle/definition", {
    "name": "vehicle",
    "extent": found["extent"],
    "anchor": [drive["x"], drive["y"], drive["z"]],
    "controls": {"destination": {"part": "destination", "offset": [0, 0, 0]}},
    "routines": {"go": {"use": "folkways_core:move_to"}},
    "triggers": {"go": {"kind": "manual", "routine": "go"}},
    "ui": {"card": [{"target": "go", "pick": "pos", "default": True, "icon": "minecraft:compass"}]},
})
```

Residents need a walkable way onto the structure to work its controls. When there is none, put the crew aboard
first. A target is disabled while its run lasts.

## Calibrating mechanisms
<!-- guide: calibration -->

The `parts` dict in `controls()` and `mechanism()` summarizes the part IDs in that result. Before writing
a routine for an unfamiliar part, read its full entry with `help('parts/<part id>')` using the discovered ID.

A player-built machine rarely has a control that says what it does: a door may open when a handle turns, and a key
may work whatever the player linked it to. Calibrate the machine before you
write its facility: read how it is put together, run a trial for each candidate control, then write routines that work
the control that did the right thing, in the right direction.

Calibrate the whole machine, not only the part the player named. When it can do several things, such as a walker or
vehicle that drives and carries an arm, a tool or a gun, trial each of them and put every one in the facility: the
drive as a `pick: "pos"` target that sends it, and each tool as its own command. Then let a command use them together.
To work a picked box with a calibrated tool, load `@std//sweep` rather than writing the passes yourself: it orders
the stops from where the tool is and keeps it out of standing blocks. Pass the box exactly as the pick gives it.
A mobile facility repositions automatically when the sweep runs out of reachable cells at its current stand.

If a routine breaks, places or moves blocks, first trial the whole routine with
`probe(routine=<routine>, definition=<definition>, args=<arguments>)` or
`probe(routine=<routine>, facility=<id>, args=<arguments>)`. Use `keep` to specify the player's allowed box when
the arguments do not already give it. Run it in the world only when `routine.ending` is `done` and
`outside.count` is zero. Debug the routine only with trials.

When speaking to the player, report only what the trial showed, such as which control moved a part and how far.
If the trial could not give a result, explain the practical limitation in plain language. Never mention sandboxes,
copies, simulations, or internal trial mechanics in player-facing text.

When the player points at the machine while asking, `get("client/view")["target"]` holds the cell under the
crosshair: start there rather than searching the area.

1. Call `mechanism(at=pos)` on any block of the machine. Use `area=` for an inclusive box of at most 64 per side,
   or `structure=` for a whole moving structure in its own coordinates. `nodes` lists parts with `roles`:
   `control` (levers, buttons, cranks, handles, weapon mounts, pilot seats, and anything with
   Folkways `controls`), `source`, `transmission` (clutches and gearshifts with `powered` and `when_powered`),
   `actuator` (pistons, bearings, pulleys, gantries, doors, weapon mounts, structure cores) and `moving` (contraptions,
   weapons, sub-levels). `edges` link them: `drives` carries rotation with `ratio` and the folded shafts and gears in
   `via`; `engaged: False` marks a disengaged clutch and `driven: False` a network nothing turns now; `powers` is
   redstone reach; `controls` is a command without rotation; `moves` joins an actuator to what it carries. `paths`
   gives each crank or source's `factor` and `rpm_per_forward_turn` at each actuator, and the switching parts it
   depends on in `through`.
2. Pick candidates: the controls whose `edges` or `paths` reach the actuator that moves the part the player means.
3. Call `probe(control={"x": x, "y": y, "z": z, "part": part}, value=value, at=pos)` for each candidate.
   This tries the input as a resident would for `ticks` (default 40) without changing the machine in the world.
   Read `summary` first, then `changes` (piston `offset`, bearing `angle`,
   mount `yaw` and `pitch`, lever `powered`, door `open`, with `peak_speed` for turning parts), `entities`
   (a contraption's `moved` [dx, dy, dz]) and `blocks` (added, removed and altered cells). `input.result` says how the
   write went and `input.done_tick` when the control showed its value. Use `redstone={"x", "y", "z", "on"}` instead of
   `control` to try a redstone input at an empty cell. Give the trial time to finish: a valve handle use takes about
   20 ticks and a hand crank use about 10, so pass `ticks` of about 20 per use plus 20 (at most 100), and read
   `input.done_tick` to see that every use ran. Probe both directions when direction matters, and probe a small count
   first: what one or two uses move tells how many a full opening takes. Print `summary` rather than the whole
   reply, which can exceed the output limit. A script can run
   at most 2 probes, so probe further candidates in later scripts. A probe changes nothing in the world and needs no
   confirmation.
4. Write the definition with routines that write the probed control with the probed value, and say in the reply
   which control you picked and what the probe showed.

A hand crank or valve handle exposes `turn`, a whole number of uses from -64 to 64: positive for plain uses,
negative for sneaking uses that turn the other way. One use of a valve handle turns it `degrees_per_turn`; one use of
a hand crank spins it for about ten ticks. A turn is relative, so a door worked by one keeps no open or closed
reading of its own. A mechanical piston, Rope Pulley or gantry shaft with a single carriage exposes
a read-only `motion` part with `position`, `moving` and `contraption` readings.

For positioning, test each channel value with `probe(..., ticks=40, release_tick=20)`. Start with
the mechanism stopped and enough free travel. Repeat across levels 0–15 or the relevant
lever states. In `position`'s `response` map, each axis names its `channel`, resting `stop` value and
`rates` entries containing `value`, signed `velocity` (blocks per tick), `start_ticks`,
`stop_ticks` and optional signed `overshoot`. Zero velocity marks a dead zone. A staffed post uses
the readings each tick. A resident working at the channel without a post runs a timed start and stop,
checks the result, and corrects within the configured `tolerance`.

In this example, `mechanism` found a control at (10, 64, 5) whose path reaches the actuator that moves the part the
player means. Probe it with each value the part takes (here `1` and `-1`), read which one moved the part the way the
player asked, and write that value into the matching routine. Name routines and card buttons after what the trial
showed, in the player's language, with a word or two each.

```python
pos = {"dimension": "minecraft:overworld", "x": 10, "y": 64, "z": 5}
graph = mechanism(at=pos)
candidates = [n for n in graph["nodes"] if "control" in n["roles"]]
x, y, z = candidates[0]["at"]
part = "<part>"
forward = probe(control={"x": x, "y": y, "z": z, "part": part}, value=1, at=pos)
backward = probe(control={"x": x, "y": y, "z": z, "part": part}, value=-1, at=pos)
result = {"forward": forward["summary"], "backward": backward["summary"]}
```

```python
set("facilities/machine/definition", {
    "name": "machine",
    "extent": {"elements": [{"space": {"dimension": "minecraft:overworld"},
               "min": [10, 62, 5], "max": [10, 66, 7]}]},
    "anchor": [10, 64, 5],
    "controls": {"input": {"part": "<part>", "offset": [0, 0, 0]}},
    "routines": {
        "forward": {"use": "folkways_core:write_all", "with": {"input": 1}},
        "backward": {"use": "folkways_core:write_all", "with": {"input": -1}},
    },
    "triggers": {"forward": {"kind": "manual", "routine": "forward"},
                 "backward": {"kind": "manual", "routine": "backward"}},
    "ui": {"card": [{"button": "forward", "icon": "<item id>"},
                    {"button": "backward", "icon": "<item id>"}]},
})
```

A resident stands at the control and works every use. A valve handle will not turn while another source drives
its network, so a probe that answers `busy` points at a motor or a clutch to check first.

For a weapon, `mechanism` shows its `yaw`, `pitch` and `loaded` state, what it carries and any crank that turns it.
Probe `aim` with `[yaw, pitch]`, or a crank's `turn`, to see which way it moves. A `fire` trial never launches a
projectile or causes an explosion; `suppressed` lists what was prevented. Then follow the weapon pattern in
`guide:facilities`.

For a moving structure, `mechanism(structure=uuid)` shows the parts that drive it and the controls that command them.
A trial of a moving structure includes the structure, what it carries and nearby ground. Probe a `destination` part
with a world position a few blocks ahead and `ticks=100`, then read `structures`: each structure's `moved`
[dx, dy, dz] and `turned` {yaw, pitch, roll} in degrees (yaw positive is clockwise seen from above), with carried
structures listed as `brought_along`; `changes` and `input.done_tick` tell whether it arrived. Probe a parking part
with `True` or `False` to see the structure settle or stand.

Controls on a structure may expose more parts; `controls(structure=uuid)` lists them and a probe returns a part's
reading as `input.reading`:

- `key/<action>`: a pulse presses that key once, powering whatever it is linked to.
- `height` (a height offset in blocks) and `lean` (`[roll, pitch]` in radians): the structure's posture.
- `limb/<n>`, numbered from 1: takes `[fwd, up]` in that limb's own plane. An aim whose length lies in the reading's
  `ring` is taken as it is; a shorter or longer one is moved to the ring's edge. The reading gives the world `x`, `y`
  and `z` where the limb's end actually stands, the same point in the structure's space as `local`, the direction of
  its last link as `axis`, whether the limb is an `arm`, the `ring`, and, while the limb is held, the `aim` it holds.
  Fit a chain to these readings with `@std//ik` to reach a point.

A part mounted on a moving structure is probed the same way: give `structure=` the structure and the part's `at` cell.
The trial shows how the part moves while the structure keeps its starting pose.

A trial includes blocks, block entities and contraptions in the selected box at their original coordinates.
Players, residents and other mobs are excluded. Moving structures start at their current pose and speed,
with attached structures (up to 32 in total) and ground up to 16 blocks around them
(less for dense ground). Keep walks short. If a structure leaves the trial area, it is removed from the trial and
marked `left_area`. Temporary Sable joints, such as a ship's mooring, are excluded, so the ship can move freely in
the trial. Input to a structure's controls affects only the structure being tested. External networks such as rail and
logistics networks are excluded and listed in `notes`. The server runs one trial at a time. Retry after `sandbox_busy`.
