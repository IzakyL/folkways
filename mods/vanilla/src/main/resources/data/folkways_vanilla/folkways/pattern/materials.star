# Blocks the patterns offer in their items slots, by the part they suit. A slot lists its own defaults first,
# since the order a slot is written in is the order its blocks reach the pattern.

MASONRY = [
    "minecraft:stone_bricks",
    "minecraft:mossy_stone_bricks",
    "minecraft:cobblestone",
    "minecraft:mossy_cobblestone",
    "minecraft:stone",
    "minecraft:smooth_stone",
    "minecraft:andesite",
    "minecraft:polished_andesite",
    "minecraft:granite",
    "minecraft:polished_granite",
    "minecraft:diorite",
    "minecraft:polished_diorite",
    "minecraft:tuff",
    "minecraft:tuff_bricks",
    "minecraft:bricks",
    "minecraft:mud_bricks",
    "minecraft:sandstone",
    "minecraft:cobbled_deepslate",
    "minecraft:deepslate_bricks",
    "minecraft:polished_deepslate",
    "minecraft:chiseled_stone_bricks",
]

ROOFING = [
    "minecraft:deepslate_tiles",
    "minecraft:polished_deepslate",
    "minecraft:deepslate_bricks",
    "minecraft:bricks",
    "minecraft:stone_bricks",
    "minecraft:cobblestone",
    "minecraft:spruce_planks",
    "minecraft:dark_oak_planks",
    "minecraft:oak_planks",
]

EARTH = [
    "minecraft:dirt",
    "minecraft:coarse_dirt",
    "minecraft:grass_block",
    "minecraft:podzol",
    "minecraft:rooted_dirt",
    "minecraft:mud",
    "minecraft:packed_mud",
    "minecraft:gravel",
    "minecraft:sand",
]

WOODS = ["oak", "spruce", "birch", "jungle", "acacia", "dark_oak", "mangrove", "cherry"]

PLANKS = ["minecraft:%s_planks" % wood for wood in WOODS]
LOGS = ["minecraft:%s_log" % wood for wood in WOODS] + ["minecraft:stripped_%s_log" % wood for wood in WOODS]
FENCES = ["minecraft:%s_fence" % wood for wood in WOODS]
GATES = ["minecraft:%s_fence_gate" % wood for wood in WOODS]
WOOD_STAIRS = ["minecraft:%s_stairs" % wood for wood in WOODS]
WOOD_SLABS = ["minecraft:%s_slab" % wood for wood in WOODS]

STONE_STAIRS = [
    "minecraft:cobblestone_stairs",
    "minecraft:mossy_cobblestone_stairs",
    "minecraft:stone_brick_stairs",
    "minecraft:mossy_stone_brick_stairs",
    "minecraft:stone_stairs",
    "minecraft:andesite_stairs",
    "minecraft:brick_stairs",
    "minecraft:mud_brick_stairs",
    "minecraft:sandstone_stairs",
]

WALLS = [
    "minecraft:stone_brick_wall",
    "minecraft:mossy_stone_brick_wall",
    "minecraft:cobblestone_wall",
    "minecraft:mossy_cobblestone_wall",
    "minecraft:andesite_wall",
    "minecraft:brick_wall",
    "minecraft:mud_brick_wall",
    "minecraft:sandstone_wall",
]

LAMPS = ["minecraft:lantern", "minecraft:soul_lantern"]

def offer(first, *pools):
    listed = list(first)
    for pool in pools:
        for block in pool:
            if block not in listed:
                listed.append(block)
    return listed
