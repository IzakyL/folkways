load("@std//ik", "forward", "solve", "to_local", "to_world")
load("@std//math", "ceil", "dist", "sqrt")

_EPSILON = 0.000001

def _aim(arm, point, args, start = None):
    found = solve(arm, point, start, tolerance = 0.01)
    c = found["controls"]
    ring = args.get("ring")
    squared = 0.0
    if ring:
        for value in c:
            squared += value * value
    radius = sqrt(squared)
    if found["error"] > 0.1 or ring and (radius < ring[0] or radius > ring[1]):
        return None
    return c

def _clear_of_ring(a, b, ring):
    # Check the segment against the ring hole.
    if not ring or len(a) != 2:
        return True
    dx = b[0] - a[0]
    dy = b[1] - a[1]
    span = dx * dx + dy * dy
    t = 0.0 if span < 1e-9 else max(0.0, min(1.0, -(a[0] * dx + a[1] * dy) / span))
    # A measured pose inside the planning margin can still move out of it.
    inner = min(ring[0], dist([a[0], a[1], 0], [0, 0, 0]))
    return dist([a[0] + t * dx, a[1] + t * dy, 0], [0, 0, 0]) + _EPSILON >= inner

def _space(args, readings, point):
    offset = args.get("offset", [0, 0, 0])
    end = [point[i] - offset[i] for i in range(3)]
    return to_local(readings["_frame"], end) if args.get("local") else end

def _working_point(args, end):
    offset = args.get("offset", [0, 0, 0])
    return [end[i] + offset[i] for i in range(3)]

def _predicted(arm, args, readings, aim):
    end = forward(arm, aim)
    return _working_point(args, to_world(readings["_frame"], end) if args.get("local") else end)

def _key(cell):
    return "%d,%d,%d" % (cell[0], cell[1], cell[2])

def _route_aim(arm, args, readings, point, bias, seed):
    goal = _space(args, readings, [point[i] - bias[i] for i in range(3)])
    return _aim(arm, goal, args, seed)

def _plan(args, readings, arm, start, start_aim, bias, done):
    box = [int(v) for v in args["target"]]
    snapshot = readings.get("_target_approach", readings["_target"])
    observed = _sweep.snapshot(snapshot["around"], snapshot["blocked"])
    half = args["reach"]
    frame = readings["_frame"] if args.get("local") else None
    path = _sweep.route(observed, box, half, args.get("coverage", half), done, start, start_aim, arm,
                        args.get("ring"), frame, args.get("offset", [0, 0, 0]), bias)
    if path == None:
        return None
    stop, first, aim, actual = path
    return {"at": stop, "first": (first, aim), "own": actual, "observed": observed}

def _past(args, point):
    box = [int(v) for v in args["target"]]
    half = args["reach"]
    return max([0.0] + [max(box[i] - (point[i] - half[i]), point[i] + half[i] - (box[i + 3] + 1)) for i in range(3)])

def _covered(args, point):
    box = [int(v) for v in args["target"]]
    half = args["reach"]
    return _sweep.covered(box, half, args.get("coverage", half), point)

def held_aim(arm, args, readings, point):
    return _aim(arm, _space(args, readings, point), args)

def _curve_clear(arm, args, readings, start, end, bias, observed, own):
    # Bound how far the tip can move between forward samples. The radius bounds each suffix of the chain.
    zero = [0, 0, 0]
    radius = dist(arm["tip"], zero)
    speed = 0.0
    for i in reversed(range(len(start))):
        joint = arm["joints"][i]
        a = (start[i] - joint["zero"]) * joint["per_unit"]
        b = (end[i] - joint["zero"]) * joint["per_unit"]
        change = max(b - a, a - b)
        if joint["kind"] == "revolute":
            pivot = dist(joint["pivot"], zero)
            speed += change * (radius + pivot)
            radius += 2 * pivot
        else:
            speed += change
            radius += max(a, -a, b, -b)
    samples = max(1, min(64, int(ceil(speed / 0.04))))
    half = [v + speed / (2 * samples) for v in args["reach"]]
    for k in range(samples):
        t = (k + 0.5) / samples
        aim = [start[i] + t * (end[i] - start[i]) for i in range(len(start))]
        predicted = _predicted(arm, args, readings, aim)
        at = [predicted[i] + bias[i] for i in range(3)]
        if not observed.clear(half, at, at, own, args["reach"], False):
            return False
    return True

