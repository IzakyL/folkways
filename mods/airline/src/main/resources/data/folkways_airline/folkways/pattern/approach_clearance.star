load("folkways_airline:airfield.star", "clearance", "runway")
accepts = ["path"]
capture = {"around": 8, "up": "all", "down": 1}
knobs = [count("width", 5, 31, default = 5, icon = "minecraft:gray_concrete")]
lang = {
    "en_us": {
        "title": "Approach clearance (1:20)",
        "width": "Runway width"
    },
    "zh_cn": {
        "title": "净空平整（1:20）",
        "width": "跑道宽度"
    },
    "zh_hk": {
        "title": "淨空平整（1:20）",
        "width": "跑道寬度"
    },
    "zh_tw": {
        "title": "淨空平整（1:20）",
        "width": "跑道寬度"
    }
}

def draw(site):
    return clearance(site, runway(site))
