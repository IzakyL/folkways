"""Lays a gun from its aim part's `ballistics` reading, loads it through a port and fires.

Define a routine's hooks with the `shoot_*` functions, or build your own from the pieces below:

    load("@std//ballistics", "shoot_step", "shoot_path", "shoot_readings", "shoot_check")

    def step(state, readings, args):
        return shoot_step(state, readings, args)

    def path(readings, args):
        return shoot_path(readings, args)

    def readings(readings, args):
        return shoot_readings(readings, args)

    def check(args):
        return shoot_check(args)

In `args` (a facility parameter of the same name stands in for a missing argument, except `target`):

  target      an entity UUID or a world [x, y, z]
  aim         the aim control (default "aim"); its `<aim>.ballistics` reading describes the gun
  fire        the fire pulse; without it the routine lays and loads only
  breech, ammo or muzzle   the port the ammunition goes through; through the muzzle, `ram` names the
              Ram Rod pulse (default "ram")
  ammunition  entries in the order they go through the port: [item, count], [item, count, components]
              or {"item", "count", "components"}; components is a dict such as
              {"createbigcannons:power": 4}
  indirect    prefer the high arc (default the weapon's)
  range       the farthest target in blocks (default the weapon's, or how far the flight model carries)
  speed, gravity, retention   a measured flight for a weapon without a native one
  damage      what fire control expects of one shot (default the weapon's, else 20)
  avoid       refuse to fire when the predicted flight or blast reaches the colony
"""

load("@std//_ballistics", native_aim = "aim", native_avoids = "avoids_colony", native_flight = "flight",
     native_solve = "solve")
load("@std//math", "sqrt")

aim = native_aim
avoids_colony = native_avoids
flight = native_flight
solve = native_solve

PULSE = {"pulse": True}
_PORTS = ["breech", "ammo", "muzzle"]

def _option(readings, args, key, default = None):
    if key in args:
        return args[key]
    if readings != None and key in readings:
        return readings[key]
    return default

def entries(ammunition):
    """The ammunition as `{"item", "count", "components"}` dicts."""
    found = []
    for entry in ammunition or []:
        if type(entry) == "dict":
            item, count, components = entry["item"], entry.get("count", 1), entry.get("components")
        else:
            item, count = entry[0], entry[1]
            components = entry[2] if len(entry) > 2 else None
        found.append({"item": item, "count": int(count), "components": components or {}})
    return found

def units(ammunition):
    """One entry per item, in loading order."""
    return [{"item": e["item"], "components": e["components"]} for e in entries(ammunition) for _ in range(e["count"])]

def weapon_of(readings, aim_name = "aim"):
    """The `ballistics` reading of an aim control, or None when it reports none."""
    return readings.get(aim_name + ".ballistics")

def _level(unit, munition):
    return unit["components"].get(munition.get("component", ""), 0)

def _speed(weapon, units_):
    munitions = weapon.get("munitions", {})
    speed = 0.0
    projectile = None
    for unit in units_:
        munition = munitions.get(unit["item"])
        if munition == None:
            return None, None
        if munition["kind"] == "projectile":
            projectile = munition
            speed += munition["power"]
        elif munition["kind"] == "cartridge":
            speed += munition["power"] * _level(unit, munition)
        else:
            speed += munition["power"]
    return speed, projectile

def model(weapon, ammunition = None, measured = None):
    """The flight model of a load: for a big cannon from its projectile and every charge, for other guns the gun's own.

    Falls back to `measured` (`{"speed", "gravity", "retention"}`) when the weapon offers none.
    """
    found = None
    if weapon != None and weapon["kind"] == "big_cannon" and ammunition:
        speed, projectile = _speed(weapon, units(ammunition))
        if projectile != None and "gravity" in projectile and speed > 0:
            found = {"speed": speed, "gravity": projectile["gravity"], "retention": projectile["retention"]}
    elif weapon != None:
        found = weapon.get("flight")
    return found or measured

def measured(readings, args):
    speed = _option(readings, args, "speed")
    gravity = _option(readings, args, "gravity")
    if speed == None or gravity == None:
        return None
    return {"speed": speed, "gravity": gravity, "retention": _option(readings, args, "retention", 1.0)}

def blast(weapon, ammunition = None):
    """The blast radius automatic fire keeps clear of the colony; negative when the round's payload is unknown."""
    if weapon["kind"] == "big_cannon" and ammunition:
        for unit in units(ammunition):
            munition = weapon["munitions"].get(unit["item"])
            if munition != None and munition["kind"] == "projectile":
                return munition["blast"]
    return weapon.get("blast", 0)

