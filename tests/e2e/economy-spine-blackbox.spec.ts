import { expect, test } from "@playwright/test";
import { tick, world } from "@izakyl/blockwright-minecraft";
import { setMaintainDemandViaUI } from "./membership-optin";
import { foundColonyFast, waitForGroundedPlayer, optInViaBook, type Vec,
} from "./colony-founding";
import { countInResidentPacks } from "./world-ui";
import { launchPair, type E2EPair } from "../shared/pair";
import { ensureOut, tracePath } from "../out-paths";
import { seededServer } from "../shared/mod-settings";
import { BUDGET } from "../shared/budgets";
import { commandPos, tryCommand, waitMatched } from "../shared/bw-helpers";
import { newRunId } from "../shared/run-id";

const CONFIG_PATH = "blockwright.toml";

const RESIDENT_TYPE = "folkways:resident";
const ITEM_TYPE = "minecraft:item";

const SMITH_TEMPLATE = "minecraft:netherite_upgrade_smithing_template";
const SMITH_BASE = "minecraft:diamond_chestplate";
const SMITH_ADDITION = "minecraft:netherite_ingot";
const SMITH_RESULT = "minecraft:netherite_chestplate";
const SMITH_DAMAGE = 137;
const SMITH_BUDGET_TICKS = 6000;

const DOORWAY_DEMAND_ITEM = "minecraft:cobblestone";
const DOORWAY_SOURCE_SLOT = 3;
const DOORWAY_SOURCE_STOCK = 16;
const DOORWAY_OPEN_BUDGET = 2400;
const DOORWAY_ENTER_BUDGET = 1200;
const DOORWAY_SHUT_BUDGET = 400;