def first_leg(stop, args, readings, arm, revolute, point, start_aim, bias):
    at, aim = stop["first"]
    working = at == stop["at"]
    # Short legs limit the arc of a turning joint between holds.
    length = dist(point, at)
    limit = 0.25 if revolute else 0.98
    if working and not revolute:
        # Near a box face, take a shorter final approach so a new measurement can correct the aim.
        box = args["target"]
        half = args["reach"]
        if any([min(at[i] - half[i] - box[i], box[i + 3] + 1 - at[i] - half[i]) < 0.25 for i in range(3)]):
            limit = 0.45
    if length > limit:
        at = [point[i] + (at[i] - point[i]) * limit / length for i in range(3)]
        aim = _route_aim(arm, args, readings, at, bias, start_aim)
        if aim == None or start_aim != None and not _clear_of_ring(start_aim, aim, args.get("ring")):
            return None
        working = False
    predicted = _predicted(arm, args, readings, aim)
    actual = [predicted[i] + bias[i] for i in range(3)]
    if dist(point, actual) > 1 + _EPSILON:
        return None
    observed = stop["observed"]
    if not observed.clear(args["reach"], point, actual, stop["own"], args["reach"], True):
        return None
    if revolute and not _curve_clear(arm, args, readings, start_aim, aim, bias, observed, stop["own"]):
        return None
    aimed = _space(args, readings, at)
    return aimed, aim, working

covered = _covered
key = _key
past = _past
plan = _plan
predicted = _predicted
working_point = _working_point

def relocate(args, readings, arm, revolute, point, held, bias, worked, left, tried, lift):
    frame = readings["_frame"]
    drive = readings["_sweep_drive"]["position"]
    half = args["reach"]
    snapshot = readings.get("_target_approach", readings["_target"])
    observed = _sweep.snapshot(snapshot["around"], snapshot["blocked"])
    done = {key: True for key in worked}
    box = args["target"]
    # There is no working stop during transit. The allowance is outside the snapshot.
    outside = [point[0], snapshot["around"][4] + 3, point[2]]
    # Allow for the carrier settling onto its feet as it arrives; a low working pose
    # can clear the cut at rest but brush the floor on the way to the next stand.
    above_floor = point[1] - half[1] >= box[1] + 0.75
    # Keep the carrier's heading on short legs so a narrow tool stays lined up with its cut.
    forward = frame["z"]
    side = frame["x"]
    # Favor the end of the cut with remaining work, even after passing the middle of the box.
    remaining = 0.0
    for cell in left:
        remaining += (cell[0] + 0.5 - point[0]) * forward[0] + (cell[2] + 0.5 - point[2]) * forward[2]
    ahead = 1 if remaining >= 0 else -1
    for axis, distance in [(forward, ahead * n) for n in (3, 5, 7)] + [
        (forward, -ahead * n) for n in (3, 5, 7)] + [(side, n) for n in (2, -2, 3, -3, 5, -5, 7, -7)]:
        shift = [axis[0] * distance, 0, axis[2] * distance]
        # Short sideways approaches can report arrival before reaching the new lane. Allow
        # room for that early stop, and check the full commanded transit for clearance.
        travel = 1.0 + 0.60 / max(distance, -distance) if axis == side else 1.0
        destination = [drive[i] + shift[i] * travel for i in range(3)]
        if any([dist(destination, previous) < 1.5 for previous in tried]):
            continue
        arrival = [point[i] + shift[i] for i in range(3)]
        end = [point[i] + shift[i] * travel for i in range(3)]
        if not above_floor or not observed.clear(half, point, end, outside, [0, 0, 0], False):
            continue
        shifted = dict(readings, _frame = dict(frame, origin = [frame["origin"][i] + shift[i] for i in range(3)]))
        stop = _plan(args, shifted, arm, arrival, held, bias, done)
        if stop != None and first_leg(stop, args, shifted, arm, revolute, arrival, held, bias) != None:
            return destination, None
    # Lift out of the cut. A wall at the edge of the tool can require pulling back before it can rise.
    if lift < 16 and point[1] < box[4] + 1 + half[1] + 0.75:
        step = 0.23 if revolute else 0.48
        for rise, back in [(step, 0), (step / 2, -ahead * step / 2), (step / 2, ahead * step / 2),
                           (0, -ahead * step), (0, ahead * step)]:
            at = [point[0] + forward[0] * back, point[1] + rise, point[2] + forward[2] * back]
            aim = _route_aim(arm, args, readings, at, bias, held)
            if aim == None or not _clear_of_ring(held, aim, args.get("ring")):
                continue
            stop = {"first": (at, aim), "at": outside, "own": outside, "observed": observed}
            leg = first_leg(stop, args, readings, arm, revolute, point, held, bias)
            if leg != None:
                return None, leg
    return None, None
