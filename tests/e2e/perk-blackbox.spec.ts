import { expect, test } from "@playwright/test";
import { reflect, tick, world } from "@izakyl/blockwright-minecraft";
import {
  foundColonyFast,
  foundingBox,
  optInViaBook,
  waitForGroundedPlayer,
  type Vec,
} from "../shared/colony/colony-founding";
import {
  countInResidentPacks,
  maxInAnySinglePack,
  mustCommand,
  prepareSafeWalkwayOn,
  safeWalkwayBox,
} from "../shared/ui/world-ui";
import { setMaintainDemandViaUI } from "../shared/colony/membership-optin";
import {
  anyResidentEarnedPerkXp,
  buildPen,
  countAnimalsInPen,
  createPastureZoneViaPanel,
  grantPerkToAllResidents,
  pastureZoneFailure,
  penCenter,
  penLayout,
} from "../shared/colony/pasture-tools";
import { launchPair, type E2EPair } from "../shared/harness/pair";
import { ensureOut, tracePath } from "../shared/harness/out-paths";
import { seededServer } from "../shared/harness/mod-settings";
import { BUDGET } from "../shared/harness/budgets";
import { sampleWhileSprinting, settledAll, unwrapSettled } from "../shared/harness/tick-tools";
import { commandPos, tryCommand } from "../shared/harness/bw-helpers";
import { newRunId } from "../shared/harness/run-id";
import { cover, twins } from "../shared/harness/stage";

const RESIDENT_COUNT = 3;

const SADDLE = "minecraft:saddle";
// A pack is eighteen cells, all of them for goods but those its tools and its food lie in: so at most eighteen.
const BASE_PACK_SLOTS = 18;
const BASE_PLANNABLE = 18;
const SADDLE_STOCK = 24;
const CHEST_SLOTS = 27;

const PASTURE_TARGET = 2;

const BEEF = "minecraft:beef";
const LEATHER = "minecraft:leather";
const VANILLA_BEEF_MAX = 3;
const BUTCHER_RANK = 3;

const BLOCKED_TICKS = 6_000;
const DELIVER_TICKS = 4_000;
const CULL_TICKS = 8_000;
const BURST = 20;
const KILL_BURST = 20;

