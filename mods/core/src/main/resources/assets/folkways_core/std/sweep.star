"""Works the cells of a picked box with a tool carried at the end of a calibrated chain.

Use `sweep(state, readings, args)` as a routine step or call `sweep_step` to work one batch at a time.
When `sweep_step` returns no actions, use `worked` and `left` for coverage and `reason` for any blockage.
Mobile facilities reposition automatically between batches. In `args`:

  target   the box `[x0, y0, z0, x1, y1, z1]`, as a box pick gives it
  fits     straight chains take `[controls, [x, y, z]]` samples spanning every control. For a turning chain use
           one list of `[value, [x, y, z]]` samples per joint from a shared start, wrapping turning samples in
           `{"revolute": samples}`
  local    fit in the carrying structure's space using the control's `local` reading and `readings["_frame"]`
  reach    `[rx, ry, rz]`, the working footprint's half-widths along the world axes
  offset   world-axis displacement `[dx, dy, dz]` from the end to the working point (default [0, 0, 0])
  coverage optional half-widths for covering cell centers (defaults to reach); the tool also covers its own cell
  control  the signal that takes the controls (default "arm"); its reading gives the end's `x`, `y` and `z`
  ring     optional `[min, max]` length of the control vector that the control takes as it is; by default the
           reading's `ring`
  dwell    working stop hold in ticks (default 40)
  at_stop  optional control/value/atTick steps, timed from the start of the sequence after the stop is checked
  relocate  set to False to keep the facility at its current stand
  skip     optional cells already worked, as `"x,y,z"` keys (a `worked` list): stops that work only these are left out

A plane is a box one cell thick.
Each batch picks a shortest open route from the measured end, preferring higher stops then nearer stops on ties.
It reads `readings["_target"]` for the box border and standing cells, in world coordinates.
An observed `_target_approach` can provide room to enter from beyond the border.
The footprint can touch cells at the chosen stop or withdraw from contact it already has. If no remaining stop can be reached,
`sweep` fails and `sweep_step` leaves the reason in its state as `reason`.
"""

load("@std//ik", "chain", "fit_prismatic", "fit_revolute", "fit_straight")
load("@std//math", "dist", "floor")
load("@std//_sweep_path", _covered = "covered", _key = "key", _past = "past", _plan = "plan",
     _predicted = "predicted", _first_leg = "first_leg", _relocate = "relocate", _held_aim = "held_aim", _working_point = "working_point")

_MAX_CELLS = 512
_EPSILON = 0.000001

def _hold(control, aim, ticks):
    return write_sequence([{"atTick": 0, "control": control, "value": aim},
                           {"atTick": ticks, "control": control, "value": aim}])

def _is_sample(item):
    return (type(item) == "list" and len(item) == 2 and type(item[0]) == "list" and type(item[1]) == "list"
            and len(item[1]) == 3 and all([type(v) in ("int", "float") for v in item[0] + item[1]]))

def _samples(fits):
    # Find the samples in nested lists and dictionaries.
    found = []
    stack = [fits]
    for _ in range(4096):
        if not stack:
            break
        item = stack.pop()
        if _is_sample(item):
            found.append(item)
        elif type(item) == "dict":
            stack += list(item.values())
        elif type(item) == "list":
            stack += item
    return found

def _chain(args):
    fits = args["fits"]
    samples = _samples(fits)
    if samples and not (type(fits) == "list" and any([type(f) == "dict" and "revolute" in f for f in fits])):
        return fit_straight(samples), False
    joints = []
    revolute = False
    tip = None
    for fit in fits:
        if type(fit) == "dict" and "revolute" in fit:
            samples = fit["revolute"]
            joint = fit_revolute(samples)
            revolute = True
        else:
            samples = fit["prismatic"] if type(fit) == "dict" else fit
            joint = fit_prismatic(samples)
        # Joint values are relative to the shared calibration pose.
        joint["zero"] = samples[0][0]
        if tip == None:
            tip = samples[0][1]
        joints.append(joint)
    return chain(joints, tip), revolute

def _cells(box):
    return [[x, y, z] for x in range(box[0], box[3] + 1) for y in range(box[1], box[4] + 1)
            for z in range(box[2], box[5] + 1)]

def _at_stop(control, aim, steps):
    # Begin at tick zero even when the caller's first write is later in the stop.
    return write_sequence([{"atTick": 0, "control": control, "value": aim}] +
                          sorted(steps, key = _tick))

def _tick(step):
    return step["atTick"]