test("economy spine: a smithing upgrade runs through the real chain, and a resident works a doorway", async ({}, testInfo) => {
  test.setTimeout(BUDGET.heavy);
  ensureOut("e2e");

  const runId = newRunId();
  let pair: E2EPair | undefined;
  const serverTrace = tracePath("e2e", "economy-spine-server");
  const clientTrace = tracePath("e2e", "economy-spine-client");

  let server: any;
  let client: any;
  let phaseContainers: any[] = [];
  try {
    pair = await launchPair({
      config: CONFIG_PATH,
      server: { trace: serverTrace, instance: seededServer(`economy-spine-server-${runId}`) },
      client: { trace: clientTrace, instance: `economy-spine-client-${runId}` },
      snapshot: () => ({ containers: phaseContainers, entityTypes: [RESIDENT_TYPE, ITEM_TYPE] }),
    });
    ({ server, client } = pair);
    const grounded = await waitForGroundedPlayer(server);
    const playerName = grounded.name;
    await tryCommand(server, `gamemode survival ${playerName}`);

    const origin = { x: Math.round(grounded.pos.x) + 4, y: Math.round(grounded.pos.y), z: Math.round(grounded.pos.z) + 4 };
    const founding = await foundColonyFast(server, client, playerName, { origin, residentCount: 3 });
    await tryCommand(server, `execute positioned ${commandPos(origin)} run kill @e[type=minecraft:fox,distance=..32]`);

    await tryCommand(server, "time set day");
    const smithing = smithingLayout(founding.origin);
    phaseContainers = [smithing.supply, smithing.target];

    await world.command(server, `setblock ${commandPos(smithing.table)} minecraft:smithing_table`);
    await world.command(server, `setblock ${commandPos(smithing.supply)} minecraft:chest{Items:[]}`);
    await world.command(server, `setblock ${commandPos(smithing.target)} minecraft:chest{Items:[]}`);
    await tick.sprint(server, 3);
    await optInViaBook(server, client, playerName, smithing.supply, "chest");
    await optInViaBook(server, client, playerName, smithing.target, "chest");
    await optInViaBook(server, client, playerName, smithing.table, "worksite");

    await world.command(server, `item replace block ${commandPos(smithing.supply)} container.0 with ${SMITH_TEMPLATE} 1`);
    await world.command(server, `item replace block ${commandPos(smithing.supply)} container.1 with ${SMITH_BASE}[minecraft:damage=${SMITH_DAMAGE}] 1`);
    await world.command(server, `item replace block ${commandPos(smithing.supply)} container.2 with ${SMITH_ADDITION} 1`);
    await tick.sprint(server, 5);

    const smithStaged = await readContainerStacks(server, smithing.supply);
    const stagedBase = smithStaged.find((stack) => stack.id === SMITH_BASE);
    expect(
      stagedBase?.damage,
      `setup failed to stage ${SMITH_BASE} as "damage ${SMITH_DAMAGE}"; supply chest read ${JSON.stringify(smithStaged)}`,
    ).toBe(SMITH_DAMAGE);

    await world.command(server, `item replace entity ${playerName} inventory.26 from entity ${playerName} weapon.mainhand`);
    await world.command(server, `item replace entity ${playerName} weapon.mainhand with minecraft:air`);
    await setMaintainDemandViaUI(server, client, playerName, smithing.target, SMITH_RESULT, 1);
    await world.command(server, `item replace entity ${playerName} weapon.mainhand from entity ${playerName} inventory.26`);
    await world.command(server, `item replace entity ${playerName} inventory.26 with minecraft:air`);

    const smithTargetBefore = await readContainerStacks(server, smithing.target);
    expect(
      smithTargetBefore.some((stack) => stack.id === SMITH_RESULT),
      `the target chest already holds ${SMITH_RESULT} after ordering: the UI picker's sample leaked into the scene, so this phase cannot test smithing`,
    ).toBe(false);
    const packsBeforeSmith = await countInResidentPacks(server);
    expect(
      packsBeforeSmith.totals[SMITH_RESULT] ?? 0,
      `a resident pack already holds ${SMITH_RESULT} after ordering (${JSON.stringify(packsBeforeSmith.totals)}): same as above, sample leak`,
    ).toBe(0);

    const smithed = await waitMatched(
      server,
      `if items block ${commandPos(smithing.target)} container.* ${SMITH_RESULT}`,
      { maxTicks: SMITH_BUDGET_TICKS, stepTicks: 20 },
    );
    const smithTargetAfter = await readContainerStacks(server, smithing.target);
    const smithSupplyAfter = await readContainerStacks(server, smithing.supply);

    expect(
      smithed.matched,
      `${SMITH_RESULT} never reached the MAINTAIN-pinned target chest within ${SMITH_BUDGET_TICKS} ticks (reason=${smithed.reason}). `
        + `The smithing table is the colony's only workstation and all three inputs are in stock, so failing here = the "claim workstation -> fetch -> smith at the table -> deliver"`
        + ` chain broke somewhere; first check the workstation opt-in succeeded, then whether the supply chest ${JSON.stringify(smithSupplyAfter)} is still full.`,
    ).toBe(true);
    expect(
      smithTargetAfter.filter((stack) => stack.id === SMITH_RESULT).map((stack) => stack.damage),
      `none of the ${SMITH_RESULT} in the target chest carries the base's damage=${SMITH_DAMAGE} (read ${JSON.stringify(smithTargetAfter)}). `
        + `damage=null means the item is brand new: it cannot come from "the same base upgraded to a harder material", so it was hauled in from elsewhere / made from nothing.`,
    ).toContain(SMITH_DAMAGE);
    expect(
      smithSupplyAfter.some((stack) => stack.id === SMITH_BASE),
      `the product appeared, but ${SMITH_BASE} is still in the supply chest (${JSON.stringify(smithSupplyAfter)}): the base was not consumed = an extra item from nothing`,
    ).toBe(false);

    await world.command(
      server,
      `item replace block ${commandPos(smithing.supply)} container.${DOORWAY_SOURCE_SLOT}`
        + ` with ${DOORWAY_DEMAND_ITEM} ${DOORWAY_SOURCE_STOCK}`,
    );
    await tick.sprint(server, 5);
    const doorwaySupply = await readContainerStacks(server, smithing.supply);
    expect(
      doorwaySupply
        .filter((stack) => stack.id === DOORWAY_DEMAND_ITEM)
        .reduce((sum, stack) => sum + stack.count, 0),
      `phase D's stock was not staged: supply chest read ${JSON.stringify(doorwaySupply)}`,
    ).toBeGreaterThanOrEqual(DOORWAY_SOURCE_STOCK);

    const doorwayEvidence: any[] = [];
    for (const fixture of doorwayFixtures(founding.origin)) {
      phaseContainers = [...phaseContainers, fixture.demand];
      await tryCommand(server, "time set day");
      for (const command of fixture.build) {
        await world.command(server, command);
      }
      await tick.sprint(server, 5);

      const evidence: any = { label: fixture.label, barrier: fixture.barrier };
      evidence.shut_at_start = await world.probe(
        server, `if block ${commandPos(fixture.barrier)} ${fixture.blockId}[open=false]`);
      expect(
        evidence.shut_at_start,
        `[${fixture.label}] setup not built: ${commandPos(fixture.barrier)} is not a closed ${fixture.blockId}`,
      ).toBe(true);

      await tryCommand(
        server,
        `tp @e[type=${RESIDENT_TYPE},${volumeArgs(fixture.interior)}] `
          + `${founding.origin.x + 0.5} ${founding.origin.y} ${founding.origin.z + 3.5}`,
      );
      await tick.sprint(server, 3);

      evidence.resident_inside_at_start = await world.probe(
        server, `if entity @e[type=${RESIDENT_TYPE},${volumeArgs(fixture.interior)}]`);
      expect(
        evidence.resident_inside_at_start,
        `[${fixture.label}] could not clear residents out of the enclosure - the "they got in" check would be true from the start, so this phase tests nothing`,
      ).toBe(false);

      await world.command(server, `setblock ${commandPos(fixture.demand)} minecraft:chest{Items:[]}`);
      await tick.sprint(server, 3);
      evidence.demand_chest_placed = await world.probe(
        server, `if block ${commandPos(fixture.demand)} minecraft:chest`);
      expect(
        evidence.demand_chest_placed,
        `[${fixture.label}] demand chest not built: ${commandPos(fixture.demand)} is not a chest`,
      ).toBe(true);

      evidence.deep_volume_covers_demand = volumeHolds(fixture.deep, fixture.demand);
      expect(
        evidence.deep_volume_covers_demand,
        `[${fixture.label}] check volume ${volumeArgs(fixture.deep)} does not cover demand chest ${commandPos(fixture.demand)}: the volume is miscomputed`,
      ).toBe(true);

      evidence.opt_in = await optInViaBook(server, client, playerName, fixture.demand, "chest");
      await world.command(server, `item replace entity ${playerName} inventory.26 from entity ${playerName} weapon.mainhand`);
      await world.command(server, `item replace entity ${playerName} weapon.mainhand with minecraft:air`);
      await setMaintainDemandViaUI(server, client, playerName, fixture.demand, DOORWAY_DEMAND_ITEM, 1);
      await world.command(server, `item replace entity ${playerName} weapon.mainhand from entity ${playerName} inventory.26`);
      await world.command(server, `item replace entity ${playerName} inventory.26 with minecraft:air`);
      await world.command(
        server,
        `tp ${playerName} ${founding.origin.x + 0.5} ${founding.origin.y} ${founding.origin.z + 3.5}`,
      );
      await tick.sprint(server, 5);

      evidence.opened = await waitMatched(
        server, `if block ${commandPos(fixture.barrier)} ${fixture.blockId}[open=true]`,
        { maxTicks: DOORWAY_OPEN_BUDGET, stepTicks: 1 });
      expect(
        evidence.opened.matched,
        `[${fixture.label}] ${commandPos(fixture.barrier)} was never opened within ${DOORWAY_OPEN_BUDGET} ticks`
          + ` (reason=${evidence.opened.reason}). The enclosure has only this one opening, so failing here = the "open" half never ran: `
          + `the goal never fired (no resident was dispatched / none hit the barrier / the path never crossed this cell), or setOpen(true) never reached the world. `
          + `Only the fence gate failing = the fence gate branch's fault (doors have setOpen, fence gates use a hand-written setBlock).`,
      ).toBe(true);

      evidence.entered = await waitMatched(
        server, `if entity @e[type=${RESIDENT_TYPE},${volumeArgs(fixture.deep)}]`,
        { maxTicks: DOORWAY_ENTER_BUDGET, stepTicks: 1 });
      expect(
        evidence.entered.matched,
        `[${fixture.label}] the barrier opened, but no resident showed up deep in the corridor within ${DOORWAY_ENTER_BUDGET} ticks`
          + ` (${volumeArgs(fixture.deep)}, >=${DEEP_MARGIN} blocks from the doorway). The door opens but no one gets through: it closed on them, `
          + `or the path beyond the door is broken.`,
      ).toBe(true);

      evidence.shut_again = await waitMatched(
        server, `if block ${commandPos(fixture.barrier)} ${fixture.blockId}[open=false]`,
        { maxTicks: DOORWAY_SHUT_BUDGET, stepTicks: 1 });
      expect(
        evidence.shut_again.matched,
        `[${fixture.label}] the barrier never closed again after opening (${DOORWAY_SHUT_BUDGET} ticks, `
          + `reason=${evidence.shut_again.reason}). This is the only half of the round-trip check that can fail on its own: remove just the "close", `
          + `and the two checks above stay green. A pasture gate left open for good after anyone passes is no gate at all - the pen's enclosure depends on it.`,
      ).toBe(true);

      doorwayEvidence.push(evidence);
    }
    expect(doorwayEvidence.map((entry) => entry.label)).toHaveLength(2);
  } catch (error) {
    pair?.failing(error);
    throw error;
  } finally {
    await pair?.teardown(testInfo);
  }
});

