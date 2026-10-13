import { test } from "@playwright/test";
import { defineTask, javaTask, screen, tick, world } from "@izakyl/blockwright-minecraft";
import { waitForGroundedPlayer } from "../shared/colony/colony-founding";
import { runColonyTask } from "../shared/colony/colony-tasks";
import { BUDGET } from "../shared/harness/budgets";
import { frames, safeAction, safeScreen, screenName, tryCommand } from "../shared/harness/bw-helpers";
import { foundPromoColonyFast } from "./fast-setup";
import { CURSOR, holdKey, KEY, TOPS, View, type Cell, type Drawing, type Vec } from "./overhead-view";
import { cinematicWorld, launchPromoPair, promoClip, sleepMs, take, type PromoPair } from "./promo-kit";
import { FILM_LOOK, OVERHEAD_LENS, MINIATURE_LOOK, shaderEvidence } from "../shared/capture/shaders";

const LAND_CONFIG = "blockwright.toml#promo-land";

type Shot = {
  pattern: string;
  /**
   * Where the view from above stands for the whole shot: the spot in the middle of the picture, the heading, the
   * distance and the tilt. It is brought there before the film runs and is not moved again.
   */
  camera: { centre: Vec; yaw: number; off: number; pitch: number };
  /** What is drawn and raised, one after another, each with other settings and all of them in the picture. */
  drawings: Drawing[];
  /** Cells no drawing is on whose trees stand between the camera and one that is: they are felled too. */
  fell?: Cell[];
  /**
   * For what is raised under the ground: once all is drawn the camera backs off for so many seconds, tilts to look
   * down, sinks so many notches of the wheel into the rock and tilts up again to pitch, to look at the works from
   * inside the hill. The rock between these corners is made solid beforehand, so that no cave of the land's own
   * shows among them.
   */
  under?: { solid: { min: Cell; max: Cell }; back: number; down: number; pitch: number };
};

