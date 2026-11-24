import { expect, test } from "@playwright/test";
import { defineTask, input, javaTask, screen, tick, world } from "@izakyl/blockwright-minecraft";
import { panelElements } from "../ldlib2";
import { QUIET_TOASTS } from "../shared/capture/ponder";
import { VisualRun } from "../shared/capture/visual-kit";
import { foundColonyFast, openColonyPanelViaBook, waitForGroundedPlayer } from "../shared/colony/colony-founding";
import { GOLEM_CAST } from "../shared/colony/integration-tasks";
import { BUDGET } from "../shared/harness/budgets";
import { frames } from "../shared/harness/bw-helpers";
import { tracePath } from "../shared/harness/out-paths";
import { launchPair, type E2EPair } from "../shared/harness/pair";
import { newRunId } from "../shared/harness/run-id";
import { openCardOf, panelSection, textUnder, waitForPresent } from "../shared/ui/book-cards";
import { clickTab, openResidentDetail } from "../shared/ui/colony-pages";

const BLOCK_FIXTURES = ["minecraft:furnace", "kaleidoscope_cookery:stove",
  "create:white_postbox", "create:elevator_pulley", "minecraft:composter"];

const HOLDINGS = defineTask<{ blocks: string[] }, { cards: Array<{ kind: string; id: string }> }>({
  name: "folkways.visual.held-cards", side: "server",
  source: javaTask({
    imports: [
      "io.github.izakyl.folkways.core.colony.charter.CurrentColony",
      "io.github.izakyl.folkways.core.front.api.Shape",
      "io.github.izakyl.folkways.core.front.engine.Enrollments",
      "io.github.izakyl.folkways.core.front.engine.colony.ColonySettings",
      "io.github.izakyl.folkways.core.front.engine.colony.HeldFacilities",
      "io.github.izakyl.folkways.core.front.engine.colony.MemberToggle",
      "io.github.izakyl.folkways.core.front.engine.colony.ZoneDrafts",
      "net.minecraft.core.registries.BuiltInRegistries",
      "net.minecraft.resources.ResourceLocation",
    ],
    body: `
var player = ctx.server().getPlayerList().getPlayers().getFirst();
var level = player.serverLevel();
var colony = CurrentColony.of(player).orElseThrow();
var start = player.blockPosition().south(7);
int index = 0;
for (String block : Args.of(ctx).strings("blocks")) {
    var at = start.east(index++ * 2);
    var id = ResourceLocation.parse(block);
    var state = BuiltInRegistries.BLOCK.getOptional(id).orElseThrow().defaultBlockState();
    level.setBlockAndUpdate(at, state);
    var attempt = MemberToggle.toggleAt(level, colony, at, player);
    if (attempt.refusal().isPresent()) {
        throw new IllegalStateException(id + ": " + attempt.refusal().get().key());
    }
}
for (var delegation : Enrollments.delegations()) {
    if (delegation.shape() instanceof Shape.Path && delegation.disabledReason().isEmpty()) {
        var at = start.south(4 + index++);
        ZoneDrafts.path(level, colony, delegation.id(), List.of(at, at.east(5), at.east(7).south(3)),
            ColonySettings.empty()).orElseThrow();
    }
}
return Map.of("cards", HeldFacilities.all(colony).stream().map(held ->
    Map.of("id", held.id().toString(), "kind", held.kind().toString())).toList());
`,
  }),
});

const ARM_STATION = defineTask<void, boolean>({
  name: "folkways.visual.weapon-station", side: "server",
  source: javaTask({
    imports: [
      "io.github.izakyl.folkways.core.colony.charter.CurrentColony",
      "io.github.izakyl.folkways.core.front.engine.colony.MemberToggle",
      "net.minecraft.core.registries.BuiltInRegistries",
      "net.minecraft.resources.ResourceLocation",
    ],
    body: `
var player = ctx.server().getPlayerList().getPlayers().getFirst();
var level = player.serverLevel();
var kind = BuiltInRegistries.ENTITY_TYPE.getOptional(ResourceLocation.parse("superbwarfare:mortar")).orElseThrow();
var entity = kind.create(level);
var at = player.blockPosition().south(6);
entity.moveTo(at.getX() + 0.5, at.getY(), at.getZ() + 0.5);
level.addFreshEntity(entity);
var attempt = MemberToggle.toggleEntity(CurrentColony.of(player).orElseThrow(), entity, player);
if (attempt.refusal().isPresent()) {
    throw new IllegalStateException(attempt.refusal().get().key());
}
return true;
`,
  }),
});