function smithingLayout(origin: Vec) {
  return {
    table: { x: origin.x + 9, y: origin.y, z: origin.z + 3 },
    supply: { x: origin.x + 11, y: origin.y, z: origin.z + 3 },
    target: { x: origin.x + 13, y: origin.y, z: origin.z + 3 },
  };
}

async function readContainerStacks(server: any, container: Vec) {
  const result = await world.container(server, container, { dimension: "minecraft:overworld", nbt: true });
  if (result.found === false || result.isContainer === false) {
    throw new Error(`container at ${commandPos(container)} is not readable: ${JSON.stringify(result)}`);
  }
  const items = (result as any)?.nbt?.Items;
  const list = Array.isArray(items) ? items : [];
  return list.map((entry: any) => ({
    slot: typeof entry?.Slot === "number" ? entry.Slot : null,
    id: typeof entry?.id === "string" ? entry.id : null,
    count: typeof entry?.count === "number" ? entry.count : 1,
    damage: componentDamage(entry),
  }));
}

function componentDamage(entry: any): number | null {
  const components = entry?.components;
  if (!components || typeof components !== "object") {
    return null;
  }
  const value = components["minecraft:damage"] ?? components.damage;
  return typeof value === "number" ? value : null;
}

type Volume = { x: number; y: number; z: number; dx: number; dy: number; dz: number };

