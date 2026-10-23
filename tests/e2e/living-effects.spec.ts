import { expect, test } from "@playwright/test";
import { defineTask, javaTask, reflect, tick, world } from "@izakyl/blockwright-minecraft";
import { foundColonyFast, foundingBox, waitForGroundedPlayer } from "../shared/colony/colony-founding";
import { mustCommand } from "../shared/ui/world-ui";
import { launchPair, type E2EPair } from "../shared/harness/pair";
import { seededServer } from "../shared/harness/mod-settings";
import { newRunId } from "../shared/harness/run-id";
import { twins } from "../shared/harness/stage";
import { commandPos, tryCommand } from "../shared/harness/bw-helpers";
import { BUDGET } from "../shared/harness/budgets";
import { ensureOut, tracePath } from "../shared/harness/out-paths";

// Real colony scheduling and room surveys, beyond the isolated action/geometry game tests.
for (const stage of twins()) test(stage.title("residents use idle activities and report a bedroom's grade and mood sources"), async ({}, testInfo) => {
  test.setTimeout(BUDGET.quick);
  ensureOut("e2e");
  const run = newRunId();
  let pair: E2EPair | undefined;
  try {
    pair = await launchPair({
      config: stage.config,
      server: { instance: seededServer(stage.name(`living-server-${run}`)), trace: tracePath("e2e", stage.name("living-server")) },
      client: { instance: stage.name(`living-client-${run}`), trace: tracePath("e2e", stage.name("living-client")) },
      snapshot: { containers: [], entityTypes: ["folkways:resident"] },
    });
    const { server, client } = pair;
    const player = await waitForGroundedPlayer(server);
    const origin = { x: Math.round(player.pos.x) + 4, y: Math.round(player.pos.y), z: Math.round(player.pos.z) + 4 };
    const box = foundingBox(origin);
    await stage.lay(server, box);
    const at = (x: number, y: number, z: number) => commandPos(stage.at({ x, y, z }));
    await tryCommand(server, `fill ${at(box.min.x, origin.y - 1, box.min.z)} ${at(box.max.x, origin.y - 1, box.max.z)} minecraft:stone`);
    await foundColonyFast(server, client, player.name, { origin, residentCount: 2, stage });
    const { x, y, z } = origin;
    await mustCommand(server, `fill ${at(x - 1, y, z - 1)} ${at(x - 1, y + 2, z + 4)} minecraft:stone`);
    await mustCommand(server, `fill ${at(x + 5, y, z - 1)} ${at(x + 5, y + 2, z + 4)} minecraft:stone`);
    await mustCommand(server, `fill ${at(x, y, z - 1)} ${at(x + 4, y + 2, z - 1)} minecraft:stone`);
    await mustCommand(server, `fill ${at(x, y, z + 4)} ${at(x + 4, y + 2, z + 4)} minecraft:stone`);
    await mustCommand(server, `fill ${at(x - 1, y + 3, z - 1)} ${at(x + 5, y + 3, z + 4)} minecraft:stone`);
    await mustCommand(server, `setblock ${at(x - 1, y, z + 2)} minecraft:oak_door[half=lower,facing=west]`);
    await mustCommand(server, `setblock ${at(x - 1, y + 1, z + 2)} minecraft:oak_door[half=upper,facing=west]`);
    await mustCommand(server, `setblock ${at(x + 4, y, z + 2)} minecraft:chest`);
    await mustCommand(server, `setblock ${at(x + 4, y, z + 3)} minecraft:potted_dandelion`);
    await mustCommand(server, `setblock ${at(x + 1, y, z + 2)} minecraft:red_carpet`);
    await mustCommand(server, `setblock ${at(x + 2, y + 3, z + 1)} minecraft:glowstone`);
    await tick.sprint(server, 200);
    const residents = await world.entities(server, { dimension: "minecraft:overworld", types: ["folkways:resident"], limit: 10 });
    expect(residents.entities.length).toBe(2);
    const life = await details(server, residents.entities[0].uuid);
    expect(life.join("\n")).toContain("Excellent bedroom");
    expect(life.join("\n")).toContain("Mood");
    expect(life.join("\n")).toContain("Recreation");
    expect(life.join("\n")).not.toContain("folkways.");

    const seen = new Set<string>();
    for (let i = 0; i < 25; i++) {
      await tick.sprint(server, 40);
      for (const resident of residents.entities) {
        const state = await reflect.invoke(server, { target: { kind: "entity", uuid: resident.uuid }, method: "leisureMotion", returnType: "java.lang.String" });
        if (typeof state.returned === "string" && state.returned) seen.add(state.returned);
      }
    }
    expect([...seen], "the real scheduler should run visible idle activities").not.toHaveLength(0);
    expect([...seen].every(kind => ["strolling", "chatting", "petting", "relaxing"].includes(kind))).toBe(true);
    await mustCommand(server, `setblock ${at(x, y + 3, z)} minecraft:air`);
    // Commands bypass player placement events; the bounded periodic survey must notice those changes too.
    await tick.sprint(server, 800);
    expect((await details(server, residents.entities[0].uuid)).join("\n")).toContain("Basic bed");
  } catch (error) {
    pair?.failing(error);
    throw error;
  } finally {
    await pair?.teardown(testInfo);
  }
});

// The resident window's living reading, written out as its lines read.
const READING = defineTask<{ uuid: string }, { lines: string }>({
  name: "folkways.living-effects.reading",
  side: "server",
  source: javaTask({
    imports: ["net.minecraft.server.MinecraftServer", "net.minecraft.network.chat.Component",
      "io.github.izakyl.folkways.core.api.resident.body.Body", "io.github.izakyl.folkways.front.api.ui.Pages",
      "io.github.izakyl.folkways.plugins.person.living.LivingContent"],
    body: `
var level = ((MinecraftServer)ctx.server()).overworld();
var body = (Body)level.getEntity(UUID.fromString(Args.of(ctx).string("uuid")));
var read = LivingContent.reading(body);
var lines = new ArrayList<String>();
if (read.contains("room")) { lines.add(Pages.readText(level.registryAccess(), read.get("room")).getString()); }
if (read.contains("advice")) { lines.add(Pages.readText(level.registryAccess(), read.get("advice")).getString()); }
if (read.contains("mood")) { lines.add(Component.translatable("folkways.mood.title").getString() + " " + read.getInt("mood")); }
var reasons = read.getCompound("reasons");
for (var reason : new TreeSet<>(reasons.getAllKeys())) {
  lines.add(Component.translatable("folkways.mood.reason." + reason, String.valueOf(reasons.getInt(reason))).getString());
}
return Map.of("lines", String.join("\\n", lines));
`,
  }),
});

async function details(server: any, uuid: string): Promise<string[]> {
  const { lines } = await server.run(READING, { uuid });
  const result = lines ? lines.split("\n") : [];
  expect(result.length, "life details should contain translated lines").toBeGreaterThan(0);
  return result;
}