// One shot to a pattern, each in a world of its own and on the ground of the promo-land world that suits it. The
// drawings of a shot lie side by side across the picture or one behind another in it, within the band the lens
// keeps sharp. Every site was raised here once to see that its pattern takes the ground and that the picture
// holds them all.
const SHOTS: Shot[] = [
  // Where the cliff throws a spur out onto the plain, seen from the south-east: the first road goes through the spur
  // in a tunnel, its two middle points sunk into the hill to the height the road is to run at; the second winds
  // over the meadow under it, the third is an avenue across the open plain. The
  // farthest is drawn first: what is raised nearer the camera would stand between the mouse and the ground behind.
  { pattern: "road", camera: { centre: { x: 40, y: 70, z: -90 }, yaw: 135, off: 62, pitch: 48 }, drawings: [
    { gesture: "line", cells: [[22, -62], [14, -78], [14, -112], [30, -128]], lift: [undefined, 72, 73, undefined] },
    { gesture: "line", cells: [[38, -60], [48, -76], [38, -92], [48, -108], [38, -124]], knobs: [{ key: "width", value: 3 },
      { key: "surface", toggle: ["minecraft:andesite", "minecraft:cobblestone", "minecraft:polished_andesite", "minecraft:bricks"] },
      { key: "spacing", value: 6 }] },
    { gesture: "line", cells: [[62, -68], [62, -96], [70, -126]], knobs: [{ key: "width", value: 9 }, { key: "spacing", value: 8 }] },
  ] },
  // The level top of the country east of the plain, where a walk needs no steps.
  { pattern: "arcade", camera: { centre: { x: 181, y: 76, z: -150 }, yaw: 180, off: 62, pitch: 50 }, drawings: [
    // The farthest first: a roof raised nearer the camera would stand between the mouse and the ground behind it.
    { gesture: "line", cells: [[158, -172], [186, -172], [206, -162]] },
    { gesture: "line", cells: [[158, -150], [204, -150]], knobs: [{ key: "roof", option: "shed" }, { key: "pitch", option: "steep" }] },
    { gesture: "line", cells: [[158, -132], [204, -132]],
      knobs: [{ key: "width", value: 7 }, { key: "bay", value: 6 }, { key: "height", value: 5 }, { key: "pavilions", tick: true }] },
  ] },
  // A hillock among the eastern ponds, seen from the south-west and from low enough to look under the bridges: one
  // line crosses a pond on a viaduct, high over the water for its piers and arches to show, and comes down into the
  // hill by a tunnel, its last point clicked on the hilltop (the far slope is out of the mouse's sight) and sunk; the next bends twice on its way over the nearer pond; the last branches off it, curving in
  // from the south to end along the track already laid, where the two join at a switch.
  { pattern: "railway", camera: { centre: { x: 380, y: 66, z: 68 }, yaw: 225, off: 70, pitch: 36 }, drawings: [
    { gesture: "line", cells: [[334, 46], [372, 46], [422, 46]], lift: [70, 70, 64], exact: true },
    { gesture: "line", cells: [[350, 80], [370, 80], [386, 96], [422, 96]], lift: [68, 68, 68, 68], exact: true,
      knobs: [{ key: "ballast", toggle: ["minecraft:tuff", "minecraft:gravel"] },
        { key: "masonry", toggle: ["minecraft:bricks", "minecraft:stone_bricks"] }] },
    { gesture: "line", cells: [[382, 114], [400, 96], [414, 96]], lift: [68, 68, 68], onto: [undefined, 69.1, 69.1],
      exact: true },
  // From this low a tree some way toward the camera stands between the mouse and a cell: those are felled as well.
  ], fell: toward([[334, 46], [372, 46], [422, 46], [350, 80], [370, 80], [386, 96], [422, 96], [382, 114], [400, 96],
    [414, 96]], -1, 1) },
  // The plain under the cliff, the walls running along its foot, each with a bend or two in it.
  { pattern: "city_wall", camera: { centre: { x: 82, y: 74, z: -145 }, yaw: 90, off: 70, pitch: 52 }, drawings: [
    { gesture: "line", cells: [[50, -178], [50, -144], [58, -110]],
      knobs: [{ key: "height", value: 12 }, { key: "thickness", value: 7 }, { key: "spacing", value: 30 }] },
    { gesture: "line", cells: [[72, -178], [72, -146], [84, -134], [84, -112]] },
    { gesture: "line", cells: [[110, -178], [110, -140], [102, -116]],
      knobs: [{ key: "height", value: 5 }, { key: "spacing", value: 0 }, { key: "gate", tick: true }] },
  ], fell: [[81, -172], [78, -175], [84, -169]] },
  // A bank that falls ten blocks to the south, toward the camera.
  { pattern: "terraced_field", camera: { centre: { x: 294, y: 71, z: -116 }, yaw: 180, off: 50, pitch: 48 }, drawings: [
    { gesture: "box", cells: [[250, -128], [276, -100]] },
    { gesture: "box", cells: [[282, -126], [304, -102]], knobs: [{ key: "rise", value: 2 }, { key: "spacing", value: 4 }] },
    { gesture: "box", cells: [[312, -142], [338, -118]], knobs: [{ key: "rise", value: 3 }, { key: "fenced", tick: true }] },
  ] },
  // The foot of the cliff, cut into from the plain.
  { pattern: "quarry", camera: { centre: { x: 30, y: 78, z: -131 }, yaw: 90, off: 52, pitch: 45 }, drawings: [
    { gesture: "box", cells: [[22, -170], [40, -152]] },
    { gesture: "box", cells: [[22, -140], [40, -122]], knobs: [{ key: "depth", value: 8 }] },
    { gesture: "box", cells: [[16, -108], [34, -92]], knobs: [{ key: "depth", value: 0 }] },
  ] },
  // The meadow where the cliff comes down to the lake, the mine mouths in a row under it.
  { pattern: "mine", camera: { centre: { x: 14, y: 71, z: -44 }, yaw: 90, off: 34, pitch: 45 }, drawings: [
    { gesture: "box", cells: [[6, -72], [18, -60]] },
    { gesture: "box", cells: [[8, -50], [20, -38]], knobs: [{ key: "level", option: "shallow" }] },
    { gesture: "box", cells: [[10, -28], [22, -16]], knobs: [{ key: "level", option: "copper" },
      { key: "timber", toggle: ["minecraft:spruce_log", "minecraft:dark_oak_log"] },
      { key: "planks", toggle: ["minecraft:spruce_planks", "minecraft:dark_oak_planks"] }] },
  ], under: { solid: { min: [-200, -260], max: [200, 170] }, back: 2.2, down: 10, pitch: 0 } },
  // The north shore of the lake, seen from over the water.
  { pattern: "dock", camera: { centre: { x: 50, y: 63, z: 10 }, yaw: 180, off: 44, pitch: 45 }, drawings: [
    { gesture: "line", cells: [[24, -6], [24, 18]] },
    { gesture: "line", cells: [[40, -7], [40, 20]],
      knobs: [{ key: "width", value: 5 }, { key: "head", option: "l" }, { key: "shelter", tick: true }] },
    { gesture: "line", cells: [[56, -5], [56, 18]], knobs: [{ key: "width", value: 7 }, { key: "head", option: "square" }] },
    { gesture: "line", cells: [[72, 0], [72, 24]], knobs: [{ key: "head", option: "none" }, { key: "shelter", tick: true }] },
  ] },
  // Two ponds side by side among the eastern meadows, each narrow enough to span: the second bridge crosses its
  // pond cornerwise.
  { pattern: "arch_bridge", camera: { centre: { x: 322, y: 63, z: 44 }, yaw: 180, off: 46, pitch: 50 }, drawings: [
    { gesture: "line", cells: [[295, 58], [321, 58]], lift: [64, 64], knobs: [{ key: "width", value: 3 }] },
    { gesture: "line", cells: [[314, 30], [290, 46]], lift: [64, 64] },
    { gesture: "line", cells: [[325, 34], [354, 34]], lift: [65, 65], knobs: [{ key: "width", value: 9 },
      { key: "arch", toggle: ["minecraft:stone_bricks", "minecraft:sandstone"] },
      { key: "deck", toggle: ["minecraft:smooth_stone", "minecraft:polished_granite"] }] },
  ] },
];

