import { defineTask, javaTask, type MinecraftClient, type MinecraftServer } from "@izakyl/blockwright-minecraft";
import { clickElement } from "../../ldlib2";
import { waitForElement } from "../harness/bw-helpers";
import { settlePanel, typeCell } from "./site-panel";

export async function staffRoutePanel(server: MinecraftServer, client: MinecraftClient, player: string) {
  const profile = await server.run(PREPARE, { player });
  await waitForElement(client, { id: "folkways.staffing.allowed" });
  await clickElement(client, { id: "folkways.staffing.allowed" });
  await clickElement(client, { id: `folkways.choice.option.${profile}` });
  await clickElement(client, { id: "folkways.choice.confirm" });
  await settlePanel(server, client);
  await typeCell(server, client, "folkways.staffing.headcount_label", "1");
  await settlePanel(server, client);
}

const PREPARE = defineTask<{ player: string }, string>({
  name: "folkways.staffing.prepare-route", side: "server", source: javaTask({
    imports: ["io.github.izakyl.folkways.core.front.api.ui.Pages",
      "io.github.izakyl.folkways.core.front.ui.screen.ColonyShell",
      "io.github.izakyl.folkways.vanilla.combat.weapon.LoadoutProfiles",
      "io.github.izakyl.folkways.vanilla.combat.weapon.LoadoutProfile",
      "io.github.izakyl.folkways.vanilla.patrol.PatrolContent",
      "io.github.izakyl.folkways.core.item.api.ItemSpec", "net.minecraft.world.item.Items"],
    body: `var player=ctx.server().getPlayerList().getPlayerByName(Args.of(ctx).string("player"));
var colony=Pages.colonyOf(player).orElseThrow();var profiles=LoadoutProfiles.of(colony);
var profile=profiles.create("Route guard");
profiles.add(profile.id(),LoadoutProfile.Entry.weapon(ItemSpec.of(Items.STONE_SWORD)));
profiles.assign(colony.residents().stream().map(resident->resident.id()).toList(),Optional.of(profile.id()));
var route=colony.claims().stream().filter(holding->holding.owner().equals(PatrolContent.BEAT))
 .findFirst().orElseThrow();
ColonyShell.editSelection(player,route.id());return profile.id().toString();`,
  }),
});
