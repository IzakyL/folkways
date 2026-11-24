import { expect, test } from "@playwright/test";
import { defineTask, javaTask, tick } from "@izakyl/blockwright-minecraft";
import { VisualRun } from "../shared/capture/visual-kit";
import { waitForGroundedPlayer, type Vec } from "../shared/colony/colony-founding";
import { BUDGET } from "../shared/harness/budgets";
import { frames } from "../shared/harness/bw-helpers";
import { tracePath } from "../shared/harness/out-paths";
import { launchPair, type E2EPair } from "../shared/harness/pair";
import { newRunId } from "../shared/harness/run-id";
import { waitForPresent } from "../shared/ui/book-cards";
import { panelElements } from "../ldlib2";

const OPEN_TOWER = defineTask<void, Vec>({
  name: "folkways.visual.airport-tower", side: "server",
  source: javaTask({
    imports: [
      "com.lowdragmc.lowdraglib2.gui.factory.BlockUIMenuType",
      "io.github.izakyl.folkways.airline.AirlineContent",
      "io.github.izakyl.folkways.airline.Airports",
      "net.minecraft.world.level.block.Blocks",
    ],
    body: `
var player = ctx.server().getPlayerList().getPlayers().getFirst();
var level = player.serverLevel();
var tower = player.blockPosition().north(3);
level.setBlockAndUpdate(tower, AirlineContent.AIRPORT_TOWER.get().defaultBlockState());
Airports.register(level, tower);
Airports.rename(level, tower, "Harbor Airfield");
for (int along = 0; along < 48; along++) {
    for (int side = -2; side <= 2; side++) {
        var marking = Math.abs(side) == 2 || side == 0 && along % 8 < 4;
        level.setBlockAndUpdate(tower.offset(along - 24, -1, side - 16),
            (marking ? Blocks.WHITE_CONCRETE : Blocks.GRAY_CONCRETE).defaultBlockState());
    }
}
Airports.request(level, tower);
BlockUIMenuType.openUI(player, tower);
return Map.of("x", tower.getX(), "y", tower.getY(), "z", tower.getZ());
`,
  }),
});

test("airport tower and chart", async ({}, testInfo) => {
  test.setTimeout(BUDGET.standard);
  const id = newRunId();
  const run = new VisualRun("airport");
  let pair: E2EPair | undefined;
  try {
    pair = await launchPair({
      config: "blockwright.toml#airline",
      server: { instance: `visual-airport-server-${id}`, trace: tracePath("visual", "airport-server") },
      client: { instance: `visual-airport-client-${id}`, trace: tracePath("visual", "airport-client") },
    });
    const { server, client } = pair;
    await waitForGroundedPlayer(server);
    const tower = await server.run(OPEN_TOWER);
    await waitForPresent(server, client, "folkways.airline.airport.map");
    await tick.sprint(server, 80);
    await frames(client, 8);
    expect((await panelElements(client)).find(element => element.id === "folkways.airline.airport.map")?.height)
      .toBeGreaterThan(50);
    await run.shot(client, "tower-map", { subject: "Airport tower and its chart", worldState: { tower }, hard: true });
  } catch (error) {
    pair?.failing(error);
    throw error;
  } finally {
    await pair?.teardown(testInfo);
  }
});
