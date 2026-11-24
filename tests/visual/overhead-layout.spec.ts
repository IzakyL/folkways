import { expect, test } from "@playwright/test";
import { defineTask, input, javaTask, screen, tick } from "@izakyl/blockwright-minecraft";
import { QUIET_TOASTS } from "../shared/capture/ponder";
import { VisualRun } from "../shared/capture/visual-kit";
import { waitForGroundedPlayer } from "../shared/colony/colony-founding";
import { foundBookColony, layFloor, pickRtsTool, tapV } from "../shared/combat/overhead";
import { PREPARE_RESIDENTS, PROJECT, RESIZE } from "../shared/combat/rts-fixtures";
import { SET_UP_GATE } from "../shared/combat/rts-gate";
import { BUDGET } from "../shared/harness/budgets";
import { frames } from "../shared/harness/bw-helpers";
import { tracePath } from "../shared/harness/out-paths";
import { launchPair, type E2EPair } from "../shared/harness/pair";
import { newRunId } from "../shared/harness/run-id";
import { waitForPresent } from "../shared/ui/book-cards";
import { mustCommand } from "../shared/ui/world-ui";

/** Window sizes and GUI scales the view from above is laid out for, down to a narrow window. */
const SIZES = [
  { width: 1600, height: 900, scale: 2 },
  { width: 1280, height: 720, scale: 3 },
  { width: 1280, height: 720, scale: 1 },
  { width: 854, height: 480, scale: 2 },
  { width: 640, height: 480, scale: 2 },
];

// Two groups bound from the residents, as Ctrl+1 and Ctrl+2 would, then the selection cleared.
const BIND_GROUPS = defineTask<{ residents: string[]; step: number }, boolean>({
  name: "folkways.visual.overhead-layout.bind-groups", side: "client",
  source: javaTask({
    imports: ["io.github.izakyl.folkways.core.command.combat.net.CombatPackets"],
    body: `
var ids = Args.of(ctx).strings("residents").stream().map(UUID::fromString).toList();
var step = Args.of(ctx).integer("step");
return switch ((int) step) {
    case 0 -> CombatPackets.select(ids);
    case 1 -> CombatPackets.bind(1);
    case 2 -> CombatPackets.select(ids.subList(0, 1));
    case 3 -> CombatPackets.bind(2);
    default -> false;
};
`,
  }),
});

test("view from above: build and RTS controls along the edges, at three GUI scales and a narrow window", async ({}, testInfo) => {
  test.setTimeout(BUDGET.heavy);
  const id = newRunId();
  const run = new VisualRun("overhead-layout");
  let pair: E2EPair | undefined;
  try {
    pair = await launchPair({
      server: { instance: `visual-overhead-layout-server-${id}`, trace: tracePath("visual", "overhead-layout-server") },
      client: { instance: `visual-overhead-layout-client-${id}`, trace: tracePath("visual", "overhead-layout-client") },
    });
    const { server, client } = pair;
    const player = await waitForGroundedPlayer(server);
    const origin = { x: Math.round(player.pos.x) + 4, y: Math.round(player.pos.y), z: Math.round(player.pos.z) + 4 };
    await mustCommand(server, "gamerule doMobSpawning false");
    await mustCommand(server, "time set noon");
    await layFloor(server, { x: origin.x - 20, z: origin.z - 10 }, { x: origin.x + 20, z: origin.z + 24 },
      origin.y, "minecraft:smooth_stone", 8);
    const { colony } = await foundBookColony(server, client, player.name, origin, 3);
    const residents = await server.run(PREPARE_RESIDENTS, { colony, ...origin, count: 3 });
    const gate = { ...origin, z: origin.z + 10, colony };
    await server.run(SET_UP_GATE, gate);
    await mustCommand(server, `tp ${player.name} ${origin.x + 0.5} ${origin.y} ${origin.z + 2.5} 0 0`);
    await tick.sprint(server, 20);
    await client.run(QUIET_TOASTS);

    const sweep = async (mode: string, subject: string) => {
      for (const size of SIZES) {
        await client.run(RESIZE, size);
        await client.run(QUIET_TOASTS);
        await frames(client, 8);
        await input.move(client, { x: 2, y: 2 });
        await frames(client, 3);
        await run.shot(client, `${mode}-${size.width}x${size.height}-gui${size.scale}`, {
          subject: `${subject}, ${size.width}x${size.height} at GUI scale ${size.scale}`, worldState: size, hard: true,
        });
      }
      await client.run(RESIZE, SIZES[0]);
      await frames(client, 8);
    };

    await tapV(client);
    await screen.waitFor(client, "OverheadScreen", { timeoutMs: 8_000 });
    await screen.clickWidget(client, { label: "Defense" });
    await frames(client, 4);
    await screen.clickWidget(client, { label: "Patrol route" });
    const view = await screen.current(client);
    const centre = { x: Math.round(view!.width / 2), y: Math.round(view!.height / 2) };
    await input.click(client, centre);
    await tick.sprint(server, 3);
    await input.click(client, { x: centre.x + 60, y: centre.y + 20 });
    await tick.sprint(server, 3);
    expect((await screen.widgets(client)).map((widget: any) => widget.label)).toContain("Set it up");
    await sweep("build", "Build mode drawing a patrol route: groups, tools, layers and the line's buttons");
    await input.key(client, { keyCode: 259 });
    await frames(client, 3);

    await pickRtsTool(client);
    await frames(client, 5);
    for (let step = 0; step < 4; step++) {
      expect(await client.run(BIND_GROUPS, { residents, step })).toBe(true);
      await tick.sprint(server, 4);
      await frames(client, 3);
    }
    const at = await client.run(PROJECT, { ...gate, y: gate.y + 1, entity: "" });
    await input.click(client, at, { button: "left" });
    await waitForPresent(server, client, "folkways.card.readout.lever");
    await sweep("rts", "RTS mode with a gate's command card and two bound groups");
  } catch (error) {
    pair?.failing(error);
    throw error;
  } finally {
    await pair?.teardown(testInfo);
  }
});
