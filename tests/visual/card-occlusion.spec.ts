import { expect, test } from "@playwright/test";
import { camera as cameras, defineTask, javaTask, screen, tick, world } from "@izakyl/blockwright-minecraft";
import { cameraLookingAt } from "@izakyl/blockwright-minecraft/internal";
import { launchPair, type E2EPair } from "../shared/harness/pair";
import { foundColonyFast, waitForGroundedPlayer, type Vec } from "../shared/colony/colony-founding";
import { aimAndWorldLeftClick } from "../shared/ui/world-ui";
import { setBookGesture } from "../shared/ui/zone-tools";
import { CONFIG_PATH, VisualRun } from "../shared/capture/visual-kit";
import { frames } from "../shared/harness/bw-helpers";
import { tracePath } from "../shared/harness/out-paths";
import { seededOffThreadServer } from "../shared/harness/mod-settings";
import { newRunId } from "../shared/harness/run-id";
import { BUDGET } from "../shared/harness/budgets";

// Read the actual resident card's five production occlusion samples, without changing rendering.
const CARD = defineTask<undefined, Record<string, any>>({
  name: "folkways.visual.card-occlusion.samples", side: "client", timeoutMs: 20_000,
  source: javaTask({
    imports: ["io.github.izakyl.folkways.front.client.ColonyLookState", "net.minecraft.client.Minecraft", "net.minecraft.world.phys.Vec3"],
    body: `
var mc = Minecraft.getInstance();
var look = ColonyLookState.residents().getFirst();
var resident = mc.level.getEntity(look.entityId());
var anchor = resident.position().add(0, resident.getBbHeight() + 0.5, 0);
var cards = Class.forName("io.github.izakyl.folkways.front.client.WorldCards");
var rowsMethod = Class.forName("io.github.izakyl.folkways.front.client.ColonyLookCard").getDeclaredMethod("rows", List.class);
rowsMethod.setAccessible(true);
var rows = (List<?>) rowsMethod.invoke(null, look.lines());
var layout = Class.forName("io.github.izakyl.folkways.front.client.LookCardLayout");
var of = layout.getDeclaredMethod("of", net.minecraft.client.gui.Font.class, List.class); of.setAccessible(true);
var laid = of.invoke(null, mc.font, rows);
var queued = Class.forName(cards.getName() + "$Queued");
var ctor = queued.getDeclaredConstructors()[0]; ctor.setAccessible(true);
var lookClass = Class.forName(cards.getName() + "$Look");
var plate = lookClass.getDeclaredField("PLATE"); plate.setAccessible(true);
var view = mc.gameRenderer.getMainCamera();
var card = ctor.newInstance(anchor, rows, 0.025f, plate.get(null), anchor.distanceToSqr(view.getPosition()));
var spotsMethod = cards.getDeclaredMethod("spots", net.minecraft.client.Camera.class, queued, laid.getClass()); spotsMethod.setAccessible(true);
var spots = (Vec3[]) spotsMethod.invoke(null, view, card, laid);
var hidden = cards.getDeclaredMethod("hidden", net.minecraft.client.multiplayer.ClientLevel.class, Vec3.class, Vec3[].class); hidden.setAccessible(true);
return Map.of("hidden", hidden.invoke(null, mc.level, view.getPosition(), spots), "samples", Arrays.stream(spots).map(Vec3::toString).toList(),
  "rows", rows.toString(), "anchor", anchor.toString(), "camera", view.getPosition().toString());
`,
  }),
});