test("cards for held blocks, points, routes and bodies", async ({}, testInfo) => {
  test.setTimeout(BUDGET.heavy);
  const id = newRunId();
  const run = new VisualRun("holding-cards");
  let pair: E2EPair | undefined;
  try {
    pair = await launchPair({
      config: "blockwright.toml#visual-holdings",
      server: { instance: `visual-holdings-server-${id}`, trace: tracePath("visual", "holdings-server") },
      client: { instance: `visual-holdings-client-${id}`, trace: tracePath("visual", "holdings-client") },
    });
    const { client, server } = pair;
    const player = await waitForGroundedPlayer(server);
    const origin = { x: Math.round(player.pos.x) + 4, y: Math.round(player.pos.y), z: Math.round(player.pos.z) + 4 };
    const { evidence, standBlock, foodChest } = await foundColonyFast(server, client, player.name, { origin, residentCount: 1 });
    await client.run(QUIET_TOASTS);
    // Counts of one, two and three digits beside the food, so the store's card shows each size of corner count.
    const chest = `${foodChest.x} ${foodChest.y} ${foodChest.z}`;
    await world.command(server, `item replace block ${chest} container.10 with minecraft:apple 7`);
    await world.command(server, `item replace block ${chest} container.11 with minecraft:bread 32`);
    await world.command(server, `item replace block ${chest} container.12 with minecraft:carrot 1`);
    const shot = async (name: string, subject: string, worldState = {}) => {
      await input.move(client, { x: 0, y: 0 });
      await frames(client, 5);
      await run.shot(client, name, { subject, worldState, hard: true });
    };
    const { cards } = await server.run(HOLDINGS, { blocks: BLOCK_FIXTURES });
    const byKind = new Map(cards.map(card => [card.kind, card.id]));
    expect([...byKind.keys()]).toEqual(expect.arrayContaining(
      ["bed", "station", "stove", "port", "elevator", "compost", "cart_line"].map(kind => `folkways_vanilla:${kind}`),
    ));
    for (const [kind, facility] of byKind) {
      await openCardOf(server, client, player.name, facility);
      await shot(`held-${kind.replace(":", "-")}`, `Facility card for ${kind}`, { kind, facility });
      await screen.dismiss(client);
      await tick.sprint(server, 3);
    }

    const cast = await server.run(GOLEM_CAST, {
      player: player.name, colony_id: evidence.colonyId, origin, kinds: ["metal"], freeze_humans: false,
    });
    expect(cast.uuids).toHaveLength(1);
    await server.run(ARM_STATION);
    await tick.sprint(server, 20);
    await openColonyPanelViaBook(server, client, player.name, standBlock);
    await clickTab(client, "folkways.tab.residents");
    const section = panelSection("folkways_vanilla:patrol", "RESIDENTS");
    await expect.poll(async () => {
      await tick.sprint(server, 5);
      await frames(client, 3);
      return textUnder(await panelElements(client), section);
    }, { timeout: 10_000, message: "The Patrol section must receive the held weapon station" }).toContain("Mortar");
    await waitForPresent(server, client, section);
    await shot("weapon-station", "Enrolled weapon station on the Workforce page");
    const name = `folkways.resident.name.${String(cast.uuids[0]).slice(0, 8)}`;
    await waitForPresent(server, client, name);
    await openResidentDetail(server, client, name);
    await waitForPresent(server, client, "folkways.resident.vocations");
    await shot("golem-body", "Resident detail window for an enrolled modular golem");
  } catch (error) {
    pair?.failing(error);
    throw error;
  } finally {
    await pair?.teardown(testInfo);
  }
});
