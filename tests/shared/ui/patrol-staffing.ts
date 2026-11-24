import { defineTask, javaTask, tick, type MinecraftClient, type MinecraftServer } from "@izakyl/blockwright-minecraft";

/**
 * Staffs the colony's patrol route with one guard of a fresh loadout profile every resident wears. The staffing is set
 * on the server, as the route card's controls set it (profile-staffing.spec drives those); what the spec watches is
 * the guard walking the route.
 */
export async function staffRoutePanel(server: MinecraftServer, _client: MinecraftClient, player: string) {
  const profile = await server.run(STAFF, { player });
  await tick.sprint(server, 3);
  return profile;
}

const STAFF = defineTask<{ player: string }, string>({
  name: "folkways.staffing.staff-route", side: "server", source: javaTask({
    imports: ["io.github.izakyl.folkways.core.front.api.ui.Pages",
      "io.github.izakyl.folkways.vanilla.combat.weapon.LoadoutProfiles",
      "io.github.izakyl.folkways.vanilla.combat.weapon.LoadoutProfile",
      "io.github.izakyl.folkways.vanilla.patrol.PatrolContent",
      "io.github.izakyl.folkways.vanilla.patrol.PatrolStaffing",
      "io.github.izakyl.folkways.core.item.api.ItemSpec", "net.minecraft.world.item.Items"],
    body: `var player=ctx.server().getPlayerList().getPlayerByName(Args.of(ctx).string("player"));
var colony=Pages.colonyOf(player).orElseThrow();var profiles=LoadoutProfiles.of(colony);
var profile=profiles.create("Route guard");
profiles.add(profile.id(),LoadoutProfile.Entry.weapon(ItemSpec.of(Items.STONE_SWORD)));
profiles.assign(colony.residents().stream().map(resident->resident.id()).toList(),Optional.of(profile.id()));
var route=colony.claims().stream().filter(holding->holding.owner().equals(PatrolContent.BEAT))
 .findFirst().orElseThrow();
PatrolStaffing.of(colony).configure(player.serverLevel(),route.id(),Set.of(profile.id()),1);
return profile.id().toString();`,
  }),
});