// Stage three isolated owed blocks through the real ShapePreview/GhostRenderer pipeline.
// The editable box itself is marked with normal book clicks; no renderer is substituted.
const PREVIEW = defineTask<{ x: number; y: number; z: number }, Record<string, any>>({
  name: "folkways.visual.card-occlusion.preview", side: "client", timeoutMs: 20_000,
  source: javaTask({
    imports: ["io.github.izakyl.folkways.front.client.ZoneSelection", "io.github.izakyl.folkways.front.api.Ghost", "net.minecraft.core.BlockPos", "net.minecraft.world.level.block.Blocks", "net.minecraft.resources.ResourceLocation"],
    body: `
var args = Args.of(ctx); int x = (int) args.integer("x"), y = (int) args.integer("y"), z = (int) args.integer("z");
var shape = ZoneSelection.marked().orElseThrow();
var ghosts = List.of(new Ghost(new BlockPos(x+1,y+1,z+2), Blocks.OAK_PLANKS.defaultBlockState()),
  new Ghost(new BlockPos(x+2,y+1,z+2), Blocks.BRICKS.defaultBlockState()),
  new Ghost(new BlockPos(x+3,y+1,z+2), Blocks.DIAMOND_BLOCK.defaultBlockState()));
var preview = Class.forName("io.github.izakyl.folkways.front.client.ShapePreview");
var show = preview.getDeclaredMethod("show", ResourceLocation.class, Object.class, java.util.function.Supplier.class, List.class); show.setAccessible(true);
show.invoke(null, ResourceLocation.fromNamespaceAndPath("folkways", "visual_occlusion"), shape,
  (java.util.function.Supplier<Optional<?>>) () -> ZoneSelection.marked(), ghosts);
return Map.of("box", shape.toString(), "ghosts", ghosts.toString());
`,
  }),
});

const FACE = defineTask<undefined, Record<string, any>>({
  name: "folkways.visual.card-occlusion.face", side: "client", timeoutMs: 20_000,
  source: javaTask({ body: `
var zone = Class.forName("io.github.izakyl.folkways.front.client.ZoneSelection");
var face = zone.getDeclaredField("aimedFace"); face.setAccessible(true);
var preview = Class.forName("io.github.izakyl.folkways.front.client.ShapePreview");
var ghosts = preview.getDeclaredField("ghosts"); ghosts.setAccessible(true);
return Map.of("face", String.valueOf(face.get(null)), "ghosts", ((List<?>)ghosts.get(null)).size());
` }),
});