/** The cells five and ten blocks from each of these along a heading, the way the camera lies from them. */
function toward(cells: Cell[], dx: number, dz: number): Cell[] {
  return cells.flatMap(([x, z]): Cell[] => [[x + 5 * dx, z + 5 * dz], [x + 10 * dx, z + 10 * dz]]);
}

for (const shot of SHOTS) {
  test(`the ${shot.pattern} pattern from above: ${shot.drawings.length} drawings raised in one still picture`, async ({}, testInfo) => {
    test.setTimeout(BUDGET.promo);
    const scope = `overhead-${shot.pattern}`;
    const run = take(scope);
    let pair: PromoPair | undefined;
    try {
      pair = await launchPromoPair(scope, { config: LAND_CONFIG,
        look: { options: { ...FILM_LOOK, ...MINIATURE_LOOK }, patches: OVERHEAD_LENS } });
      const { server, client, clientInstance } = pair;
      const player = (await waitForGroundedPlayer(server)).name;
      await tryCommand(server, `op ${player}`);
      await cinematicWorld(server);
      await world.command(server, "time set 6500");

      // The player stands behind the camera, under the bottom edge of the picture, with the chest the colony is
      // founded on beside them.
      const { centre, yaw, off, pitch } = shot.camera;
      const heading = yaw * Math.PI / 180, back = Math.cos(pitch * Math.PI / 180) * off + 10;
      const stand = { x: Math.round(centre.x + Math.sin(heading) * back), z: Math.round(centre.z - Math.cos(heading) * back) };
      const chest = { x: stand.x + 2, z: stand.z };
      const cells = [stand, chest, ...[...shot.drawings.flatMap(drawing => drawing.cells), ...(shot.fell ?? [])].map(([x, z]) => ({ x, z }))];
      // In creative the book raises a pattern at once, and the player comes to no harm on the way down to the ground.
      await world.command(server, `gamemode creative ${player}`);
      await world.command(server, `tp ${player} ${stand.x + 0.5} 200 ${stand.z + 0.5} ${yaw} 14`);
      await tick.sprint(server, 20);
      const surveyed = await runColonyTask(server, TOPS, { cells });
      const tops = new Map(cells.map((cell, index) => [`${cell.x},${cell.z}`, Number((surveyed.tops as number[])[index])]));
      const top = (cell: { x: number; z: number }) => tops.get(`${cell.x},${cell.z}`)!;
      const ground = ([x, z]: Cell): Vec => ({ x: x + 0.5, y: top({ x, z }) + 1, z: z + 0.5 });
      run.note("ground", { stand, felled: surveyed.felled });

      if (shot.under) run.note("solid", await runColonyTask(server, SOLID, shot.under.solid));
      await world.command(server, `setblock ${chest.x} ${top(chest) + 1} ${chest.z} minecraft:chest`);
      await world.command(server, `tp ${player} ${stand.x + 0.5} ${top(stand) + 1} ${stand.z + 0.5} ${yaw} 14`);
      await world.command(server, `item replace entity ${player} weapon.mainhand with folkways:colony_book`);
      const founded = await foundPromoColonyFast(server, player, [{ x: chest.x, y: top(chest) + 1, z: chest.z }]);
      const colony_id = founded.colony_id;
      await tryCommand(server, "kill @e[type=minecraft:item]");
      await tick.sprint(server, 100);

      await safeAction(() => screen.dismiss(client));
      await client.run(CURSOR, {});
      await safeAction(() => frames(client, 30));
      await sleepMs(3_000);

      // The view is brought to where it stays before the film runs, with the game's time running for its keys.
      const view = new View(client, ground);
      await tick.freeze(server, false);
      await holdKey(client, KEY.V);
      await sleepMs(1_500);
      const open = screenName(await safeScreen(client));
      if (!open.includes("OverheadScreen")) throw new Error(`the view from above did not open (${open})`);
      await view.goTo(centre, yaw, off, pitch);
      await view.goTo(centre, yaw, off, pitch);
      await view.park();
      const spots = shot.drawings.flatMap(drawing => drawing.cells.map(ground));
      const pose = await view.pose(spots);
      run.note("pose", pose);
      const outside = spots.filter((_, index) => {
        const at = pose.at[index];
        return !(at.x > 12 && at.x < pose.width - 12 && at.y > 44 && at.y < pose.height - 12);
      });
      if (outside.length) throw new Error(`the picture does not hold ${JSON.stringify(outside)} from ${JSON.stringify(pose)}`);
      await tick.freeze(server, true);
      await sleepMs(2_000);

      const stamps: Array<{ start: number; raised: number; end: number }> = [];
      const said: any[] = [];
      let started = 0;
      const now = () => Math.round((Date.now() - started) / 100) / 10;
      let gates: any;
      try {
        gates = await promoClip(run, client, server, shot.pattern, {
          subject: `From above, in creative, the camera still: ${shot.drawings.length} drawings handed to the `
            + `${shot.pattern} pattern with other settings each, and each raised at once`,
          worldState: { colony_id, pattern: shot.pattern, camera: shot.camera, drawings: shot.drawings },
          seconds: 1,
          during: async () => {
            started = Date.now();
            await sleepMs(800);
            for (const [index, drawing] of shot.drawings.entries()) {
              const start = now();
              said.push(await view.drawn(shot.pattern, drawing, 0.6));
              const raised = now();
              // A look at what was raised, the mouse out of its way.
              await view.park();
              const last = index === shot.drawings.length - 1;
              await sleepMs(last && !shot.under ? 3_500 : 1_600);
              if (last && shot.under) {
                // Out of a spectator's eyes the game draws what lies behind the rock the camera is in.
                await world.command(server, `gamemode spectator ${player}`);
                await view.fly([KEY.S], shot.under.back);
                await view.turn(0, 80 - pitch, 900);
                await view.dolly(shot.under.down, 200);
                await sleepMs(400);
                await view.turn(0, shot.under.pitch - 80, 1_800);
                await view.park();
                await sleepMs(4_500);
              }
              stamps.push({ start, raised, end: now() });
            }
          },
        });
      } finally {
        run.note("shot", { name: shot.pattern, ...gates, drafts: stamps, said });
      }
      await holdKey(client, KEY.V);
      run.note("shaders", shaderEvidence(clientInstance));
    } catch (error) {
      pair?.failing(error);
      throw error;
    } finally {
      await pair?.teardown(testInfo);
    }
  });
}

