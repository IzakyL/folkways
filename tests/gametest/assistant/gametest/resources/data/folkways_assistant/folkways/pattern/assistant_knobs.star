capture = 0
knobs = [
    flag("enabled", default = True),
    count("length", 1, 4, default = 1),
    choice("material", ["stone", "dirt"], default = "stone"),
]

def draw(site):
    if not site.flag("enabled"):
        return []
    material = "minecraft:" + site.choice("material")
    return [blocks("row", {point(x, 0, 0): material for x in range(site.count("length"))})]
