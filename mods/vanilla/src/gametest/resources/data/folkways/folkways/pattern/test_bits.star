detail = "bits"
accepts = ["zone"]

def draw(site):
    return [part("dome", dome(site.zone), ["minecraft:stone"])]
