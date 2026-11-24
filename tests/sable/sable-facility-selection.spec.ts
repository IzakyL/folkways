import { expect, test } from "@playwright/test";
import { defineTask, input, javaTask, tick } from "@izakyl/blockwright-minecraft";
import { waitForElement } from "../ldlib2";
import { waitForGroundedPlayer } from "../shared/colony/colony-founding";
import { driveShip } from "../shared/colony/sable-probe";
import { foundBookColony, layFloor, openRtsTool } from "../shared/combat/overhead";
import { PROJECT } from "../shared/combat/rts-fixtures";
import { BUDGET } from "../shared/harness/budgets";
import { frames } from "../shared/harness/bw-helpers";
import { launchPair, type E2EPair } from "../shared/harness/pair";
import { newRunId } from "../shared/harness/run-id";
import { aboard } from "../shared/harness/stage";
import { mustCommand } from "../shared/ui/world-ui";

const BUTTON = { id: "folkways.card.choice.ready" };

test("RTS clicks and box-selects a facility after its Sable deck moves", async ({}, testInfo) => {
  test.setTimeout(BUDGET.standard);
  const stage = aboard();
  let pair: E2EPair | undefined;
  try {
    const run = newRunId();
    pair = await launchPair({
      config: stage.config,
      server: { instance: `sable-facility-server-${run}` },
      client: { instance: `sable-facility-client-${run}` },
    });
    const { server, client } = pair;
    const player = await waitForGroundedPlayer(server);
    const origin = { x: Math.round(player.pos.x) + 4, y: Math.round(player.pos.y), z: Math.round(player.pos.z) + 4 };
    await layFloor(server, { x: origin.x - 12, z: origin.z - 10 }, { x: origin.x + 16, z: origin.z + 22 }, origin.y);
    const { colony } = await foundBookColony(server, client, player.name, origin, 1);
    const deck = { ...origin, y: origin.y + 2, z: origin.z + 11 };
    await stage.lay(server, {
      min: { x: deck.x - 1, y: deck.y + 1, z: deck.z - 1 },
      max: { x: deck.x + 1, y: deck.y + 3, z: deck.z + 1 },
    });
    const structure = stage.structure!;
    await server.run(SET_UP_FACILITY, { colony, structure });
    await mustCommand(server, `tp ${player.name} ${origin.x + 0.5} ${origin.y} ${origin.z + 3.5} 0 0`);
    await tick.sprint(server, 10);
    await frames(client, 10);
    await openRtsTool(client);
    await tick.sprint(server, 20);
    await frames(client, 5);

    const at = await client.run(PROJECT, { ...deck, entity: "" });
    await input.click(client, { x: at.x, y: at.y }, { button: "left" });
    await waitForElement(client, BUTTON);
    await input.key(client, { keyCode: 256 });

    const move = await driveShip(server, structure, { x: 6, y: 1, z: 0 }, ticks => tick.sprint(server, ticks));
    expect(move.end.x - move.start.x).toBeGreaterThan(5);
    await tick.sprint(server, 10);
    await frames(client, 10);
    const moved = await client.run(PROJECT, {
      x: deck.x + Math.round(move.end.x - move.start.x),
      y: deck.y + Math.round(move.end.y - move.start.y),
      z: deck.z + Math.round(move.end.z - move.start.z),
      entity: "",
    });
    await input.click(client, { x: moved.x, y: moved.y }, { button: "left" });
    await waitForElement(client, BUTTON);
    await input.key(client, { keyCode: 256 });
    await input.drag(client, {
      from: { x: moved.x - 18, y: moved.y - 18 },
      to: { x: moved.x + 18, y: moved.y + 18 },
      steps: 8, button: "left",
    });
    await waitForElement(client, BUTTON);
  } catch (error) {
    pair?.failing(error);
    throw error;
  } finally {
    await pair?.teardown(testInfo);
  }
});

const SET_UP_FACILITY = defineTask<{ colony: string; structure: string }, boolean>({
  name: "folkways.sable-facility-selection.install", side: "server",
  source: javaTask({
    imports: [
      "io.github.izakyl.folkways.core.colony.api.Colonies",
      "io.github.izakyl.folkways.core.facility.api.DefinitionCodec",
      "io.github.izakyl.folkways.core.facility.api.FacilityExtent",
      "io.github.izakyl.folkways.core.facility.api.DefinedFacilities",
    ],
    body: `
var args = Args.of(ctx);
var server = ctx.server();
var colony = Colonies.of(server, UUID.fromString(args.string("colony"))).orElseThrow();
var facilities = DefinedFacilities.of(colony).orElseThrow();
var extent = FacilityExtent.structure(server.overworld(), UUID.fromString(args.string("structure"))).orElseThrow();
String name = "Survey deck";
var data = Map.of("name", name, "extent", DefinitionCodec.extent(extent),
  "parameters", Map.of("ready", "yes"),
  "ui", Map.of("card", List.of(Map.of("choice", "ready", "options", List.of("yes", "no")))));
var problems = facilities.apply(facilities.preview(name, Optional.of(data)));
if (!problems.isEmpty()) {
    throw new IllegalStateException(problems.toString());
}
return true;`,
  }),
});