def load_problem(weapon, ammunition, port = "breech"):
    """Why the weapon cannot take this load through the port, by the native rules; None when it can."""
    if weapon == None:
        return "the aim control reports no ballistics"
    if weapon["kind"] != "big_cannon":
        wanted = weapon.get("ammunition")
        for entry in entries(ammunition):
            if entry["item"] != wanted:
                return "the gun fires %s, its selected shell; %s does not fit" % (wanted, entry["item"])
        return None
    loading = units(ammunition)
    bore = loading if port == "muzzle" else list(reversed(loading))
    kinds = []
    for unit in bore:
        munition = weapon["munitions"].get(unit["item"])
        if munition == None:
            return "%s is not a big cannon munition" % unit["item"]
        kinds.append(munition)
    count = len(bore)
    if count < 2 or kinds[-1]["kind"] != "projectile" or any([k["kind"] == "projectile" for k in kinds[:-1]]):
        return ("load one projectile with its propellant: through the breech the projectile goes first, " +
                "through the muzzle last")
    if kinds[-1]["size"] != 1:
        return "only single-block projectiles are loaded by hand"
    if count > weapon["tube"]:
        return "the tube has %d cells; this load needs %d" % (weapon["tube"], count)
    charges = count - 1
    if charges > weapon["chambers"]:
        return ("propellant would rest in cell %d, which is not a chamber; CBC can burst the barrel there" %
                (weapon["chambers"] + 1))
    stress = 0.0
    for i in range(charges):
        munition = kinds[i]
        if munition["kind"] == "cartridge":
            if i != 0:
                return "a big cartridge must sit nearest the breech and alone among cartridges"
            power = _level(bore[i], munition)
            if power <= 0:
                return "the big cartridge holds no powder; give it components {\"%s\": power}" % munition["component"]
            stress += munition["stress"] * power
        else:
            stress += munition["stress"]
    if stress > weapon["max_stress"]:
        return ("propellant stress %s exceeds the cannon's safe limit of %d (getMaxSafeCharges: material and " +
                "breech strength); CBC can burst the cannon") % (stress, weapon["max_stress"])
    if port == "breech" and charges >= 2 and charges > weapon["ram_strength"]:
        return ("a ram rod pushes at most %d munitions (ramRodStrength), so a hand load stops at %d blocks" %
                (weapon["ram_strength"], weapon["ram_strength"] + 1))
    if port == "muzzle" and weapon["tube"] - 1 > weapon["ram_reach"]:
        return ("a Ram Rod reaches %d cells into the bore (ramRodReach); this %d-cell tube cannot be loaded " +
                "from the muzzle") % (weapon["ram_reach"], weapon["tube"])
    speed, projectile = _speed(weapon, loading)
    if weapon["squib"] and projectile["squib"] and speed != None:
        travel = weapon["tube"] - charges - 1
        least = weapon["stuck_velocity"]
        if least < 0 or travel >= 1 and least > speed / travel:
            return "CBC ammunition can lodge in the barrel: insufficient charge power for this bore length"
    return None

def _same(unit, cell, munitions):
    if unit["item"] != cell["item"]:
        return False
    munition = munitions.get(unit["item"])
    if munition != None and munition["kind"] == "cartridge":
        return _level(unit, munition) == cell.get("power", 0)
    return True

def _live(weapon):
    return [cell for cell in weapon.get("bore", []) if not cell.get("spent", False)]

def fed(weapon, ammunition, port = "breech"):
    """How many items of the loading order are in place already, or None when the bore holds another load.

    A spent big cartridge does not count: opening the breech to load ejects it, as a player's hand does.
    """
    loading = units(ammunition)
    if weapon["kind"] != "big_cannon":
        return len(loading) if weapon.get("loaded") or weapon.get("magazine", 0) > 0 else 0
    live = _live(weapon)
    for placed in range(len(loading), -1, -1):
        expected = loading[:placed] if port == "muzzle" else list(reversed(loading[:placed]))
        if len(expected) == len(live) and all([_same(expected[i], live[i], weapon["munitions"])
                                               for i in range(len(live))]):
            return placed
    return None

def _item(unit, count = 1):
    item = {"item": unit["item"], "count": count}
    if unit["components"]:
        item["components"] = unit["components"]
    return item

def load_actions(weapon, ammunition, port, name, ram = "ram"):
    """The deliveries (and Ram Rod strokes) that complete the load from what is in place.

    A breech or muzzle takes one item per delivery. Each munition pushed into the muzzle is rammed down to the column.
    """
    placed = fed(weapon, ammunition, port)
    if placed == None:
        return None
    if weapon["kind"] != "big_cannon":
        rest = entries(ammunition)
        skip = placed
        actions = []
        for entry in rest:
            left = entry["count"] - min(skip, entry["count"])
            skip -= entry["count"] - left
            if left > 0:
                actions.append(deliver(name, [_item(entry, left)]))
        return actions
    actions = []
    if port == "muzzle":
        live = _live(weapon)
        if live and live[-1]["cell"] > len(live) - 1:
            actions += [write(ram, PULSE)] * (live[-1]["cell"] - (len(live) - 1))
    for index, unit in enumerate(units(ammunition)[placed:]):
        actions.append(deliver(name, [_item(unit)]))
        if port == "muzzle":
            actions += [write(ram, PULSE)] * (weapon["tube"] - 1 - (placed + index))
    return actions

def target_point(readings, args):
    """The routine's target as a world point: an entity's centre, or the given [x, y, z]."""
    target = args.get("target")
    if type(target) == "string":
        return readings.get("_target_center", readings.get("_target_point"))
    if type(target) in ("list", "tuple") and len(target) == 3:
        return [float(v) for v in target]
    return None

