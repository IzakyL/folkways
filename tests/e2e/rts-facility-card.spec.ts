import { expect, test } from "@playwright/test";
import { defineTask, input, javaTask, tick } from "@izakyl/blockwright-minecraft";
import { clickElement, panelElements, selectElement, waitForElement } from "../ldlib2";
import { waitForGroundedPlayer } from "../shared/colony/colony-founding";
import { foundBookColony, layFloor, openRtsTool } from "../shared/combat/overhead";
import { PREPARE_RESIDENTS, PROJECT, type Vec } from "../shared/combat/rts-fixtures";
import { BUDGET } from "../shared/harness/budgets";
import { frames } from "../shared/harness/bw-helpers";
import { launchPair, type E2EPair } from "../shared/harness/pair";
import { newRunId } from "../shared/harness/run-id";
import { mustCommand } from "../shared/ui/world-ui";
import { SET_UP_GATE, SET_EDGE_SCROLL, READ_HOTKEYS, IS_PICKING_TARGET, SERVER_IMPORTS, GATE_SCENE } from "../shared/combat/rts-gate";

const FACILITY = "folkways.card.";
const OPEN = { id: FACILITY + "button.open" };
const CLOSE = { id: FACILITY + "button.close" };
const TARGET = { id: FACILITY + "target.pick" };
const NIGHT = { id: FACILITY + "toggle.night" };
const READOUT = FACILITY + "readout.lever";

