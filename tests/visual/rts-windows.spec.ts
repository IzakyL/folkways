import { expect, test } from "@playwright/test";
import { input, screen, tick } from "@izakyl/blockwright-minecraft";
import { clickElement, panelElements, selectElement } from "../ldlib2";
import { QUIET_TOASTS } from "../shared/capture/ponder";
import { VisualRun } from "../shared/capture/visual-kit";
import { waitForGroundedPlayer } from "../shared/colony/colony-founding";
import { foundBookColony, layFloor, openRtsTool } from "../shared/combat/overhead";
import { IS_PICKING_TARGET, READ_HOTKEYS, SET_EDGE_SCROLL, SET_UP_GATE } from "../shared/combat/rts-gate";
import { PROJECT } from "../shared/combat/rts-fixtures";
import { SELECT_VEHICLES, SPAWN } from "../shared/combat/rts-vehicle";
import { BUDGET } from "../shared/harness/budgets";
import { frames } from "../shared/harness/bw-helpers";
import { tracePath } from "../shared/harness/out-paths";
import { launchPair, type E2EPair } from "../shared/harness/pair";
import { newRunId } from "../shared/harness/run-id";
import { waitForPresent } from "../shared/ui/book-cards";
import { MODE_PROBE, PREPARE_MODE_LAYERS } from "../shared/ui/mode-layers";
import { mustCommand } from "../shared/ui/world-ui";

const card = "folkways.card.";

test("RTS facility, mode layers and vehicle window", async ({}, testInfo) => {
  test.setTimeout(BUDGET.heavy);
  const id = newRunId();
  const run = new VisualRun("rts-windows");
  let pair: E2EPair | undefined;
  try {
    pair = await launchPair({
      config: "blockwright.toml#warfare-rts",
      server: { instance: `visual-rts-windows-server-${id}`, trace: tracePath("visual", "rts-windows-server") },
      client: { instance: `visual-rts-windows-client-${id}`, trace: tracePath("visual", "rts-windows-client") },
    });
    const { server, client } = pair;
    const player = await waitForGroundedPlayer(server);
    const origin = { x: Math.round(player.pos.x) + 4, y: Math.round(player.pos.y), z: Math.round(player.pos.z) + 4 };
    await mustCommand(server, "gamerule doMobSpawning false");
    await mustCommand(server, "gamerule doDaylightCycle false");
    await mustCommand(server, "time set noon");
    await layFloor(server, { x: origin.x - 20, z: origin.z - 10 }, { x: origin.x + 20, z: origin.z + 24 },
      origin.y, "minecraft:smooth_stone", 8);
    const { colony } = await foundBookColony(server, client, player.name, origin, 2);
    const gate = { ...origin, z: origin.z + 10, colony };
    await server.run(SET_UP_GATE, { ...gate, visualReadouts: true });
    const vehicle = await server.run(SPAWN, { ...origin, x: origin.x - 7, z: origin.z + 15, type: "superbwarfare:lav_150" });
    await mustCommand(server, `tp ${player.name} ${origin.x + 0.5} ${origin.y} ${origin.z + 2.5} 0 0`);
    await client.run(SET_EDGE_SCROLL, { enabled: false });
    await tick.sprint(server, 20);
    await frames(client, 10);
    await openRtsTool(client);
    await tick.sprint(server, 20);
    await frames(client, 5);
    await client.run(QUIET_TOASTS);
    const shot = async (name: string, subject: string, worldState = {}) => {
      await input.move(client, { x: 80, y: 120 });
      await frames(client, 5);
      await run.shot(client, name, { subject, worldState, hard: true });
    };
    const focus = async (point: typeof origin, token: string) => {
      const at = await client.run(PROJECT, { ...point, entity: "" });
      await input.click(client, at, { button: "left" });
      await waitForPresent(server, client, token);
    };
    await focus({ ...gate, y: gate.y + 1 }, card + "readout.lever");
    await expect.poll(async () => selectElement(await panelElements(client), { id: card + "button.close" })?.active)
      .toBe(false);
    for (const token of ["button.open", "toggle.night", "choice.shell.he", "number.charges.increase",
      "readout.status", "readout.replenishment", "readout.contents"]) {
      expect(selectElement(await panelElements(client), { id: card + token })).toBeDefined();
    }
    const readings = await panelElements(client);
    expect(readings.some(element => element.text === "38%")).toBe(true);
    expect(readings.some(element => element.text === "Stocked")).toBe(true);
    const hotkeys = await client.run(READ_HOTKEYS);
    expect(hotkeys).toContain("R");
    await shot("facility-command-card", "Defined gate commands, controls, readouts and disabled close action", { hotkeys });
    await client.run(SET_EDGE_SCROLL, { enabled: true });
    await frames(client, 5);
    await shot("facility-edge-scroll-hotkeys", "Gate command hotkeys with edge scrolling",
      { hotkeys: await client.run(READ_HOTKEYS) });
    await clickElement(client, { id: card + "target.pick" });
    expect(await client.run(IS_PICKING_TARGET)).toBe(true);
    await shot("facility-target-pick", "Pick a world target for the facility routine");
    await input.key(client, { keyCode: 256 });
    await client.run(SELECT_VEHICLES, { ids: [vehicle] });
    await waitForPresent(server, client, "folkways.rts.unit.board");
    await shot("vehicle-window", "Vehicle commands with an empty crew roster", { vehicle });
    await clickElement(client, { id: "folkways.rts.unit.profile.change" });
    await waitForPresent(server, client, "folkways.choice.cancel");
    await shot("vehicle-profile-picker", "Choose a vehicle profile");
    await clickElement(client, { id: "folkways.choice.cancel" });

    await server.run(PREPARE_MODE_LAYERS, { ...origin, colony });
    await expect.poll(async () => {
      await tick.sprint(server, 10);
      await frames(client, 8);
      const state = await client.run(MODE_PROBE);
      return state.zones > 0 && state.ghosts > 0 && state.marks > 0;
    }).toBe(true);
    for (const [label, mode] of [["Build", "build"], ["Control", "rts"]]) {
      await screen.clickWidget(client, { label });
      await expect.poll(async () => {
        await frames(client, 8);
        return (await client.run(MODE_PROBE)).alpha[mode];
      }).toBeCloseTo(1, 4);
      await shot(`mode-${mode}`, `Book overlays in ${label} mode`, await client.run(MODE_PROBE));
    }
  } catch (error) {
    pair?.failing(error);
    throw error;
  } finally {
    await pair?.teardown(testInfo);
  }
});