def _left(args, worked):
    done = {k: True for k in args.get("skip", []) + list(worked)}
    return [cell for cell in _cells([int(v) for v in args["target"]]) if _key(cell) not in done]

def sweep_step(state, readings, args):
    control = args.get("control", "arm")
    end = readings.get(control)
    if "ring" not in args and type(end) == "dict" and "ring" in end:
        # Leave a margin inside the reported ring.
        args = dict(args, ring = [end["ring"][0] + 0.04, end["ring"][1] - 0.03])
    if readings["_failure"] != None:
        return state, [fail(readings["_failure"])]
    if readings.get("_target") == None:
        return state, [fail_permanent("sweep needs readings._target for a loaded box of at most %d cells" % _MAX_CELLS)]
    if args.get("local") and readings.get("_frame") == None:
        return state, [fail_permanent("local fits need a facility on a moving structure")]
    if state != None and state["finished"]:
        return state, []
    local = args.get("local")
    if type(end) != "dict" or any([k not in end for k in ["x", "y", "z"]]) or local and "local" not in end:
        return state, [fail("%s gives no position" % control)]
    world = [end["x"], end["y"], end["z"]]
    stands = end["local"] if local else world
    point = _working_point(args, world)
    worked = [] if state == None else state["worked"]
    if state != None and "aimed" in state:
        if dist(stands, state["aimed"]) > 0.5:
            return state, [fail_permanent("%s is not where its calibration puts it; calibrate it again" % control)]
        if state["working"]:
            if _past(args, point) > _EPSILON:
                return state, [fail_permanent("the tool's footprint is outside the box at %s; calibrate again" %
                                              _key([int(floor(v)) for v in point]))]
            steps = args.get("at_stop", [])
            if steps and not state.get("acted"):
                return dict(state, acted = True), [_at_stop(control, state["aim"], steps)]
            # Only credit the cells assigned to this stop. A small pose error can brush the cells
            # beside it and make them overlap the footprint without actually working them.
            assigned = _covered(args, state.get("stop", point))
            worked = worked + [k for k in _covered(args, point) if k in assigned and k not in worked]
    left = _left(args, worked)
    finished = {"worked": worked, "left": left, "finished": True}
    stands_tried = [] if state == None else state.get("stands_tried", [])
    lift = 0 if state == None else state.get("lift", 0)
    if not left:
        return finished, []
    arm, revolute = _chain(args)
    if arm == None:
        return dict(finished, reason = "the samples could not fit the chain"), []
    start_aim = end.get("aim", state.get("aim") if state != None else None)
    if revolute and start_aim == None:
        start_aim = _held_aim(arm, args, readings, point)
        if start_aim == None:
            return dict(finished, reason = "the turning chain cannot locate its current pose"), []
    old = _predicted(arm, args, readings, start_aim) if start_aim != None else point
    bias = [point[i] - old[i] for i in range(3)]
    stop = _plan(args, readings, arm, point, start_aim, bias,
                 {k: True for k in args.get("skip", []) + worked})
    leg = None if stop == None else _first_leg(stop, args, readings, arm, revolute, point, start_aim, bias)
    if leg == None:
        drive = readings.get("_sweep_drive")
        if args.get("relocate", True) and local and drive != None and start_aim != None:
            destination, raised = _relocate(args, readings, arm, revolute, point, start_aim, bias,
                                            args.get("skip", []) + worked, left, stands_tried, lift)
            if destination != None:
                return {"worked": worked, "finished": False, "aim": start_aim,
                        "stands_tried": stands_tried + [destination]}, [
                    run("folkways_core:move_to", {"drive": drive["control"], "target": destination})]
            if raised != None:
                aimed, aim, _ = raised
                return {"worked": worked, "finished": False, "aimed": aimed, "aim": aim, "working": False,
                        "stands_tried": stands_tried, "lift": lift + 1}, [_hold(control, aim, 30)]
        reason = "no remaining cell is within reach through open space" if stop == None else "the tool cannot follow the open route"
        return dict(finished, reason = reason), []
    aimed, aim, working = leg
    dwell = args.get("dwell", 40) if working else 30
    return {"worked": worked, "aimed": aimed, "aim": aim, "working": working, "finished": False,
            "stands_tried": stands_tried, "stop": stop["at"]}, [
        _hold(control, aim, dwell)]

def sweep(state, readings, args):
    state, actions = sweep_step(state, readings, args)
    if actions:
        return state, actions
    if state.get("reason"):
        return state, [fail_permanent(state["reason"])]
    return state, [done]
