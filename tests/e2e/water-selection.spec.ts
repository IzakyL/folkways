import { writeFileSync } from "node:fs";
import { expect, test } from "@playwright/test";
import { defineTask, input, javaTask, media, tick, world } from "@izakyl/blockwright-minecraft";
import { cameraLookingAt } from "@izakyl/blockwright-minecraft/internal";
import { commandPos, createColonyViaBook, waitForGroundedPlayer } from "../shared/colony/colony-founding";
import { setBookGesture } from "../shared/ui/zone-tools";
import { launchPair, type E2EPair } from "../shared/harness/pair";
import { ensureOut, outAbs, tracePath } from "../shared/harness/out-paths";
import { seededServer } from "../shared/harness/mod-settings";
import { frames } from "../shared/harness/bw-helpers";
import { BUDGET } from "../shared/harness/budgets";
import { newRunId } from "../shared/harness/run-id";
import { around, twins } from "../shared/harness/stage";

type Shot = Awaited<ReturnType<typeof media.screenshot>>;
type Vec = { x: number; y: number; z: number };
const MARKED = defineTask<undefined, { marked: boolean; drafting: boolean; sneaking: boolean; min?: Vec; max?: Vec }>({
  name: "folkways.water-selection.marked", side: "client", source: javaTask({ body: `
var type=Class.forName("io.github.izakyl.folkways.front.client.ZoneSelection");
var marked=(java.util.Optional<?>)type.getMethod("marked").invoke(null);
boolean drafting=(Boolean)type.getMethod("drafting").invoke(null);
boolean sneaking=((net.minecraft.client.Minecraft)ctx.client()).player.isShiftKeyDown();
if(marked.isEmpty())return Map.of("marked",false,"drafting",drafting,"sneaking",sneaking);
var box=marked.get();var min=(net.minecraft.core.BlockPos)box.getClass().getMethod("min").invoke(box);
var max=(net.minecraft.core.BlockPos)box.getClass().getMethod("max").invoke(box);
return Map.of("marked",true,"drafting",drafting,"sneaking",sneaking,"min",Map.of("x",min.getX(),"y",min.getY(),"z",min.getZ()),
"max",Map.of("x",max.getX(),"y",max.getY(),"z",max.getZ()));
` }),
});

