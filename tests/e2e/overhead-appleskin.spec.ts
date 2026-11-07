import { expect, test } from "@playwright/test";
import { defineTask, javaTask, media, screen, tick } from "@izakyl/blockwright-minecraft";
import { mkdirSync } from "node:fs";
import path from "node:path";
import { foundingBox, waitForGroundedPlayer } from "../shared/colony/colony-founding";
import { foundBookColony, tapV } from "../shared/combat/overhead";
import { launchPair, type E2EPair } from "../shared/harness/pair";
import { outAbs } from "../shared/harness/out-paths";
import { seededServer } from "../shared/harness/mod-settings";
import { frames, tryCommand } from "../shared/harness/bw-helpers";
import { newRunId } from "../shared/harness/run-id";
import { GROUND } from "../shared/harness/stage";

type Drawn = { appleskin: boolean; food: number; layers: Record<string, number> };

// Counts the HUD layers the game actually drew since the last reading: a layer whose Pre event was cancelled never
// reaches Post. The counts live in the system properties, where a listener added once outlives the task class.
const DRAWN_LAYERS = defineTask<void, Drawn>({
  name: "folkways.overhead-appleskin.layers", side: "client",
  source: javaTask({ imports: [
    "net.neoforged.neoforge.common.NeoForge",
    "net.neoforged.neoforge.client.event.RenderGuiLayerEvent",
    "net.neoforged.neoforge.client.gui.VanillaGuiLayers",
    "net.neoforged.fml.ModList",
    "java.util.concurrent.ConcurrentHashMap",
    "java.util.concurrent.atomic.AtomicInteger",
  ], body: `
var props = System.getProperties();
@SuppressWarnings("unchecked")
var counts = (Map<String, AtomicInteger>) props.get("folkways.overhead-appleskin.counts");
if (counts == null) {
  var fresh = new ConcurrentHashMap<String, AtomicInteger>();
  props.put("folkways.overhead-appleskin.counts", fresh);
  NeoForge.EVENT_BUS.addListener((RenderGuiLayerEvent.Post event) -> {
    var name = event.getName();
    if (name.getNamespace().equals("appleskin") || name.equals(VanillaGuiLayers.FOOD_LEVEL)) {
      fresh.computeIfAbsent(name.toString(), key -> new AtomicInteger()).incrementAndGet();
    }
  });
  counts = fresh;
}
var seen = new LinkedHashMap<String, Object>();
counts.forEach((name, count) -> seen.put(name, count.getAndSet(0)));
return Map.of("appleskin", ModList.get().isLoaded("appleskin"),
  "food", seen.getOrDefault(VanillaGuiLayers.FOOD_LEVEL.toString(), 0), "layers", seen);
` }),
});

test("the view from above puts AppleSkin's overlays away with the food and health bars", async ({}, testInfo) => {
  test.setTimeout(240_000);
  const shots = outAbs("e2e", "shots", "overhead-appleskin");
  mkdirSync(shots, { recursive: true });
  let pair: E2EPair | undefined;
  try {
    const run = newRunId();
    pair = await launchPair({ config: "blockwright.toml#appleskin",
      server: { instance: seededServer(`appleskin-server-${run}`) }, client: { instance: `appleskin-client-${run}` } });
    const { server, client } = pair;
    const player = await waitForGroundedPlayer(server);
    const origin = { x: Math.round(player.pos.x) + 4, y: Math.round(player.pos.y), z: Math.round(player.pos.z) + 4 };
    await GROUND.lay(server, foundingBox(origin));
    await foundBookColony(server, client, player.name, origin, 1);
    await tryCommand(server, `gamemode survival ${player.name}`);
    // Hungry, with saturation left, and bread in the off hand: every AppleSkin overlay has something to draw.
    await tryCommand(server, `effect give ${player.name} minecraft:hunger 3 100 true`);
    await tick.sprint(server, 60);
    await tryCommand(server, `item replace entity ${player.name} weapon.offhand with minecraft:bread`);
    await tick.sprint(server, 3);
    await frames(client, 10);
    await client.run(DRAWN_LAYERS);
    await frames(client, 10);

    const firstPerson = await client.run(DRAWN_LAYERS);
    expect(firstPerson.appleskin, "the appleskin profile installs AppleSkin").toBe(true);
    expect(firstPerson.food, "out of the player's own eyes the food bar is drawn").toBeGreaterThan(0);
    const dressing = Object.keys(firstPerson.layers).filter(name => name.startsWith("appleskin:"));
    expect(dressing, "AppleSkin's layers are drawn beside it").toContain("appleskin:saturation_level");
    await media.screenshot(client, { path: path.join(shots, "01-first-person.png") });

    await tapV(client);
    await screen.waitFor(client, "OverheadScreen", { timeoutMs: 8_000 });
    await frames(client, 5);
    await client.run(DRAWN_LAYERS);
    await frames(client, 10);
    const above = await client.run(DRAWN_LAYERS);
    expect(above.food, "the view from above puts the food bar away").toBe(0);
    expect(Object.entries(above.layers).filter(([name, count]) => name.startsWith("appleskin:") && count > 0),
      "and every AppleSkin layer with it").toEqual([]);
    await media.screenshot(client, { path: path.join(shots, "02-view-from-above.png") });

    await tapV(client);
    await frames(client, 5);
    await client.run(DRAWN_LAYERS);
    await frames(client, 10);
    const back = await client.run(DRAWN_LAYERS);
    expect(back.layers["appleskin:saturation_level"] ?? 0, "back in the player's eyes AppleSkin draws again")
      .toBeGreaterThan(0);
  } catch (error) {
    pair?.failing(error);
    throw error;
  } finally {
    await pair?.teardown(testInfo);
  }
});
