import { expect, test } from "@playwright/test";
import { defineTask, input, javaTask, screen, tick } from "@izakyl/blockwright-minecraft";
import { foundColonyFast, openColonyPanelViaBook, waitForGroundedPlayer } from "../shared/colony/colony-founding";
import { PREPARE_RESIDENTS, PROJECT, READ_CLIENT, RESIZE, type Button } from "../shared/combat/rts-fixtures";
import { CONFIG_PATH, VisualRun } from "../shared/capture/visual-kit";
import { frames } from "../shared/harness/bw-helpers";
import { BUDGET } from "../shared/harness/budgets";
import { seededOffThreadServer } from "../shared/harness/mod-settings";
import { tracePath } from "../shared/harness/out-paths";
import { launchPair, type E2EPair } from "../shared/harness/pair";
import { newRunId } from "../shared/harness/run-id";
import { mustCommand } from "../shared/ui/world-ui";

// Arrange a persistent server failure for rendering review, without depending on pathfinding timing.
const FAIL_ROUTE = defineTask<{ colony: string; resident: string }, boolean>({
  name: "folkways.visual.combat-cards.failed-route", side: "server",
  source: javaTask({
    imports: ["net.minecraft.server.MinecraftServer", "io.github.izakyl.folkways.core.colony.api.Colonies",
      "io.github.izakyl.folkways.rts.combat.group.UnitGroups", "io.github.izakyl.folkways.rts.combat.group.UnitGroup"],
    body: `
var a = Args.of(ctx);
var colony = Colonies.of((MinecraftServer) ctx.server(), UUID.fromString(a.string("colony"))).orElseThrow();
var group = UnitGroups.of(colony).ofResident(UUID.fromString(a.string("resident"))).orElseThrow();
var fail = UnitGroup.class.getDeclaredMethod("failedWaiting", UnitGroup.EndReason.class);
fail.setAccessible(true);
fail.invoke(group, UnitGroup.EndReason.ROUTE_NO_PATH);
return true;`,
  }),
});