for (const stage of twins()) test(stage.title("perk system end-to-end: carrying capacity and occupation-specific husbandry bonuses"), async ({}, testInfo) => {
  test.setTimeout(BUDGET.heavy);
  ensureOut("e2e");
  const runId = newRunId();
  const serverTrace = tracePath("e2e", stage.name("perk-server"));
  const clientTrace = tracePath("e2e", stage.name("perk-client"));

  let pair: E2EPair | undefined;
  let server: any;
  let client: any;

  try {
    const clientInstance = stage.name(`perk-client-${runId}`);
    const serverInstance = stage.name(`perk-server-${runId}`);
    pair = await launchPair({
      config: stage.config,
      server: { trace: serverTrace, instance: seededServer(serverInstance) },
      client: { trace: clientTrace, instance: clientInstance },
      snapshot: () => ({ containers: [], entityTypes: ["folkways:resident", "minecraft:cow", "minecraft:item"] }),
    });
    ({ server, client } = pair);

    const grounded = await waitForGroundedPlayer(server);
    const playerName = grounded.name;
    await mustCommand(server, `gamemode survival ${playerName}`);

    const origin: Vec = {
      x: Math.round(grounded.pos.x) + 4,
      y: Math.round(grounded.pos.y),
      z: Math.round(grounded.pos.z) + 4,
    };
    const sourceChest: Vec = { x: origin.x - 7, y: origin.y, z: origin.z - 3 };
    const targetChest: Vec = { x: origin.x - 7, y: origin.y, z: origin.z - 13 };
    // The blocks the chests are: what commands and container reads address.
    await stage.lay(server, cover(foundingBox(origin), safeWalkwayBox(origin, sourceChest, targetChest)));
    const sourceAt = stage.at(sourceChest);
    const targetAt = stage.at(targetChest);
    await prepareSafeWalkwayOn(server, stage, origin, sourceChest, targetChest);

    const founding = await foundColonyFast(server, client, playerName, { origin, residentCount: RESIDENT_COUNT, stage });
    const foodAt = stage.at(founding.foodChest);
    expect(founding.residents, `residents=${founding.residents} (expected ${RESIDENT_COUNT})`).toBeGreaterThanOrEqual(RESIDENT_COUNT);
    await grantPerkToAllResidents(server,
      `{global:{level:99,owned:[]},vocations:{"folkways:herding":{level:99,owned:[]}}}`);

    const pen = penLayout(origin, 8, 3);
    await buildPen(server, pen, stage);
    const zone = await createPastureZoneViaPanel(server, client, playerName, founding.standBlock, pen, {
      animal: "cow",
      target: PASTURE_TARGET,
    }, stage);
    expect(zone.created, pastureZoneFailure(zone, "cow", PASTURE_TARGET)).toBe(true);
    for (const spot of [
      { x: pen.min.x + 0.5, z: pen.min.z + 0.5 },
      { x: pen.max.x + 0.5, z: pen.min.z + 0.5 },
      { x: pen.min.x + 0.5, z: pen.max.z + 0.5 },
    ]) {
      await mustCommand(server, `summon minecraft:cow ${spot.x} ${origin.y} ${spot.z} {Age:0,PersistenceRequired:1b}`);
    }
    await tryCommand(server, `tp ${playerName} ${founding.standBlock.x + 0.5} ${stage.stand(founding.standBlock).y} ${founding.standBlock.z + 1.5} 180 30`);
    await tick.sprint(server, 20);

    const reasons: string[] = [];

    const controlArmed = await waitForHerd(server, pen, PASTURE_TARGET + 1);
    const control = await observeCull(server, pen);
    if (!controlArmed.seen) {
      reasons.push(`the control arm never saw ${PASTURE_TARGET + 1} cows in the pen at once (counted ${controlArmed.cows})`);
    }
    if (!control.killed) {
      reasons.push(
        `the surplus cow in the control arm was never culled (${CULL_TICKS} ticks): the culling chain itself is broken, so the perk arm means nothing. `
        + `Samples: ${JSON.stringify(control.samples.slice(0, 14))}`,
      );
    }
    if (control.peak_beef < 1) {
      reasons.push(
        `after the control arm's cull no pack holds ${BEEF} (the carcass drops should go straight into the butcher's pack via CullNode): `
        + `this arm measured nothing, so the >=${VANILLA_BEEF_MAX + 1} check below proves nothing. `
        + `Cull confirmed at tick=${control.kill_tick} (needs the cow count to drop to <=${PASTURE_TARGET} and beef in a pack), `
        + `then watched another ${6 * KILL_BURST} ticks. Samples: `
        + `${JSON.stringify(control.samples.slice(0, 14))}`,
      );
    }
    if (control.peak_beef > VANILLA_BEEF_MAX) {
      reasons.push(
        `one cow in the control arm gave ${control.peak_beef} ${BEEF}, above the vanilla loot table cap of ${VANILLA_BEEF_MAX}: `
        + `so the perk arm's >=${VANILLA_BEEF_MAX + 1} is no longer a number only the perk bonus can reach`,
      );
    }

    await settleHerd(server, pen);
    await clearCarcassGoods(server);
    await normalizeHerd(server, pen, origin.y);
    await grantPerkToAllResidents(server,
      `{vocations:{"folkways:herding":{xp:192,level:${BUTCHER_RANK},owned:[{id:"herding_butcher",rank:${BUTCHER_RANK}}]}}}`);
    await tick.sprint(server, 20);
    await normalizeHerd(server, pen, origin.y);
    await clearCarcassGoods(server);

    const perkedArmed = await armSurplusCow(server, pen, { x: pen.max.x + 0.5, y: origin.y, z: pen.max.z + 0.5 });
    const perked = await observeCull(server, pen);

    if (!perkedArmed.seen) {
      reasons.push(
        `the perk arm never saw the surplus cow in the pen (counted ${perkedArmed.cows}, expected ${PASTURE_TARGET + 1}): `
        + `the yield check below would be vacuous - it would only show the cow was never seen`,
      );
    }
    if (!perked.killed) {
      reasons.push(`the surplus cow in the perk arm was never culled (${CULL_TICKS} ticks)`);
    }
    expect(perked.peak_beef, "a culled cow still yields ordinary beef").toBeGreaterThan(0);
    expect(perked.peak_beef, "butcher III adds at most half again to ordinary carcass products").toBeLessThanOrEqual(Math.ceil(VANILLA_BEEF_MAX * 1.5));
    const stat = await reflect.query(server, {
      root: { kind: "static", className: "io.github.izakyl.folkways.plugins.pasture.PastureContent", path: "CARCASS_YIELD" },
      select: { id: "id().toString()" }, handles: true, limits: { maxDepth: 0, maxNodes: 8 },
    });
    expect(stat.tree?.handle, "husbandry yield stat should be available").toBeTruthy();
    for (const resident of await readResidentPerks(server)) {
      const chance = await reflect.invoke(server, {
        target: { kind: "entity", uuid: resident.uuid }, path: "effects()", method: "value",
        args: [{ $handle: stat.tree!.handle }], argTypes: ["io.github.izakyl.folkways.effect.Stat"], returnType: "double",
      });
      expect(Number(chance.returned), "butcher III adds half again to ordinary husbandry products").toBeCloseTo(0.5);
    }

    await clearCarcassGoods(server);

    await tryCommand(server, `setblock ${commandPos(sourceAt)} minecraft:chest{Items:[]}`);
    await tryCommand(server, `setblock ${commandPos(targetAt)} minecraft:chest{Items:[]}`);
    await optInViaBook(server, client, playerName, sourceChest, "chest", stage);
    await optInViaBook(server, client, playerName, targetChest, "chest", stage);
    await setMaintainDemandViaUI(server, client, playerName, targetChest, SADDLE, SADDLE_STOCK, stage);
    await tryCommand(server, `tp ${playerName} ${founding.standBlock.x + 0.5} ${stage.stand(founding.standBlock).y} ${founding.standBlock.z + 1.5} 180 30`);
    await stockSaddles(server, sourceAt);

    const packAtStart = await maxInAnySinglePack(server, SADDLE);
    const stockAtStart = await countInChest(server, sourceAt, SADDLE);
    expect(
      { stocked: stockAtStart, in_target: await countInChest(server, targetAt, SADDLE), in_packs: packAtStart.max },
      "phase A did not start from a clean slate",
    ).toEqual({ stocked: SADDLE_STOCK, in_target: 0, in_packs: 0 });

    const packsBefore = await readResidentPerks(server);
    expect(
      packsBefore.filter((entry) => entry.slots !== BASE_PACK_SLOTS),
      `before phase A some resident's pack is not ${BASE_PACK_SLOTS} slots: a pack perk leaked into the control arm, `
      + `so the peak measured below says nothing about the no-perk cap`,
    ).toEqual([]);

    const blocked = await observeCarry(server, targetAt, BLOCKED_TICKS);
    expect(
      blocked.delivered,
      `phase A delivered only ${blocked.delivered} of ${SADDLE_STOCK} saddles to the target chest in ${BLOCKED_TICKS} ticks: `
      + `${blocked.delivered === 0
        ? "the hauling chain itself is broken, so the wall the perk phase has to clear was never reached"
        : "the run did not finish, so the measured peak may just be 'not hauled that many yet' rather than the carry cap"}.`
      + ` Samples: ${JSON.stringify(blocked.samples.slice(0, 12))}`,
    ).toBe(SADDLE_STOCK);
    expect(
      blocked.peak_in_one_pack,
      `the whole order was delivered, yet no pack ever held a saddle: sampling misses the window between "take" and "put", `
      + `or the goods never passed through a pack. Samples: ${JSON.stringify(blocked.samples.slice(0, 12))}`,
    ).toBeGreaterThan(0);
    expect(
      blocked.peak_in_one_pack,
      `without the perk the fullest pack held ${blocked.peak_in_one_pack} saddles at once, beyond the`
      + ` ${BASE_PLANNABLE} slots a ${BASE_PACK_SLOTS}-slot pack can plan: so "going past it" later cannot prove the pack grew. `
      + ` Samples: ${JSON.stringify(blocked.samples.slice(0, 12))}`,
    ).toBeLessThanOrEqual(BASE_PLANNABLE);

    await emptyPacks(server);
    await emptyChest(server, targetAt);
    await tick.sprint(server, 20);

    const beforePerk = await whereAreSaddles(server, sourceAt, targetAt, foodAt);
    expect(
      beforePerk,
      `phase B wants to grant the perk while the colony has no saddles to split yet, but saddles are here: `
      + `${JSON.stringify(beforePerk)}. As long as one reachable saddle remains, hauling splits its legs at the old pack width first, `
      + `so the peak measured below can only be the old ${BASE_PLANNABLE} slots, regardless of whether the pack grew`,
    ).toEqual({ source: 0, target: 0, packs: 0, food_chest: 0 });

    await grantPerkToAllResidents(server, `{vocations:{"folkways:hauling":{xp:480,level:2,owned:[{id:"hauling_heavy",rank:2}]}}}`);
    await tick.sprint(server, 20);

    const perkedPacks = await readResidentPerks(server);
    const narrowest = perkedPacks.reduce(
      (least, entry) => Math.min(least, typeof entry.slots === "number" ? entry.slots : -1),
      Number.POSITIVE_INFINITY,
    );
    expect(
      narrowest,
      `after granting hauling_heavy rank 2, the resident with the narrowest pack still has only ${narrowest} slots`
      + ` (baseline ${BASE_PACK_SLOTS}): Pack.applyCapacityMultiplier was not driven by this perk, `
      + `so the run below cannot possibly haul more than baseline. Pack slots and perks: ${JSON.stringify(perkedPacks)}`,
    ).toBeGreaterThan(BASE_PACK_SLOTS);

    await stockSaddles(server, sourceAt);
    const delivered = await observeCarry(server, targetAt, DELIVER_TICKS);
    const perkState = await readResidentPerks(server);
    const where = await whereAreSaddles(server, sourceAt, targetAt, foodAt);

    if (delivered.peak_in_one_pack <= BASE_PLANNABLE) {
      reasons.push(
        `carry per trip: the fullest pack still stops at ${delivered.peak_in_one_pack} saddles (${DELIVER_TICKS} ticks), `
        + `not past the baseline ${BASE_PLANNABLE} slots. Where the saddles are now: ${JSON.stringify(where)}; `
        + `pack slots and perks: ${JSON.stringify(perkState)}; samples: ${JSON.stringify(delivered.samples.slice(0, 12))}`,
      );
    }

    const organic = await anyResidentEarnedPerkXp(server);
    expect(
      organic,
      "after the whole run no resident earned any perk XP from their own work: the organic growth path (active labor -> "
      + "BodyPerks.addXp) is not running, so however well the perk effects are wired, real players can never reach them",
    ).toBe(true);

    expect(reasons, reasons.join(" | ")).toEqual([]);
  } catch (error) {
    pair?.failing(error);
    throw error;
  } finally {
    await pair?.teardown(testInfo);
  }
});

