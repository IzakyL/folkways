accepts = ["zone"]
knobs = [count("rounds", 1, 8, default = 3)]

def grow(site):
    n = site.kept.get("n", 0)
    if n >= site.count("rounds"):
        return []
    cell = site.zone.sized(width = 1, height = 1, depth = 1).at(x = n)
    return grown([part("step", box(cell), ["minecraft:stone"])], keep = {"n": n + 1})