test("RTS facility card opens and closes a gate with both camera layouts", async ({}, testInfo) => {
  test.setTimeout(BUDGET.standard);
  let pair: E2EPair | undefined;
  try {
    const run = newRunId();
    pair = await launchPair({
      config: "blockwright.toml#rts",
      server: { instance: `rts-facility-server-${run}` },
      client: { instance: `rts-facility-client-${run}` },
      snapshot: { entityTypes: ["folkways_vanilla:resident"] },
    });
    const { server, client } = pair;
    const player = await waitForGroundedPlayer(server);
    const origin = { x: Math.round(player.pos.x) + 4, y: Math.round(player.pos.y), z: Math.round(player.pos.z) + 4 };
    for (const command of ["difficulty peaceful", "gamerule doMobSpawning false", "gamerule doDaylightCycle false",
      "time set noon"]) {
      await mustCommand(server, command);
    }
    await layFloor(server, { x: origin.x - 16, z: origin.z - 10 }, { x: origin.x + 16, z: origin.z + 24 }, origin.y);
    const { colony } = await foundBookColony(server, client, player.name, origin, 1);
    expect(await server.run(PREPARE_RESIDENTS, { colony, ...origin, count: 1 })).toHaveLength(1);
    const gate = { ...origin, z: origin.z + 10, colony };
    await server.run(SET_UP_GATE, gate);
    await mustCommand(server, `tp ${player.name} ${origin.x + 0.5} ${origin.y} ${origin.z + 2.5} 0 0`);
    await client.run(SET_EDGE_SCROLL, { enabled: false });
    await tick.sprint(server, 10);
    await frames(client, 10);
    await openRtsTool(client);
    await tick.sprint(server, 20);
    await frames(client, 5);

    const at = await client.run(PROJECT, { ...gate, y: gate.y + 1, entity: "" });
    await input.click(client, { x: at.x, y: at.y }, { button: "left" });
    await waitForElement(client, { id: "folkways.window.folkways_rts_unit" });
    await waitForElement(client, { id: READOUT });

    const card = () => panelElements(client);
    async function waitForGate(routine: string, open: boolean) {
      await expect.poll(async () => {
        await tick.sprint(server, 15);
        await frames(client, 3);
        const elements = await card();
        const gateState = await server.run(READ_GATE, gate);
        return {
          lever: gateState.lever, door: gateState.door, night: gateState.night,
          completed: elements.some(element => element.text === `Last: ${routine} · done`),
          disabled: selectElement(elements, open ? OPEN : CLOSE)?.active === false,
          reading: selectElement(elements, { within: READOUT, path: [2] })?.text,
        };
      }, { timeout: 45_000 }).toEqual({
        lever: open, door: open, night: open, completed: true, disabled: true, reading: open ? "On" : "Off",
      });
    }

    await expect.poll(async () => {
      await tick.sprint(server, 10);
      await frames(client, 3);
      return selectElement(await card(), CLOSE)?.active;
    }).toBe(false);
    expect(selectElement(await card(), OPEN)?.active).toBe(true);
    // A number steps down before it steps up on the card, and its step down has the slot after its step up.
    expect(await client.run(READ_HOTKEYS)).toEqual(["R", "T", "Y", "U", "", "K"]);
    await input.key(client, { keyCode: 82 });
    await waitForGate("open", true);
    expect(selectElement(await card(), CLOSE)?.active).toBe(true);

    await clickElement(client, NIGHT);
    await expect.poll(async () => {
      await tick.sprint(server, 10);
      await frames(client, 3);
      const reading = await server.run(READ_GATE, gate);
      return { night: reading.night, chosen: selectElement(await card(), NIGHT)?.classes?.includes("folkways_chosen") };
    }).toEqual({ night: false, chosen: false });

    await client.run(SET_EDGE_SCROLL, { enabled: true });
    await frames(client, 5);
    expect(await client.run(READ_HOTKEYS)).toEqual(["Q", "W", "E", "R", "D", "S"]);
    await input.key(client, { keyCode: 87 });
    await waitForGate("close", false);

    await clickElement(client, { id: FACILITY + "choice.shell.he" });
    await clickElement(client, { id: FACILITY + "number.charges.increase" });
    await expect.poll(async () => {
      await tick.sprint(server, 10);
      await frames(client, 3);
      const { shell, charges } = await server.run(READ_GATE, gate);
      return { shell, charges };
    }).toEqual({ shell: "he", charges: 2 });
    await clickElement(client, { id: FACILITY + "number.charges.decrease" });
    await expect.poll(async () => {
      await tick.sprint(server, 10);
      return (await server.run(READ_GATE, gate)).charges;
    }).toBe(1);

    const worldPoint = (dx: number) => client.run(PROJECT, { ...gate, x: gate.x + dx, entity: "" });
    await clickElement(client, TARGET);
    expect(await client.run(IS_PICKING_TARGET)).toBe(true);
    await input.key(client, { keyCode: 256 });
    expect(await client.run(IS_PICKING_TARGET)).toBe(false);
    expect(selectElement(await card(), TARGET)).toBeDefined();

    await clickElement(client, TARGET);
    await input.click(client, await worldPoint(3), { button: "left" });
    expect(await client.run(IS_PICKING_TARGET)).toBe(false);
    async function waitForTargetRun(open: boolean) {
      await expect.poll(async () => {
        await tick.sprint(server, 15);
        await frames(client, 3);
        const { lever, door } = await server.run(READ_GATE, gate);
        return { lever, door, done: (await card()).some(element => element.text === "Last: pick · done") };
      }, { timeout: 45_000 }).toEqual({ lever: open, door: open, done: true });
    }
    await waitForTargetRun(true);
    await input.click(client, await worldPoint(-3), { button: "right" });
    await waitForTargetRun(false);
  } catch (error) {
    pair?.failing(error);
    throw error;
  } finally {
    await pair?.teardown(testInfo);
  }
});

const READ_GATE = defineTask<Vec & { colony: string }, { lever: boolean; door: boolean; night: boolean; shell: string; charges: number }>({
  name: "folkways.rts-facility-card.read-gate", side: "server",
  source: javaTask({ imports: SERVER_IMPORTS, body: GATE_SCENE + `
var view = facilities.read(name).orElseThrow();
var parameters = (Map<?, ?>) view.data().get("parameters");
return Map.of("shell", parameters.get("shell"), "charges", parameters.get("charges"),
  "lever", level.getBlockState(lever).getValue(LeverBlock.POWERED),
  "door", level.getBlockState(door).getValue(DoorBlock.OPEN),
  "night", view.triggers().get("night"));
` }),
});