async function stockSaddles(server: any, chest: Vec) {
  for (let slot = 0; slot < SADDLE_STOCK; slot++) {
    await mustCommand(server, `item replace block ${commandPos(chest)} container.${slot} with ${SADDLE}`);
  }
  await tick.sprint(server, 5);
}

async function emptyChest(server: any, chest: Vec) {
  for (let slot = 0; slot < CHEST_SLOTS; slot++) {
    await tryCommand(server, `item replace block ${commandPos(chest)} container.${slot} with minecraft:air`);
  }
  await tick.sprint(server, 5);
}

async function whereAreSaddles(server: any, source: Vec, target: Vec, foodChest: Vec) {
  const [sourceRead, targetRead, packsRead, foodRead] = await settledAll<any>([
    () => countInChest(server, source, SADDLE),
    () => countInChest(server, target, SADDLE),
    () => countInResidentPacks(server),
    () => countInChest(server, foodChest, SADDLE),
  ]);
  return {
    source: unwrapSettled<number>(sourceRead),
    target: unwrapSettled<number>(targetRead),
    packs: unwrapSettled<any>(packsRead).totals[SADDLE] ?? 0,
    food_chest: unwrapSettled<number>(foodRead),
  };
}

async function countInChest(server: any, chest: Vec, itemId: string) {
  const read: { slots?: Array<{ id: string; count: number }> } = await world
    .container(server, chest, { dimension: "minecraft:overworld" })
    .catch(() => ({ slots: [] }));
  let count = 0;
  for (const slot of read.slots ?? []) {
    if (slot?.id === itemId) {
      count += slot.count ?? 1;
    }
  }
  return count;
}

