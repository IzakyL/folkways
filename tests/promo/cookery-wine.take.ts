import { defineTask, javaTask } from "@izakyl/blockwright-minecraft";
import { expect, test } from "@playwright/test";
import { shaderEvidence } from "../shared/capture/shaders";
import { take, type PromoPair } from "./promo-kit";
import { areaCells, castColony, fixCamera, openScene, place, readScene, rollUntil, setVitals, shoot, stageDining, T } from "./cookery-scene";
import { readTavern, stageTavern } from "./tavern-stage";
import { stageVineyard, growVineyard } from "./vineyard-stage";

// From a planted grape arbor to the native pressing/fermentation chain and a staffed bar.
// One long bar with a stool at every counter; the counters directly store the bottles served to seated residents.

const WINE = T + "wine";
const BAR = [5, 6, 7, 8, 9, 10];
// A resident drinks at most once a quarter day. During the bar shot the two drinkers are let off that wait one after
// the other, so they come in at their own time and the bottles stocked on camera are drunk on camera; no drink, bottle
// or mood is forged.
const THIRSTY = defineTask<{ uuids: string[] }, any>({
  name: "folkways.promo.wine-thirsty", side: "server", timeoutMs: 20_000,
  source: javaTask({ body: `
var args = Args.of(ctx);
int cleared = 0;
for (var id : args.strings("uuids")) {
    var body = ctx.server().overworld().getEntity(UUID.fromString(id));
    if (body != null) { body.getPersistentData().remove("folkways:last_table_drink"); cleared++; }
}
return Map.of("cleared", cleared);
` }),
});