test("resident cards across walls and ghosts through a gripped box face", async ({}, testInfo) => {
  test.setTimeout(BUDGET.heavy);
  const run = new VisualRun("card-occlusion");
  const id = newRunId();
  let pair: E2EPair | undefined;
  try {
    pair = await launchPair({ config: CONFIG_PATH,
      server: { instance: seededOffThreadServer(`visual-card-occlusion-server-${id}`), trace: tracePath("visual", "card-occlusion-server") },
      client: { instance: `visual-card-occlusion-client-${id}`, trace: tracePath("visual", "card-occlusion-client") },
    });
    const { server, client } = pair;
    const grounded = await waitForGroundedPlayer(server);
    const name = grounded.name;
    const origin = { x: Math.round(grounded.pos.x) + 4, y: Math.round(grounded.pos.y), z: Math.round(grounded.pos.z) + 4 };
    const cmd = (command: string) => world.command(server, command);
    const fill = (a: Vec, b: Vec, block: string) => cmd(`fill ${a.x} ${a.y} ${a.z} ${b.x} ${b.y} ${b.z} ${block}`);
    await cmd(`gamemode creative ${name}`);
    await foundColonyFast(server, client, name, { origin, residentCount: 1 });
    await screen.dismiss(client);
    await cmd("gamerule doDaylightCycle false");
    await cmd("weather clear");
    await cmd("gamerule doWeatherCycle false");

    // Keep the same resident, pose and camera for all three occlusion scenes.
    const p = { x: origin.x, y: origin.y, z: origin.z + 22 };
    await fill({ x: p.x - 12, y: p.y, z: p.z - 12 }, { x: p.x + 12, y: p.y + 9, z: p.z + 6 }, "minecraft:air");
    await fill({ x: p.x - 12, y: p.y - 1, z: p.z - 12 }, { x: p.x + 12, y: p.y - 1, z: p.z + 6 }, "minecraft:smooth_stone");
    await cmd(`execute as @e[type=folkways:resident] run data merge entity @s {NoAI:1b,NoGravity:1b,Invulnerable:1b}`);
    await cmd(`tp @e[type=folkways:resident] ${p.x + 0.5} ${p.y} ${p.z + 0.5} 180 0`);
    const eye = { x: p.x + 0.5, y: p.y + 2.8, z: p.z - 7.5 };
    const facing = cameraLookingAt(eye, { x: p.x + 0.5, y: p.y + 2.8, z: p.z + 0.5 });
    const camera = { dimension: "minecraft:overworld", position: eye, ...facing, fov: 55 };
    await cmd(`tp ${name} ${p.x + 0.5} ${p.y} ${p.z - 7.5} ${facing.yaw} ${facing.pitch}`);
    await tick.step(server, 20);
    // Set the camera before probing; shot() then uses this same pose.
    await cameras.set(client, { position: eye, ...facing });
    await frames(client, 20);
    const a = await client.run(CARD, undefined);
    run.note("a-control-samples", a);
    expect(a.hidden).toBe(0);
    await run.shot(client, "a-resident-card-clear", { subject: "Control: the entire resident card is visible", worldState: a, camera });

    // A three-block wall cuts across the card's lower portion; its upper corners remain clear.
    const wallMin = { x: p.x - 4, y: p.y, z: p.z - 3 };
    const wallMax = { x: p.x + 4, y: p.y + 2, z: p.z - 3 };
    await fill(wallMin, wallMax, "minecraft:stone_bricks");
    await tick.sprint(server, 3);
    await cameras.set(client, { position: eye, ...facing });
    await frames(client, 30);
    const b = await client.run(CARD, undefined);
    run.note("b-partial-samples", b);
    expect(b.hidden).toBeGreaterThan(0);
    expect(b.hidden).toBeLessThan(5);
    await run.shot(client, "b-resident-card-partial-wall", { subject: "Partial occlusion: the whole card must draw over the stone-brick wall", worldState: { ...b, wallMin, wallMax }, camera });

    await fill(wallMin, { ...wallMax, y: p.y + 6 }, "minecraft:stone_bricks");
    await tick.sprint(server, 3);
    await cameras.set(client, { position: eye, ...facing });
    await frames(client, 30);
    const c = await client.run(CARD, undefined);
    run.note("c-hidden-samples", c);
    expect(c.hidden).toBe(5);
    await run.shot(client, "c-resident-card-full-wall", { subject: "Full occlusion: no resident card should appear through the tall wall", worldState: c, camera });
    await cameras.reset(client);

    // Separate small scene: empty 5x4x5 editable box, three ghosts two blocks behind its north face.
    const box = { x: origin.x + 24, y: origin.y, z: origin.z + 20 };
    await fill({ x: box.x - 5, y: box.y, z: box.z - 9 }, { x: box.x + 9, y: box.y + 7, z: box.z + 9 }, "minecraft:air");
    await fill({ x: box.x - 5, y: box.y - 1, z: box.z - 9 }, { x: box.x + 9, y: box.y - 1, z: box.z + 9 }, "minecraft:smooth_stone");
    await setBookGesture(server, client, name, "box");
    const max = { x: box.x + 4, y: box.y + 3, z: box.z + 4 };
    await cmd(`setblock ${box.x} ${box.y} ${box.z} minecraft:glass`);
    await cmd(`setblock ${max.x} ${max.y} ${max.z} minecraft:glass`);
    await aimAndWorldLeftClick(server, client, name, box);
    await tick.sprint(server, 3);
    await aimAndWorldLeftClick(server, client, name, max);
    await tick.sprint(server, 3);
    await cmd(`setblock ${box.x} ${box.y} ${box.z} minecraft:air`);
    await cmd(`setblock ${max.x} ${max.y} ${max.z} minecraft:air`);
    const preview = await client.run(PREVIEW, box);
    const dEye = { x: box.x + 2.5, y: box.y + 1.62, z: box.z - 7.5 };
    const dFacing = cameraLookingAt(dEye, { x: box.x + 2.5, y: box.y + 1.62, z: box.z + 2.5 });
    await cmd(`tp ${name} ${dEye.x} ${box.y} ${dEye.z} ${dFacing.yaw} ${dFacing.pitch}`);
    await tick.sprint(server, 5);
    await frames(client, 30);
    const d = await client.run(FACE, undefined);
    expect(d.face).toBe("north");
    expect(d.ghosts).toBe(3);
    run.note("d-preview", { ...preview, ...d });
    await run.shot(client, "d-ghosts-through-gripped-face", { subject: "Oak, brick and diamond ghosts remain visible behind the translucent white north face", worldState: { ...preview, ...d, eye: dEye }, hard: true });
    run.expectTaken(["a-resident-card-clear", "b-resident-card-partial-wall", "c-resident-card-full-wall", "d-ghosts-through-gripped-face"]);
  } catch (error) {
    pair?.failing(error);
    throw error;
  } finally {
    await pair?.teardown(testInfo);
  }
});