function volumeArgs(volume: Volume) {
  return `x=${volume.x},y=${volume.y},z=${volume.z},dx=${volume.dx},dy=${volume.dy},dz=${volume.dz}`;
}

function volumeHolds(volume: Volume, cell: Vec) {
  return cell.x + 1 >= volume.x && cell.x <= volume.x + volume.dx
    && cell.y + 1 >= volume.y && cell.y <= volume.y + volume.dy
    && cell.z + 1 >= volume.z && cell.z <= volume.z + volume.dz;
}

type DoorwayFixture = {
  label: string;
  barrier: Vec;
  blockId: string;
  demand: Vec;
  build: string[];
  interior: Volume;
  deep: Volume;
};

const DEEP_MARGIN = 3;

const WORKED_FROM_WITHIN = 4;

const CHEST_TO_WALL = WORKED_FROM_WITHIN;

const CHEST_TO_DOORWAY = WORKED_FROM_WITHIN + DEEP_MARGIN;

function doorwayFixtures(origin: Vec): DoorwayFixture[] {
  const y = origin.y;
  const nearWall = origin.z - 5;
  const innerNear = nearWall - 1;
  const chestZ = innerNear - CHEST_TO_DOORWAY;
  const innerFar = chestZ - CHEST_TO_WALL;
  const farWall = innerFar - 1;
  const innerDepth = innerNear - innerFar + 1;

  const doorX = origin.x - (CHEST_TO_WALL + 2);
  const gateX = origin.x + (CHEST_TO_WALL + 2);

  const chamber = (centreX: number) => ({
    outerMinX: centreX - (CHEST_TO_WALL + 1),
    outerMaxX: centreX + (CHEST_TO_WALL + 1),
    innerMinX: centreX - CHEST_TO_WALL,
    innerMaxX: centreX + CHEST_TO_WALL,
    innerWidth: 2 * CHEST_TO_WALL + 1,
  });
  const corridor = chamber(doorX);
  const pen = chamber(gateX);

  return [
    {
      label: "oak door in a sealed stone corridor",
      barrier: { x: doorX, y, z: nearWall },
      blockId: "minecraft:oak_door",
      demand: { x: doorX, y, z: chestZ },
      build: [
        `fill ${corridor.outerMinX} ${y} ${farWall} ${corridor.outerMaxX} ${y + 2} ${nearWall} minecraft:smooth_stone`,
        `fill ${corridor.innerMinX} ${y} ${innerFar} ${corridor.innerMaxX} ${y + 2} ${innerNear} minecraft:air`,
        `setblock ${doorX} ${y} ${nearWall} minecraft:oak_door[facing=north,half=lower,hinge=left,open=false,powered=false]`,
        `setblock ${doorX} ${y + 1} ${nearWall} minecraft:oak_door[facing=north,half=upper,hinge=left,open=false,powered=false]`,
      ],
      interior: { x: corridor.innerMinX, y, z: innerFar, dx: corridor.innerWidth, dy: 2, dz: innerDepth },
      deep: { x: corridor.innerMinX, y, z: innerFar, dx: corridor.innerWidth, dy: 2, dz: innerDepth - DEEP_MARGIN },
    },
    {
      label: "oak fence gate in a fenced pen",
      barrier: { x: gateX, y, z: nearWall },
      blockId: "minecraft:oak_fence_gate",
      demand: { x: gateX, y, z: chestZ },
      build: [
        `fill ${pen.outerMinX} ${y} ${farWall} ${pen.outerMaxX} ${y} ${nearWall} minecraft:oak_fence`,
        `fill ${pen.innerMinX} ${y} ${innerFar} ${pen.innerMaxX} ${y} ${innerNear} minecraft:air`,
        `setblock ${gateX} ${y} ${nearWall} minecraft:oak_fence_gate[facing=north,open=false,in_wall=false,powered=false]`,
      ],
      interior: { x: pen.innerMinX, y, z: innerFar, dx: pen.innerWidth, dy: 2, dz: innerDepth },
      deep: { x: pen.innerMinX, y, z: innerFar, dx: pen.innerWidth, dy: 2, dz: innerDepth - DEEP_MARGIN },
    },
  ];
}