test("wine: trellised grapes become wine served at the bar", async ({}, testInfo) => {
  const run = take("cookery-wine");
  let pair: PromoPair | undefined;
  try {
    const scene = await openScene("cookery-wine");
    pair = scene.pair;
    const { server, at, set, fill } = scene;

    await fill(at(-7, -4, -1), at(4, 3, -1), "minecraft:smooth_sandstone");
    await fill(at(5, -4, -1), at(11, 3, -1), "minecraft:spruce_planks");
    // The stools stand on the south side, so the drinkers face the camera across the bar; the waiter serves from the north.
    const counters = BAR.map(x => at(x, -3));
    for (const counter of counters) {
      await set(counter, T + "bar_counter[facing=south]");
      await set({ ...counter, z: counter.z + 1 }, "kaleidoscope_cookery:chair_spruce[facing=north]");
    }
    const area = { id: "wine-bar", cells: areaCells(scene, 5, -4, 11, 3), tables: [], cabinets: counters, foods: [], drinks: [WINE] };

    // The dorm and its rations stand behind the camera, out of every shot.
    const cast = await castColony(scene, [], 7, i => i < 3 ? ["hauling"] : i < 5 ? ["farming", "hauling"] : [], at(-12, -32));
    run.note("cast", cast);
    const vintners = cast.residents.slice(0, 2), waiter = cast.residents[2], farmers = cast.residents.slice(3, 5), diners = cast.residents.slice(5);
    const vineyard = await stageVineyard(scene, cast.colony_id);
    for (const [i, uuid] of farmers.entries()) await place(server, uuid, at(-14 + i * 3, 6));
    run.note("tavern", await stageTavern(server, scene.name, cast.colony_id, scene.o, vintners, false));
    await place(server, waiter, at(12, -1));
    // The two drinkers wait apart, out at the end of the bar and down by the vineyard path.
    await place(server, diners[0], at(13, -2));
    await place(server, diners[1], at(15.5, 2.5));
    run.note("vitals", await setVitals(server, cast.colony_id, cast.residents.map(uuid => ({ uuid, food: 20, tired: 0 }))));
    run.note("diningSetup", await stageDining(server, cast.colony_id, [area], 200));
    // The winery faces north, so the camera stands on that side. One fixed view holds the vineyard, the winery and the
    // bar for the whole take.
    await fixCamera(scene, at(-3, -21, 10), at(-3, 0, 0), 56);

    const everyone = cast.residents;
    const sample = async () => ({ ...await readScene(server, cast.colony_id, everyone, [area]),
      tavern: await readTavern(server, cast.colony_id, scene.o, vintners) });
    const vineyardTake = await shoot(run, scene, "01-vineyard",
      "Residents raise a trellis post at each corner of the plot, roof it five blocks up, plant grapevines, "
      + "harvest the grapes hanging under the roof and carry them to the winery. "
      + "Native growth is accelerated between samples; no grapes are supplied or harvested by the script.", 140, 30, async () => {
        const growth = await growVineyard(scene, cast.colony_id);
        return { ...await sample(), vineyard: growth };
      }, { vineyard });
    run.note("vineyard", vineyardTake);
    expect(vineyardTake.some((one: any) => one.people.some((p: any) => farmers.includes(p.uuid)
      && p.action === "planting")), "residents must build and plant the vineyard on camera").toBe(true);
    expect(vineyardTake.some((one: any) => one.people.some((p: any) => farmers.includes(p.uuid)
      && p.action === "harvesting")), "residents must harvest grapes on camera").toBe(true);
    expect(vineyardTake.some((one: any) => one.people.some((p: any) => farmers.includes(p.uuid)
      && p.pack.some((stack: string) => /^grapex/.test(stack)))), "a farmer must carry the real grape harvest").toBe(true);

    const brewing = await shoot(run, scene, "02-pressing-and-fermentation",
      "The harvested grapes enter the pressing tub; vintners press juice, transfer buckets to the barrel, "
      + "and begin native fermentation", 65, 20, sample, { area });
    run.note("brewing", brewing);
    const pressed = [...vineyardTake, ...brewing].some((one: any) => one.tavern.tub.fluidAmount > 0 || one.tavern.barrel.fluidAmount > 0);
    expect(pressed, "grapes must be pressed into juice on camera").toBe(true);

    // Keep the real native fermentation and tap bottling on camera before the bar is stocked.
    const bottling = await shoot(run, scene, "03-fermentation-and-bottling",
      "The native barrel ferments the harvested grape juice, then a vintner fills actual bottles from its tap "
      + "and the waiter stocks the bar counters", 150, 40, sample, { area });
    run.note("bottling", bottling);
    const production = [...vineyardTake, ...brewing, ...bottling];
    expect(production.some((one: any) => one.tavern.bottleBlock === WINE),
      "a filled native wine bottle must be visible below the barrel tap").toBe(true);
    expect(production.some((one: any) => one.tavern.workers.some((worker: any) => worker.pack.some((stack: any) => stack.id === WINE))),
      "a resident must collect the wine bottles").toBe(true);
    expect(production.some((one: any) => one.drinks > 0 && one.people.some((p: any) => p.action === "setting_table")),
      "residents must serve the wine to stocked counters on camera").toBe(true);
    const stocked = await rollUntil(server, 40, 600_000, sample, (state: any) => state.drinks >= 4);
    run.note("stocked", stocked.log);
    expect(stocked.ready, "bottled wine must reach the bar counters").toBe(true);
    // Idle residents wander back toward the dorm in the long production shots, so each drinker steps in at an end of
    // the bar as they are let off the wait. The second comes a while into the shot, so the two do not walk up together.
    await place(server, diners[0], at(12.5, -2));
    run.note("thirsty", await server.run(THIRSTY, { uuids: [diners[0]] }));
    const opened = Date.now();
    let late = false;
    const atTheBar = async () => {
      if (!late && Date.now() - opened > 12_000) {
        await place(server, diners[1], at(13.5, 1.5));
        run.note("thirstyLate", await server.run(THIRSTY, { uuids: [diners[1]] }));
        late = true;
      }
      return sample();
    };
    const dining = await shoot(run, scene, "04-drinks-at-the-bar",
      "The waiter keeps the long bar stocked with the new wine while two residents come up to the stools "
      + "one after the other and drink in their spare time", 80, 20, atTheBar, { area });
    run.note("dining", dining);
    const wineOnCamera = [...production, ...dining];
    const drank = new Set(wineOnCamera.flatMap((one: any) => one.people.filter((p: any) => diners.includes(p.uuid)
      && (p.drinking || p.tableDrinks > 0)).map((p: any) => p.uuid)));
    const seatedAtBar = wineOnCamera.some((one: any) => one.people.some((p: any) => diners.includes(p.uuid)
      && p.drinking && p.seated && counters.some(counter =>
        Math.hypot(p.position[0] - counter.x, p.position[2] - counter.z) <= 2)));
    expect(seatedAtBar, "drinking must take place seated at the bar counter").toBe(true);
    run.note("summary", { drank: [...drank], seatedAtBar });
    expect(drank.size, "residents must drink wine at the bar on camera").toBeGreaterThanOrEqual(2);
    run.note("shaders", shaderEvidence(scene.pair.clientInstance));
  } catch (error) {
    pair?.failing(error);
    throw error;
  } finally {
    await pair?.teardown(testInfo);
  }
});