async function emptyPacks(server: any) {
  await tryCommand(server, `execute as @e[type=folkways:resident] run data merge entity @s `
    + `{"neoforge:attachments":{"folkways:body_state":{Pack:{Items:[]}}}}`);
  await tick.sprint(server, 5);
}

async function clearCarcassGoods(server: any) {
  await tryCommand(server, "time set day");
  await emptyPacks(server);
  for (const item of [BEEF, LEATHER]) {
    await tryCommand(server, `kill @e[type=minecraft:item,nbt={Item:{id:"${item}"}}]`);
  }
  await tick.sprint(server, 5);
}

async function observeCarry(server: any, target: Vec, maxTicks: number) {
  const samples: any[] = [];
  let peak = 0;
  let delivered = 0;
  let probedResidents = 0;
  await sampleWhileSprinting(server, {
    maxTicks,
    stepTicks: BURST,
    sampleAtZero: true,
    read: async (ticks) => {
      const [packRead, chestRead] = await settledAll<any>([
        () => maxInAnySinglePack(server, SADDLE),
        () => countInChest(server, target, SADDLE),
      ]);
      const read = unwrapSettled(packRead);
      const inTarget = unwrapSettled(chestRead);
      peak = Math.max(peak, read.max);
      probedResidents = read.resident_count;
      delivered = inTarget;
      const sample = { ticks, in_one_pack: read.max, in_target: inTarget };
      samples.push(sample);
      return sample;
    },
    done: (sample) => sample.in_target >= SADDLE_STOCK,
  });
  return { peak_in_one_pack: peak, delivered, probed_residents: probedResidents, samples };
}