for (const stage of twins()) test(stage.title("water selection: the actual marked outline remains visible above and below the surface"), async ({}, testInfo) => {
  test.setTimeout(BUDGET.slow); ensureOut("e2e");
  const runId = newRunId(); let pair: E2EPair | undefined;
  const evidence: Record<string, any> = { stage: stage.kind, views: [] };
  try {
    pair = await launchPair({ config: stage.config,
      server: { trace: tracePath("e2e", stage.name("water-selection-server-" + runId)), instance: seededServer(stage.name("water-selection-server-" + runId)) },
      client: { trace: tracePath("e2e", stage.name("water-selection-client-" + runId)), instance: stage.name("water-selection-client-" + runId) } });
    const { server, client } = pair;
    const grounded = await waitForGroundedPlayer(server); const player = grounded.name;
    const origin = { x: Math.round(grounded.pos.x) + 4, y: Math.round(grounded.pos.y) + 4, z: Math.round(grounded.pos.z) + 4 };
    const plot = around([origin], 12, 7); await stage.lay(server, { min: { ...plot.min, y: origin.y - 3 }, max: plot.max });
    const block = (at: { x: number; y: number; z: number }) => commandPos(stage.at(at));
    await world.command(server, "gamerule doDaylightCycle false"); await world.command(server, "gamerule doWeatherCycle false");
    await world.command(server, "time set noon"); await world.command(server, "weather clear");
    try {
      await world.command(server, `fill ${block({ ...plot.min, y: origin.y - 3 })} ${block(plot.max)} minecraft:air`);
    } catch (error) {
      if (!(error instanceof Error) || !error.message.includes("No blocks were filled")) throw error;
    }
    await world.command(server, `fill ${block({ ...plot.min, y: origin.y - 3 })} ${block({ ...plot.max, y: origin.y - 1 })} minecraft:smooth_stone`);
    await world.command(server, `setblock ${block(origin)} minecraft:smooth_stone`);
    await world.command(server, `item replace entity ${player} weapon.mainhand with folkways:colony_book`);
    await world.command(server, `tp ${player} ${origin.x - 3.5} ${origin.y + 0.2} ${origin.z + 0.5}`);
    await tick.sprint(server, 5);
    evidence.founding = await createColonyViaBook(server, client, player, origin, stage);
    const min = { x: origin.x + 2, y: origin.y - 3, z: origin.z + 2 };
    const max = { x: origin.x + 5, y: origin.y - 3, z: origin.z + 5 };
    const pondMin = { x: origin.x + 1, y: origin.y - 2, z: origin.z + 1 };
    const pondMax = { x: origin.x + 6, y: origin.y - 1, z: origin.z + 6 };
    await world.command(server, `fill ${block({ ...pondMin, y: min.y })} ${block({ ...pondMax, y: min.y })} minecraft:dirt`);
    await world.command(server, `fill ${block(pondMin)} ${block(pondMax)} minecraft:water[level=0]`);
    evidence.mode = await setBookGesture(server, client, player, "box");
    const mark = async () => {
      // Each ray begins on the nearby solid rim. Native clickBlock verifies the actual crosshair block.
      const first = await stage.click({ server, client }, min, { button: "left",
        from: { x: min.x + 0.5, y: origin.y, z: origin.z + 0.5 } });
      await tick.sprint(server, 3);
      const second = await stage.click({ server, client }, max, { button: "left",
        from: { x: max.x + 0.5, y: origin.y, z: origin.z + 7.5 } });
      await tick.sprint(server, 5);
      const actual = await client.run(MARKED, undefined);
      expect(actual.marked, "both real clicks produce a marked box").toBe(true);
      expect(actual.min, "the selected minimum is the requested submerged dirt, in storage coordinates").toEqual(stage.at(min));
      expect(actual.max, "the selected maximum is the requested submerged dirt, in storage coordinates").toEqual(stage.at(max));
      return { first, second, actual };
    };
    const target = stage.world({ x: origin.x + 3.5, y: origin.y - 2, z: origin.z + 3.5 });
    const viewpoints = [
      { name: "shore-through-water", wet: true, eye: stage.world({ x: origin.x + 3.5, y: origin.y + 3, z: origin.z - 1.5 }) },
      { name: "underwater-camera", wet: true, eye: stage.world({ x: origin.x + 3.5, y: origin.y - 0.35, z: origin.z + 1.3 }) },
      { name: "dry-control", wet: false, eye: stage.world({ x: origin.x + 3.5, y: origin.y + 3, z: origin.z - 1.5 }) },
      { name: "opaque-wall-control", wet: false, eye: stage.world({ x: origin.x + 3.5, y: origin.y + 3, z: origin.z - 1.5 }) },
    ];
    for (const view of viewpoints) {
      if (view.name === "dry-control") await world.command(server, `fill ${block(pondMin)} ${block(pondMax)} minecraft:air`);
      await tick.sprint(server, 25);
      const fluid = await server.run(POND, { min: stage.at(pondMin), max: stage.at(pondMax), plotMin: stage.at({ ...plot.min, y: origin.y - 2 }), plotMax: stage.at({ ...plot.max, y: origin.y - 1 }) });
      expect(fluid.inside, "the fixture contains the full native two-deep source pond, or a completely dry control").toBe(view.wet ? 72 : 0);
      expect(fluid.outside, "solid floor and walls prevent escaped water in every view").toBe(0);
      const marked = await mark();
      if (view.name === "opaque-wall-control") {
        await world.command(server, `fill ${block({ x: plot.min.x, y: min.y, z: origin.z + 1 })} ${block({ x: plot.max.x, y: plot.max.y, z: origin.z + 1 })} minecraft:stone`);
        await tick.sprint(server, 5);
      }
      const camera = { position: view.eye, ...cameraLookingAt(view.eye, target), fov: 70 };
      const capture = async (suffix: string) => media.screenshot(client, { camera, maxWidth: 1280,
        path: outAbs("e2e", "shots", stage.name(`water-selection-${runId}-${view.name}-${suffix}.png`)) });
      const active = await capture("marked");
      await input.key(client, { keybind: "key.sneak", action: "press" });
      try {
        await expect.poll(async () => (await client.run(MARKED, undefined)).sneaking,
          { message: "the native player has entered sneak before the cancel click" }).toBe(true);
        await input.button(client, { button: "left", action: "press" });
        await input.button(client, { button: "left", action: "release" });
      }
      finally { await input.key(client, { keybind: "key.sneak", action: "release" }); }
      await frames(client, 4);
      const cancelled = await client.run(MARKED, undefined);
      expect(cancelled.marked, "the actual cancel gesture removes the selection").toBe(false);
      expect(cancelled.drafting, "the cancel gesture also removes the first corner, leaving no invisible draft").toBe(false);
      await expect.poll(async () => (await client.run(MARKED, undefined)).sneaking,
        { message: "the native player has released sneak before another selection" }).toBe(false);
      const hidden = await capture("unmarked"); const noise = await capture("unmarked-noise");
      const visibility = outlineDifference(active, hidden, noise);
      evidence.views.push({ ...view, camera, marked, fluid, visibility,
        frames: [active, hidden, noise].map(shot => ({ path: shot.path, sha256: shot.sha256, frame: shot.frame, terrain: shot.terrain })) });
    }
    // Capture every view before asserting, retaining both wet baselines even when one fails.
    for (const view of evidence.views) {
      if (view.name === "opaque-wall-control") {
        expect(view.visibility.signal, "solid stone still hides the selection behind it")
          .toBeLessThanOrEqual(view.visibility.noise * 1.25 + 30);
        continue;
      }
      expect(view.visibility.largest, view.name + ": the real framebuffer contains a continuous visible selection edge").toBeGreaterThan(35);
      expect(view.visibility.signal, view.name + ": selection pixels exceed independently captured water-animation noise")
        .toBeGreaterThan(view.visibility.noise * 1.25 + 30);
    }
  } catch (error) { pair?.failing(error); throw error; }
  finally {
    const file = testInfo.outputPath("water-selection-evidence.json");
    writeFileSync(file, JSON.stringify(evidence, null, 2));
    await testInfo.attach("water-selection-evidence.json", { path: file, contentType: "application/json" });
    await pair?.teardown(testInfo);
  }
});

