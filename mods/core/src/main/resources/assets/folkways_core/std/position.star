load("@std//ik", "chain", "forward", "solve", "to_local", "to_world")
load("@std//math", "sqrt")

def _axes(args):
    return args.get("order", sorted(args["frame"]["axes"]))

def position_check(args):
    axes = _axes(args)
    names = args["frame"]["axes"]
    if not axes or len(axes) > 3 or sorted(axes) != sorted(names):
        return "position order must list each frame axis once"
    if any([axis not in names for axis in args.get("hold", [])]):
        return "position hold must name frame axes"
    if args.get("tolerance", 0.25) <= 0:
        return "position tolerance must be positive"
    if "pos" not in args and "target" not in args:
        return "position needs a target"
    return None

def position_ready(readings, args):
    if type(args.get("target")) == "string" and "_target_point" not in readings and "pos" not in args:
        return False
    return all([axis in readings and axis + ".maximum" in readings for axis in _axes(args)])

def _chain(args, axes):
    frame = args["frame"]
    joints = []
    for axis in axes:
        direction = frame["axes"][axis]
        length = sqrt(direction[0] * direction[0] + direction[1] * direction[1] + direction[2] * direction[2])
        joints.append({"kind": "prismatic", "direction": direction, "per_unit": length, "zero": 0})
    return chain(joints, frame["origin"])

def _world(readings, point):
    return to_world(readings["_frame"], point) if "_frame" in readings else point

def position_readings(readings, args):
    axes = _axes(args)
    if not all([axis in readings and axis + ".maximum" in readings for axis in axes]):
        return {}
    geometry = _chain(args, axes)
    corners = [[]]
    for axis in axes:
        corners = [corner + [value] for corner in corners for value in [0, readings[axis + ".maximum"]]]
    points = [_world(readings, forward(geometry, corner)) for corner in corners]
    return {
        "current": _world(readings, forward(geometry, [readings[axis] for axis in axes])),
        "bounds": [min([p[i] for p in points]) for i in range(3)] + [max([p[i] for p in points]) for i in range(3)],
    }

def position_goals(readings, args, target):
    axes = _axes(args)
    point = to_local(readings["_frame"], target) if "_frame" in readings else target
    result = solve(_chain(args, axes), point, start = [readings[axis] for axis in axes], tolerance = 0.00001)
    if not result["reached"]:
        return None
    goals = {}
    for axis, goal in zip(axes, result["controls"]):
        if axis in args.get("hold", []):
            goals[axis] = readings[axis]
            continue
        limit = readings[axis + ".maximum"]
        if goal < -0.00001 or goal > limit + 0.00001:
            return None
        goals[axis] = max(0, min(limit, goal))
    return goals

def _abs(value):
    return max(value, -value)

def _rate(profile, distance):
    best = None
    for rate in profile["rates"]:
        velocity = rate["velocity"]
        if distance * velocity <= 0:
            continue
        start = int(rate["start_ticks"])
        duration = min(24000, start + max(1, int((distance - rate.get("overshoot", 0)) / velocity + 0.5)))
        error = _abs(distance - rate.get("overshoot", 0) - velocity * (duration - start))
        if best == None or error < best[0]:
            best = (error, duration, rate["value"], int(rate["stop_ticks"]))
    return best

def position_step(state, readings, args):
    failure = readings.get("_failure")
    if failure != None and failure != "folkways.facility.script.script_interrupted":
        return state, [fail(failure)]
    axes = [axis for axis in _axes(args) if axis not in args.get("hold", [])]
    tolerance = args.get("tolerance", 0.25)
    if state == None:
        target = args.get("pos", readings.get("_target_point", args.get("target")))
        goals = position_goals(readings, args, target)
        if goals == None:
            return state, [fail_permanent("folkways.refusal.facility.position_travel")]
        waypoints = []
        if "clearance" in args:
            clearance = position_goals(readings, args, args["clearance"])
            if clearance == None:
                return state, [fail_permanent("folkways.refusal.facility.position_travel")]
            waypoints.append(clearance)
        waypoints.append(goals)
        state = ([(axis, goal[axis]) for goal in waypoints for axis in axes], 0, 0, 0)
    legs, index, attempts, settling = state
    for i in range(index, len(legs)):
        axis, goal = legs[i]
        distance = goal - readings[axis]
        if readings.get(axis + ".moving", False):
            if settling >= 20:
                return state, [fail_permanent("folkways.refusal.facility.position_unreached")]
            return (legs, i, attempts, settling + 1), []
        if _abs(distance) <= tolerance:
            attempts = 0
            continue
        profile = args["response"][axis]
        rate = _rate(profile, distance)
        if rate == None or attempts >= 4:
            return state, [fail_permanent("folkways.refusal.facility.position_unreached")]
        _, duration, drive, settle = rate
        channel = profile["channel"]
        steps = [
            {"atTick": 0, "control": channel, "value": drive},
            {"atTick": duration, "control": channel, "value": profile["stop"]},
        ]
        if settle > 0 and duration + settle <= 24000:
            steps.append({"atTick": duration + settle, "control": channel, "value": profile["stop"]})
        return (legs, i, attempts + 1, 0), [run("folkways_core:write_sequence", {"steps": steps, "on_interrupt": {channel: profile["stop"]}})]
    return state, [done]