function nearPenVolume(pen: any) {
  const x = pen.min.x - 8;
  const y = pen.min.y - 4;
  const z = pen.min.z - 8;
  return `x=${x},y=${y},z=${z},dx=${pen.max.x + 8 - x},dy=12,dz=${pen.max.z + 8 - z}`;
}

async function settleHerd(server: any, pen: any) {
  let previous = -1;
  for (let ticks = 0; ticks <= 1_200; ticks += 100) {
    await tick.sprint(server, 100);
    const cows = await countAnimalsInPen(server, pen, /cow/);
    if (cows === previous) return { settled: true, ticks, cows };
    previous = cows;
  }
  return { settled: false, ticks: 1_200, cows: previous };
}

async function normalizeHerd(server: any, pen: any, y: number) {
  const center = penCenter(pen, y);
  const evidence: any = {};

  await tryCommand(server, `execute as @e[type=minecraft:cow,${nearPenVolume(pen)}] run tp @s ${center.x} ${center.y} ${center.z}`);
  await tick.sprint(server, 2);

  evidence.before_topup = await countAnimalsInPen(server, pen, /cow/);
  for (let guard = 0; guard < 4; guard++) {
    if ((await countAnimalsInPen(server, pen, /cow/)) >= PASTURE_TARGET) break;
    await mustCommand(server, `summon minecraft:cow ${center.x} ${center.y} ${center.z} {Age:0,PersistenceRequired:1b}`);
    await tick.sprint(server, 4);
  }
  evidence.after_topup = await countAnimalsInPen(server, pen, /cow/);

  await tryCommand(server, `execute as @e[type=minecraft:cow,${nearPenVolume(pen)}] run data merge entity @s {Health:10.0f}`);
  await tick.sprint(server, 4);
  return evidence;
}