function outlineDifference(active: Shot, hidden: Shot, noise: Shot) {
  if (active.width !== hidden.width || active.height !== hidden.height || noise.width !== hidden.width || noise.height !== hidden.height) throw new Error("capture sizes changed");
  const { width, height } = active; const mask = new Uint8Array(width * height); let signal = 0, noisePixels = 0;
  const brighter = (one: Buffer, two: Buffer, at: number) => one[at]! + one[at + 1]! + one[at + 2]! - two[at]! - two[at + 1]! - two[at + 2]! > 45;
  for (let y = Math.floor(height * 0.1); y < height * 0.9; y++) for (let x = Math.floor(width * 0.1); x < width * 0.9; x++) {
    const pixel = y * width + x, at = pixel * 4;
    if (brighter(hidden.rgba, noise.rgba, at) || brighter(noise.rgba, hidden.rgba, at)) noisePixels++;
    if (brighter(active.rgba, hidden.rgba, at)) { mask[pixel] = 1; signal++; }
  }
  let largest = 0;
  for (let at = 0; at < mask.length; at++) if (mask[at]) {
    const queue = [at]; mask[at] = 0;
    for (let i = 0; i < queue.length; i++) {
      const pixel = queue[i]!, x = pixel % width, y = Math.floor(pixel / width);
      for (let dy = -1; dy <= 1; dy++) for (let dx = -1; dx <= 1; dx++) {
        if (x + dx < 0 || x + dx >= width || y + dy < 0 || y + dy >= height) continue;
        const next = (y + dy) * width + x + dx;
        if (mask[next]) { mask[next] = 0; queue.push(next); }
      }
    }
    largest = Math.max(largest, queue.length);
  }
  return { signal, noise: noisePixels, largest, width, height };
}
const POND = defineTask<{ min: Vec; max: Vec; plotMin: Vec; plotMax: Vec }, { inside: number; outside: number }>({
  name: "folkways.water-selection.actual-fluid", side: "server", source: javaTask({ body: `
var a=Args.of(ctx);var one=(Map<?,?>)a.value("min");var two=(Map<?,?>)a.value("max");
var low=(Map<?,?>)a.value("plotMin");var high=(Map<?,?>)a.value("plotMax");
int minX=((Number)one.get("x")).intValue(),minY=((Number)one.get("y")).intValue(),minZ=((Number)one.get("z")).intValue();
int maxX=((Number)two.get("x")).intValue(),maxY=((Number)two.get("y")).intValue(),maxZ=((Number)two.get("z")).intValue();
int inside=0,outside=0;var level=ctx.server().overworld();
for(int x=((Number)low.get("x")).intValue();x<=((Number)high.get("x")).intValue();x++)
for(int y=((Number)low.get("y")).intValue();y<=((Number)high.get("y")).intValue();y++)
for(int z=((Number)low.get("z")).intValue();z<=((Number)high.get("z")).intValue();z++){
  if(level.getFluidState(new net.minecraft.core.BlockPos(x,y,z)).is(net.minecraft.tags.FluidTags.WATER)){
    if(x>=minX&&x<=maxX&&y>=minY&&y<=maxY&&z>=minZ&&z<=maxZ)inside++;else outside++;
  }
}
return Map.of("inside",inside,"outside",outside);
` }),
});
