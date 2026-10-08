NEARLY = 0.000001

def floor(value):
    whole = int(value)
    return whole - 1 if whole > value else whole

def size(value):
    return -value if value < 0 else value

def ceil(value):
    whole = int(value)
    return whole + 1 if whole < value else whole

def root(value):
    if value <= 0:
        return 0.0
    guess = max(1.0, value / 2.0)
    for _ in range(64):
        better = (guess + value / guess) / 2.0
        if size(better - guess) < 0.000000001:
            return better
        guess = better
    return guess

def runs_of(marks):
    runs = []
    start = 0.0
    for i in range(len(marks) - 1):
        ax, az = marks[i]
        bx, bz = marks[i + 1]
        length = root((bx - ax) * (bx - ax) + (bz - az) * (bz - az))
        runs.append({"ax": ax, "az": az, "bx": bx, "bz": bz, "ux": (bx - ax) / length,
            "uz": (bz - az) / length, "length": length, "start": start})
        start += length
    return {"runs": runs, "length": start}

def centreline(site, width):
    marks = []
    for p in site.path.points:
        spot = (p.x + 0.5, p.z + 0.5)
        if not marks or marks[-1] != spot:
            marks.append(spot)
    if len(marks) < 2:
        fail("A road needs two different points / 道路至少需要两个不同的点")
    line = runs_of(marks)
    runs = line["runs"]
    for j in range(1, len(runs)):
        if runs[j - 1]["ux"] * runs[j]["ux"] + runs[j - 1]["uz"] * runs[j]["uz"] < -0.7072:
            fail("The path doubles back at point %d; bends up to 135° are allowed / 路径在第 %d 个点处折返，转角不得超过 135°"
                % (j + 1, j + 1))
    if width % 2 == 1:
        return line
    shifted = []
    for i in range(len(marks)):
        before = runs[max(0, i - 1)]
        after = runs[min(len(runs) - 1, i)]
        nx = -before["uz"] - after["uz"]
        nz = before["ux"] + after["ux"]
        scale = 0.5 / (1.0 + before["ux"] * after["ux"] + before["uz"] * after["uz"])
        shifted.append((marks[i][0] + nx * scale, marks[i][1] + nz * scale))
    return runs_of(shifted)

def locate(line, cx, cz, half):
    runs = line["runs"]
    last = len(runs) - 1
    best = None
    for j in range(len(runs)):
        run = runs[j]
        rx = cx - run["ax"]
        rz = cz - run["az"]
        t = rx * run["ux"] + rz * run["uz"]
        side = size(rz * run["ux"] - rx * run["uz"])
        over = 0.0
        if t < 0:
            if j == 0:
                over = -t - 0.5
            else:
                before = runs[j - 1]
                if (cx - before["ax"]) * before["ux"] + (cz - before["az"]) * before["uz"] <= before["length"]:
                    continue
                side = root(rx * rx + rz * rz)
            t = 0.0
        elif t > run["length"]:
            if j == last:
                over = t - run["length"] - 0.5
            else:
                after = runs[j + 1]
                if (cx - after["ax"]) * after["ux"] + (cz - after["az"]) * after["uz"] >= 0:
                    continue
                ex = cx - run["bx"]
                ez = cz - run["bz"]
                side = root(ex * ex + ez * ez)
            t = run["length"]
        beyond = side - half
        if over > 0:
            beyond = over if beyond <= 0 else root(beyond * beyond + over * over)
        if best == None or beyond < best[0]:
            best = (beyond, run["start"] + t)
    return best

def at(line, s):
    for run in line["runs"]:
        if s <= run["start"] + run["length"] or run == line["runs"][-1]:
            d = max(0.0, min(run["length"], s - run["start"]))
            return run["ax"] + run["ux"] * d, run["az"] + run["uz"] * d, run
    fail("unreachable")

def landings(line, half):
    runs = line["runs"]
    spans = []
    for j in range(1, len(runs)):
        dot = runs[j - 1]["ux"] * runs[j]["ux"] + runs[j - 1]["uz"] * runs[j]["uz"]
        if dot > 0.9999:
            continue
        cross = size(runs[j - 1]["ux"] * runs[j]["uz"] - runs[j - 1]["uz"] * runs[j]["ux"])
        reach = half * cross / (1.0 + dot) + 1.0
        spans.append((runs[j]["start"] - reach, runs[j]["start"] + reach))
    return spans