// Makes the rock between two corners solid, from the bottom of the world to a little under the ground: every cave,
// pool and pocket of the land's own is filled with stone.
const SOLID = defineTask<Record<string, any>, Record<string, any>>({
  name: "folkways.promo.overhead-solid",
  side: "server",
  timeoutMs: 300_000,
  source: javaTask({
    imports: ["net.minecraft.core.BlockPos", "net.minecraft.world.level.block.Blocks", "net.minecraft.world.level.levelgen.Heightmap"],
    body: `
var level=ctx.server().overworld();var args=Args.of(ctx);
var min=args.list("min");var max=args.list("max");int filled=0;
var at=new BlockPos.MutableBlockPos();
for(int x=((Number)min.get(0)).intValue();x<=((Number)max.get(0)).intValue();x++)
for(int z=((Number)min.get(1)).intValue();z<=((Number)max.get(1)).intValue();z++){
  level.getChunk(x>>4,z>>4);
  int top=level.getHeight(Heightmap.Types.OCEAN_FLOOR,x,z)-5;
  for(int y=level.getMinBuildHeight()+1;y<=top;y++){
    at.set(x,y,z);var state=level.getBlockState(at);
    if(state.isSolidRender(level,at))continue;
    level.setBlock(at,(y<4?Blocks.DEEPSLATE:Blocks.STONE).defaultBlockState(),2|16);filled++;
  }
}
return Sync.stamp(Map.of("ok",true,"filled",filled));
`,
  }),
});