test("combat cards: status, failures, pagination and hover help at both window sizes", async ({}, testInfo) => {
  test.setTimeout(BUDGET.heavy);
  let pair: E2EPair | undefined;
  try {
    const id = newRunId();
    pair = await launchPair({
      config: `${CONFIG_PATH}#visual-rts`,
      server: { instance: seededOffThreadServer(`visual-combat-cards-server-${id}`), trace: tracePath("visual", "combat-cards-server") },
      client: { instance: `visual-combat-cards-client-${id}`, trace: tracePath("visual", "combat-cards-client") },
    });
    const { server, client } = pair;
    const player = await waitForGroundedPlayer(server);
    const origin = { x: Math.round(player.pos.x) + 4, y: Math.round(player.pos.y), z: Math.round(player.pos.z) + 4 };
    for (const command of ["difficulty peaceful", "gamerule doMobSpawning false", "gamerule doDaylightCycle false",
      "gamerule doWeatherCycle false", "weather clear", "time set noon"]) {
      await mustCommand(server, command);
    }
    await mustCommand(server,
      `fill ${origin.x - 19} ${origin.y - 1} ${origin.z - 19} ${origin.x + 19} ${origin.y - 1} ${origin.z + 19} minecraft:smooth_stone`);
    const founding = await foundColonyFast(server, client, player.name, { origin, residentCount: 14 });
    const colony = String(founding.evidence.colonyId);
    await openColonyPanelViaBook(server, client, player.name, founding.standBlock);
    await screen.dismiss(client);
    const residents = await server.run(PREPARE_RESIDENTS, { colony, ...origin, count: 14 });
    expect(residents).toHaveLength(14);
    await mustCommand(server, `tp ${player.name} ${origin.x + 0.5} ${origin.y} ${origin.z + 2.5} 0 0`);
    await tick.sprint(server, 2);
    await frames(client, 10);
    await input.key(client, { keyCode: 86 });
    await screen.waitFor(client, "OverheadScreen", { timeoutMs: 8_000 });
    await screen.clickWidget(client, { label: "RTS" });
    const settle = async () => {
      await tick.sprint(server, 2);
      await frames(client, 5);
      return client.run(READ_CLIENT);
    };
    await expect.poll(async () => (await settle()).roster, { timeout: 15_000 }).toBe(14);
    const center = (hit: Button) => ({ x: hit.x + hit.width / 2, y: hit.y + hit.height / 2 });
    let bound = false;
    for (const size of [{ width: 1920, height: 1080 }, { width: 854, height: 480 }]) {
      const run = new VisualRun(`combat-cards-${size.width}x${size.height}`);
      const expected: string[] = [];
      await client.run(RESIZE, { ...size, scale: 2 });
      await frames(client, 15);
      if ((await client.run(READ_CLIENT)).selected.length) {
        await input.key(client, { keyCode: 256 });
      }
      await expect.poll(async () => (await settle()).selected).toEqual([]);
      await input.move(client, { x: 0, y: 0 });
      const shot = async (name: string, subject: string) => {
        expected.push(name);
        await run.shot(client, name, { subject, worldState: { size, state: await client.run(READ_CLIENT) }, hard: true });
      };
      await input.key(client, { keyCode: 72 });
      expect((await client.run(READ_CLIENT)).failureKey).toBe("folkways.rts.select_first");
      await shot("02-empty-selection-failure", "Empty-selection command: red failure text on the parchment status row");
      await expect.poll(async () => {
        await frames(client, 3);
        return (await client.run(READ_CLIENT)).failure;
      }, { timeout: 6_000 }).toBe("");
      expect((await client.run(READ_CLIENT)).state.result).toBe("none");
      await shot("01-normal-status", "Normal status after the temporary failure has expired");

      if (!bound) {
        const boxes = await Promise.all(residents.map(entity => client.run(PROJECT, { ...origin, entity })));
        await input.drag(client, {
          from: { x: Math.min(...boxes.map(box => box.left)) - 3, y: Math.min(...boxes.map(box => box.top)) - 3 },
          to: { x: Math.max(...boxes.map(box => box.right)) + 3, y: Math.max(...boxes.map(box => box.bottom)) + 3 },
          steps: 8, button: "left",
        });
        await expect.poll(async () => (await settle()).selected.length).toBe(14);
        await input.key(client, { keyCode: 49, modifiers: ["ctrl"] });
        await expect.poll(async () => (await settle()).control.groups[0]?.length).toBe(14);
        bound = true;
      } else {
        await input.key(client, { keyCode: 49 });
        await expect.poll(async () => (await settle()).selected.length).toBe(14);
      }
      await input.key(client, { keyCode: 72 });
      await expect.poll(async () => (await settle()).state.result).toBe("stopped");
      await input.move(client, { x: 0, y: 0 });
      await frames(client, 5);
      const firstPage = (await client.run(READ_CLIENT)).buttons.filter(hit => hit.kind === "UNIT").map(hit => hit.resident);
      expect(firstPage.length).toBeGreaterThan(0);
      expect(firstPage.length).toBeLessThan(14);
      await shot("03-page-one", "Fourteen selected residents: first page, group slots and centered portraits");
      for (const [kind, name, subject] of [
        ["GROUP", "05-group-tooltip", "Group slot hover: slot number and available member count"],
        ["UNIT", "06-portrait-tooltip", "Portrait hover: resident name and health"],
        ["NEXT", "07-next-tooltip", "Next-page arrow hover help"],
        ["PREVIOUS", "08-previous-tooltip", "Previous-page arrow hover help"],
      ]) {
        const hit = (await client.run(READ_CLIENT)).buttons.find(button => button.kind === kind);
        expect(hit, `${kind} must be drawn at ${size.width}x${size.height}`).toBeTruthy();
        await input.move(client, center(hit!));
        await frames(client, 15);
        await shot(name, subject);
      }
      const next = (await client.run(READ_CLIENT)).buttons.find(hit => hit.kind === "NEXT")!;
      await input.click(client, center(next), { button: "left" });
      await input.move(client, { x: 0, y: 0 });
      await frames(client, 5);
      const secondPage = (await client.run(READ_CLIENT)).buttons.filter(hit => hit.kind === "UNIT").map(hit => hit.resident);
      expect(secondPage.length).toBeGreaterThan(0);
      expect(secondPage.some(id => firstPage.includes(id))).toBe(false);
      expect((await client.run(READ_CLIENT)).selected).toHaveLength(14);
      await shot("04-page-two", "The next page has different portraits while the selection remains fourteen");

      const target = await client.run(PROJECT, { ...origin, x: origin.x + 7, z: origin.z + 6, entity: "" });
      expect(target.y).toBeLessThan(target.height - (await client.run(READ_CLIENT)).barHeight);
      const request = (await client.run(READ_CLIENT)).route.request;
      await input.click(client, target, { button: "right" });
      await expect.poll(async () => {
        const state = await settle();
        return state.route.request > request && state.route.accepted && state.route.intent === "MOVE";
      }).toBe(true);
      await server.run(FAIL_ROUTE, { colony, resident: residents[0] });
      await expect.poll(async () => (await settle()).state.result, { timeout: 15_000 }).toBe("route_no_path");
      await expect.poll(async () => {
        await frames(client, 3);
        return (await client.run(READ_CLIENT)).failure;
      }, { timeout: 6_000 }).toBe("");
      expect((await client.run(READ_CLIENT)).state.phase).toBe("failed_waiting");
      await input.move(client, { x: 0, y: 0 });
      await shot("09-route-failure", "Persistent route_no_path failure: red status after all temporary failure text has expired");
      run.expectTaken(expected);
    }
  } catch (error) {
    pair?.failing(error);
    throw error;
  } finally {
    await pair?.teardown(testInfo);
  }
});
