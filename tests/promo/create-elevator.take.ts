import { defineTask, javaTask, world } from "@izakyl/blockwright-minecraft";
import { expect, test } from "@playwright/test";
import { shaderEvidence } from "../shared/capture/shaders";
import { take, type PromoPair } from "./promo-kit";
import { castColony, fixCamera, openScene, place, readScene, rollUntil, setVitals, shoot } from "./cookery-scene";
import { buildElevator, readElevator } from "./elevator-stage";

// A Create elevator beside a raised sleeping loft. Four worn-out residents on the ground want their beds upstairs; the
// cabin is the only way up, so they walk in, stand on its moving floor while it rises and walk out to bed.

const RISE = 6;
const SECONDS = 50;
const OUTLINES = defineTask<void, any>({
  name: "folkways.promo.elevator-outlines", side: "client", timeoutMs: 20_000,
  source: javaTask({ imports: ["io.github.izakyl.folkways.create.elevator.ElevatorOutlines"], body: `
return ElevatorOutlines.shown().stream().map(outline -> Map.of(
    "entity", outline.entityId(), "cabinHeight", outline.cabin().getYsize(),
    "sweepHeight", outline.sweep().map(box -> box.getYsize()).orElse(0.0),
    "containsCabin", outline.sweep().map(box -> box.inflate(0.05).minmax(outline.cabin()).equals(box.inflate(0.05))).orElse(false)
)).toList();
` }),
});

test("elevator: tired residents ride a Create elevator up to their beds", async ({}, testInfo) => {
  const run = take("create-elevator");
  let pair: PromoPair | undefined;
  try {
    const scene = await openScene("create-elevator");
    pair = scene.pair;
    const { server, at, set, fill } = scene;

    // The loft lies west of the shaft at the cabin's upper stop; nothing but the cabin reaches it.
    const floor = RISE - 1;
    await fill(at(-10, -8, floor), at(-3, -1, floor), "minecraft:spruce_planks");
    for (const [x, z] of [[-10, -8], [-10, -1], [-4, -8], [-4, -1]] as const)
      await fill(at(x, z, 0), at(x, z, floor - 1), "minecraft:stripped_spruce_log[axis=y]");
    await fill(at(-10, -8, floor + 1), at(-10, -1, floor + 1), "minecraft:spruce_fence");
    await fill(at(-10, -1, floor + 1), at(-4, -1, floor + 1), "minecraft:spruce_fence");
    const beds = [-7, -6, -4, -3].map(z => at(-8, z, RISE));
    for (const bed of beds) {
      await set(bed, "minecraft:red_bed[part=foot,facing=west]");
      await set({ ...bed, x: bed.x - 1 }, "minecraft:red_bed[part=head,facing=west]");
    }
    await set(at(-4, -1, RISE + 1), "minecraft:lantern");

    // Behind the camera: the settlers need food in store, the shot does not.
    const rations = at(-5, -30);
    await set(rations, `minecraft:chest[facing=east]{Items:[{Slot:0b,id:"minecraft:cooked_beef",count:64}]}`);
    const cast = await castColony(scene, [...beds, rations], 4, () => ["hauling"]);
    run.note("cast", cast);
    run.note("elevator", await buildElevator(server, scene.name, cast.colony_id, scene.o));
    for (const [i, uuid] of cast.residents.entries()) await place(server, uuid, at(-7 + i * 2, -16));
    run.note("vitals", await setVitals(server, cast.colony_id, cast.residents.map(uuid => ({ uuid, food: 20, tired: 1000 }))));
    // The camera looks at the open north face of the cabin and the loft beside it.
    await fixCamera(scene, at(-5, -22, 9), at(-3, -4, 3), 50);

    await expect.poll(async () => (await scene.client.run(OUTLINES)).length,
      { message: "the held book must render the detected cabin bounds", timeout: 15_000 }).toBeGreaterThan(0);
    const outlines = await scene.client.run(OUTLINES);
    expect(outlines.some((one: any) => one.containsCabin && one.sweepHeight >= one.cabinHeight + RISE - 0.1),
      "the sweep must cover the cabin through both floors").toBe(true);
    run.note("outlines", outlines);
    const sample = async () => ({ ...await readScene(server, cast.colony_id, cast.residents, []),
      lift: await readElevator(server, cast.colony_id, scene.o) });
    const evidence = await shoot(run, scene, "01-elevator",
      "Fixed view of a Create elevator beside a raised sleeping loft: four tired residents walk into the cabin, stand "
      + "on its floor as it rises and walk out to their beds upstairs", SECONDS, 20, sample, { beds });
    run.note("activity", evidence);
    const rode = new Set(evidence.flatMap((one: any) => one.lift.passengers.map((p: any) => p.uuid)));
    const upstairs = Math.max(...evidence.map((one: any) => one.people.filter((p: any) => p.position[1] >= scene.o.y + RISE - 0.5).length));
    const asleep = Math.max(...evidence.map((one: any) => one.people.filter((p: any) => p.action === "sleeping").length));
    run.note("summary", { rode: [...rode], upstairs, asleep });
    expect(rode.size, "residents must ride the cabin on camera").toBeGreaterThanOrEqual(2);
    expect(upstairs, "residents must reach the loft on camera").toBeGreaterThanOrEqual(2);
    expect(asleep, "residents must lie down in the loft beds on camera").toBeGreaterThanOrEqual(2);
    await world.command(server, `item replace entity ${scene.name} weapon.mainhand with minecraft:air`);
    await expect.poll(async () => (await scene.client.run(OUTLINES)).length).toBe(0);
    await world.command(server, `item replace entity ${scene.name} weapon.mainhand with folkways:colony_book`);
    run.note("shaders", shaderEvidence(scene.pair.clientInstance));
  } catch (error) {
    pair?.failing(error);
    throw error;
  } finally {
    await pair?.teardown(testInfo);
  }
});