def reach(weapon, flight_model, readings = None, args = {}):
    """The farthest target: the given range, the weapon's own, or how far the flight model carries."""
    given = _option(readings, args, "range")
    if given != None:
        return given
    if "range" in weapon:
        return weapon["range"]
    ticks = 1200
    if flight_model["gravity"] > 0:
        ticks = min(1200, max(1, 2 * flight_model["speed"] / flight_model["gravity"]))
    return flight_model["speed"] * ticks

def _distance(a, b):
    return sqrt((a[0] - b[0]) * (a[0] - b[0]) + (a[1] - b[1]) * (a[1] - b[1]) + (a[2] - b[2]) * (a[2] - b[2]))

def _port(args):
    for port in _PORTS:
        if port in args:
            return port, args[port]
    return None, None

def shoot_check(args):
    """Refuses arguments `shoot_step` cannot use."""
    if len([port for port in _PORTS if port in args]) > 1:
        return "breech, ammo and muzzle are mutually exclusive"
    ammunition = args.get("ammunition")
    if ammunition != None:
        if type(ammunition) not in ("list", "tuple") or not ammunition:
            return "ammunition must be an ordered list of [item, count] entries"
        for entry in entries(ammunition):
            if type(entry["item"]) != "string" or entry["count"] < 1 or type(entry["components"]) != "dict":
                return "ammunition entries need an item, a positive count and optional components"
    if "target" not in args:
        return "shoot needs a target: an entity UUID or [x, y, z]"
    return None

def shoot_step(state, readings, args):
    """Lays the gun on `target`, completes the load through the port and fires."""
    if readings["_failure"] != None:
        return state, [fail(readings["_failure"])]
    aim_name = args.get("aim", "aim")
    weapon = weapon_of(readings, aim_name)
    if weapon == None:
        return state, [fail("%s reports no ballistics: it must be an assembled big cannon or a supported gun" % aim_name)]
    ammunition = _option(readings, args, "ammunition")
    port, name = _port(args)
    if state == None:
        problem = load_problem(weapon, ammunition, port) if ammunition else None
        if problem != None:
            return state, [fail_permanent("the weapon refuses this load: " + problem)]
        point = target_point(readings, args)
        if point == None:
            return state, [fail("the target is unavailable")]
        flight_model = model(weapon, ammunition, measured(readings, args))
        if flight_model == None:
            return state, [fail_permanent("the weapon offers no flight model; set measured speed, gravity and retention")]
        if _distance(point, weapon["pivot"]) > reach(weapon, flight_model, readings, args):
            return state, [fail("the target is outside the weapon's range")]
        angles = aim(weapon, flight_model, point, _option(readings, args, "indirect", weapon.get("indirect", False)))
        if angles == None:
            return state, [fail("no firing solution reaches the target")]
        if _option(readings, args, "avoid", False):
            shot = flight(weapon, flight_model, angles, point)
            if not avoids_colony(shot["points"], blast(weapon, ammunition)):
                return state, [fail("the shot would reach the colony")]
        return "laid", [write(aim_name, angles), await(aim_name, angles, 200, within = 1)]
    if state == "laid":
        if ammunition and name != None:
            actions = load_actions(weapon, ammunition, port, name, args.get("ram", "ram"))
            if actions == None:
                return state, [fail("the bore holds a load this routine did not place; clear it first")]
            if actions:
                return "loaded", actions
        state = "loaded"
    if state == "loaded" and args.get("fire") != None:
        return "fired", [write(args["fire"], PULSE)]
    return state, [done]

def _ready_model(readings, args):
    aim_name = args.get("aim", "aim")
    weapon = weapon_of(readings, aim_name)
    if weapon == None:
        return aim_name, None, None, None
    ammunition = _option(readings, args, "ammunition")
    return aim_name, weapon, ammunition, model(weapon, ammunition, measured(readings, args))

def shoot_path(readings, args):
    """The flight at the gun's current aim, to its first strike."""
    aim_name, weapon, ammunition, flight_model = _ready_model(readings, args)
    angles = readings.get(aim_name)
    if flight_model == None or angles == None:
        return None
    return {"points": flight(weapon, flight_model, angles)["points"], "strike": True}

def shoot_readings(readings, args):
    """Publishes `center`, and `engagement` for colony fire control when the load and flight are known."""
    aim_name, weapon, ammunition, flight_model = _ready_model(readings, args)
    if weapon == None:
        return {}
    published = {"center": weapon["pivot"]}
    port, _ = _port(args)
    if flight_model == None or ammunition and load_problem(weapon, ammunition, port) != None:
        return published
    published["engagement"] = {
        "aim": aim_name,
        "model": flight_model,
        "indirect": _option(readings, args, "indirect", weapon.get("indirect", False)),
        "range": reach(weapon, flight_model, readings, args),
        "damage": _option(readings, args, "damage", weapon.get("damage", 20)),
        "blast": blast(weapon, ammunition),
    }
    return published