async function waitForHerd(server: any, pen: any, want: number) {
  for (let ticks = 0; ticks <= 60; ticks += 2) {
    await tick.sprint(server, 2);
    const cows = await countAnimalsInPen(server, pen, /cow/);
    if (cows >= want) {
      return { seen: true, ticks, cows };
    }
  }
  return { seen: false, ticks: 60, cows: await countAnimalsInPen(server, pen, /cow/) };
}

async function armSurplusCow(server: any, pen: any, at: Vec) {
  await mustCommand(server, `summon minecraft:cow ${at.x} ${at.y} ${at.z} {Age:0,PersistenceRequired:1b}`);
  for (let ticks = 0; ticks <= 40; ticks += 2) {
    await tick.sprint(server, 2);
    const cows = await countAnimalsInPen(server, pen, /cow/);
    if (cows >= PASTURE_TARGET + 1) {
      return { seen: true, ticks_to_register: ticks + 2, cows };
    }
  }
  return { seen: false, ticks_to_register: null, cows: await countAnimalsInPen(server, pen, /cow/) };
}

async function observeCull(server: any, pen: any) {
  const samples: any[] = [];
  let killed = false;
  let killTick: number | null = null;
  let cows = 0;
  let peakBeef = 0;
  let peakLeather = 0;

  const read = async (ticks: number) => {
    const [packsRead, cowsRead] = await settledAll<any>([
      () => countInResidentPacks(server),
      () => countAnimalsInPen(server, pen, /cow/),
    ]);
    const packs = unwrapSettled(packsRead);
    const beef = packs.totals[BEEF] ?? 0;
    const leather = packs.totals[LEATHER] ?? 0;
    peakBeef = Math.max(peakBeef, beef);
    peakLeather = Math.max(peakLeather, leather);
    cows = unwrapSettled(cowsRead);
    samples.push({ ticks, cows, beef, leather });
  };

  for (let ticks = 0; ticks <= CULL_TICKS; ticks += KILL_BURST) {
    if (ticks > 0) await tick.sprint(server, KILL_BURST);
    await read(ticks);
    if (!killed && cows <= PASTURE_TARGET && peakBeef > 0) {
      killed = true;
      killTick = ticks;
      for (let extra = 1; extra <= 6; extra++) {
        await tick.sprint(server, KILL_BURST);
        await read(ticks + extra * KILL_BURST);
      }
      break;
    }
  }

  return {
    killed,
    kill_tick: killTick,
    final_cows: cows,
    peak_beef: peakBeef,
    peak_leather: peakLeather,
    sample_burst_ticks: KILL_BURST,
    samples: samples.slice(0, 80),
  };
}

async function readResidentPerks(server: any) {
  const listed = await world
    .entities(server, { dimension: "minecraft:overworld", types: ["folkways:resident"], limit: 20 })
    .catch(() => ({ entities: [] as world.EntityJson[] }));
  const out: any[] = [];
  for (const resident of listed.entities) {
    const perks = await world
      .entity(server, { uuid: resident.uuid }, { nbtPath: "neoforge:attachments.folkways:body_state.Perks" })
      .then((r) => r?.nbt).catch((e: any) => String(e).slice(0, 120));
    const slots = await reflect.invoke(server, {
      target: { kind: "entity", uuid: resident.uuid },
      path: "pack()",
      method: "getContainerSize",
      returnType: "int",
    }).then((r: any) => r?.returned).catch((e: any) => String(e).slice(0, 120));
    out.push({ uuid: resident.uuid, slots, perks });
  }
  return out;
}