def flat(spans, a, b):
    covered = 0.0
    for low, high in spans:
        covered += max(0.0, min(b, high) - max(a, low))
    return min(b - a, covered)

def survey(site, line, width):
    stations = []
    runs = line["runs"]
    for j in range(len(runs)):
        run = runs[j]
        steps = max(1, ceil(run["length"] - NEARLY))
        for m in range(0 if j == 0 else 1, steps + 1):
            d = run["length"] * m / steps
            stations.append({"s": run["start"] + d, "x": run["ax"] + run["ux"] * d,
                "z": run["az"] + run["uz"] * d, "run": j})
    reach = width // 2
    for station in stations:
        run = runs[station["run"]]
        heights = []
        water = None
        for o in range(-reach, reach + 1):
            cx = floor(station["x"] - run["uz"] * o)
            cz = floor(station["z"] + run["ux"] * o)
            ground = site.ground(cx, cz)
            if ground == None:
                fail("No ground under the road near (%d, %d) / 道路 (%d, %d) 附近下方没有地面" % (cx, cz, cx, cz))
            heights.append(ground)
            if o == 0:
                station["centre"] = ground
            above = point(cx, ground + 1, cz)
            if site.matches(above, "fluid"):
                depth = site.probe(above, "up", until = "!fluid", max = 32)
                top = ground + (depth if depth != None else 32)
                water = top if water == None else max(water, top)
                station["bed"] = ground if "bed" not in station else min(station["bed"], ground)
        heights = sorted(heights)
        station["ground"] = heights[len(heights) // 2]
        station["water"] = water
    return stations

def profile(site, line, width, grade = 0.5, smoothing = 4.0, deepest = 8, crossing = 24, drowned = 6):
    stations = survey(site, line, width)
    count = len(stations)
    floors = []
    for station in stations:
        wet = station["water"]
        floors.append(None if wet == None else wet + 1)
        station["dry"] = station["ground"] if wet == None else max(station["ground"], wet + 1)
    wet_from = None
    for i in range(count):
        station = stations[i]
        if station["water"] != None:
            if station["water"] - station["bed"] > drowned:
                fail("Water deeper than %d blocks — build an arch bridge here / 水深超过 %d 格，请改用拱桥"
                    % (drowned, drowned))
            if wet_from == None:
                wet_from = station["s"]
            if station["s"] - wet_from > crossing:
                fail("Water crossing longer than %d blocks — build an arch bridge here / 水面超过 %d 格，请改用拱桥"
                    % (crossing, crossing))
        else:
            wet_from = None
    targets = []
    for i in range(count):
        total = 0.0
        taken = 0
        for k in range(max(0, i - 3 * int(smoothing)), min(count, i + 3 * int(smoothing) + 1)):
            if size(stations[k]["s"] - stations[i]["s"]) <= smoothing:
                total += stations[k]["dry"]
                taken += 1
        targets.append(total / taken)
    spans = landings(line, width / 2.0)
    run = [0.0]
    for i in range(1, count):
        a = stations[i - 1]["s"]
        b = stations[i]["s"]
        run.append(run[i - 1] + (b - a) - flat(spans, a, b))
    total = run[count - 1]
    first = stations[0]["centre"]
    last = stations[count - 1]["centre"]
    if size(last - first) > grade * total + NEARLY:
        fail("Too steep: the ends differ by %d blocks over %d / 坡度过陡：两端高差 %d 格，路长仅 %d 格"
            % (size(last - first), int(line["length"]), size(last - first), int(line["length"])))
    cone = list(floors)
    for i in range(1, count):
        if cone[i - 1] != None:
            reach = cone[i - 1] - grade * (run[i] - run[i - 1])
            cone[i] = reach if cone[i] == None else max(cone[i], reach)
    for i in range(count - 2, -1, -1):
        if cone[i + 1] != None:
            reach = cone[i + 1] - grade * (run[i + 1] - run[i])
            cone[i] = reach if cone[i] == None else max(cone[i], reach)
    heights = []
    for i in range(count):
        low = max(first - grade * run[i], last - grade * (total - run[i]))
        if cone[i] != None:
            low = max(low, cone[i])
        high = min(first + grade * run[i], last + grade * (total - run[i]))
        if low > high + NEARLY:
            fail("The road cannot climb clear of the water and still meet the ground at both ends"
                + " / 道路无法既高出水面又与两端地面衔接")
        heights.append(max(low, min(high, targets[i])))
    for i in range(1, count):
        step = grade * (run[i] - run[i - 1])
        heights[i] = max(heights[i - 1] - step, min(heights[i - 1] + step, heights[i]))
    for i in range(count - 2, -1, -1):
        step = grade * (run[i + 1] - run[i])
        heights[i] = max(heights[i + 1] - step, min(heights[i + 1] + step, heights[i]))
    worst = 0
    for i in range(count):
        stations[i]["y"] = heights[i]
        worst = max(worst, ceil(size(heights[i] - stations[i]["dry"])))
    if worst > deepest:
        fail("The ground is too uneven: it would take %d blocks of cut or fill; add points or bridge the gap"
            % worst + " / 地形起伏过大，需要挖填 %d 格；请增加路径点或改建桥梁" % worst)
    return {"stations": stations, "depth": worst}

def level(profile, s):
    stations = profile["stations"]
    low = 0
    high = len(stations) - 1
    if s <= stations[low]["s"]:
        return stations[low]["y"]
    if s >= stations[high]["s"]:
        return stations[high]["y"]
    for _ in range(32):
        if high - low <= 1:
            break
        middle = (low + high) // 2
        if stations[middle]["s"] <= s:
            low = middle
        else:
            high = middle
    a = stations[low]
    b = stations[high]
    span = b["s"] - a["s"]
    return a["y"] if span <= 0 else a["y"] + (b["y"] - a["y"]) * (s - a["s"]) / span

def trace(profile):
    return [[station["x"] - 0.5, station["y"], station["z"] - 0.5] for station in profile["stations"]]

def bed(y):
    return ceil(y - 0.75)

def earthworks(site, line, profile, width, shoulder = 1, headroom = 4, canopy = 8):
    half = width / 2.0
    spread = int(half) + shoulder + profile["depth"] + 2
    xs = [run["ax"] for run in line["runs"]] + [line["runs"][-1]["bx"]]
    zs = [run["az"] for run in line["runs"]] + [line["runs"][-1]["bz"]]
    columns = {}
    fill = []
    dig = []
    crown = []
    for x in range(floor(min(xs)) - spread, floor(max(xs)) + spread + 1):
        for z in range(floor(min(zs)) - spread, floor(max(zs)) + spread + 1):
            beyond, s = locate(line, x + 0.5, z + 0.5, half)
            if beyond > spread:
                continue
            ground = site.ground(x, z)
            if ground == None:
                fail("No ground beside the road at (%d, %d) / 道路旁 (%d, %d) 没有地面" % (x, z, x, z))
            top = bed(level(profile, s))
            k = 0 if beyond < -NEARLY else max(1, ceil(beyond))
            slope = max(0, k - shoulder)
            low = top - 1 if k == 0 else top - slope
            high = top if k == 0 else top + slope
            for y in range(ground + 1, low + 1):
                fill.append((x, y, z))
            cleared = high
            if ground > high:
                cleared = ground + 2
            if k <= shoulder:
                columns[(x, z)] = (k, top)
                cleared = max(cleared, top + headroom)
                for y in range(cleared + 1, top + canopy + 1):
                    crown.append((x, y, z))
            for y in range(high + 1, cleared + 1):
                dig.append((x, y, z))
    return {"columns": columns, "fill": fill, "dig": dig, "crown": crown}

def band(site, reach, low, high, of):
    found = []
    for stretch in site.path.stretches:
        room = stretch.sized(width = 2 * reach + 1, anchor = "center").at(z = -reach - 1)
        room = room.sized(depth = stretch.depth + 2 * reach + 2).at(y = low - stretch.origin.y)
        cells = terrain(room.sized(height = high - low + 1), of = of)
        if cells != None:
            found.append(cells)
    return group(found) if found else None

def only(site, listed, reach, of):
    if not listed:
        return None
    ys = [cell[1] for cell in listed]
    around = band(site, reach, min(ys), max(ys), of)
    if around == None:
        return None
    return trim(cells(listed), to = around)
