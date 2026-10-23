import { defineTask, javaTask } from "@izakyl/blockwright-minecraft";
import { runColonyTask, MARK_MEMBERS } from "../shared/colony/colony-tasks";
import type { Scene } from "./cookery-scene";

/** Supply construction materials and delegate an empty plot. Residents build and plant every vine. */
export async function stageVineyard(scene: Scene, colony_id: string) {
  const supplies = scene.at(-16, 7);
  await scene.set(supplies, 'minecraft:chest{Items:[{Slot:0b,id:"kaleidoscope_tavern:grapevine",count:24},{Slot:1b,id:"kaleidoscope_tavern:trellis",count:64}]}');
  await runColonyTask(scene.server, MARK_MEMBERS, {
    player: scene.name, colony_id, cells: [supplies.x, supplies.y, supplies.z],
  });
  return runColonyTask(scene.server, VINEYARD, { colony_id, origin: scene.o, phase: "delegate" });
}

/** Only advance native growth between samples; planting, harvesting and hauling belong to residents. */
export async function growVineyard(scene: Scene, colony_id: string) {
  return runColonyTask(scene.server, VINEYARD, { colony_id, origin: scene.o, phase: "grow" });
}

const VINEYARD = defineTask<Record<string, any>, any>({
  name: "folkways.promo.vineyard", side: "server", timeoutMs: 20_000,
  source: javaTask({ imports: [
    "com.github.ysbbbbbb.kaleidoscopetavern.block.plant.GrapevineTrellisBlock",
    "com.github.ysbbbbbb.kaleidoscopetavern.block.plant.GrapeCropBlock",
    "io.github.izakyl.folkways.core.api.colony.Colonies",
    "io.github.izakyl.folkways.front.engine.colony.ColonyFront",
    "io.github.izakyl.folkways.front.engine.colony.ColonySettings",
    "io.github.izakyl.folkways.plugins.farming.FarmingContent",
    "io.github.izakyl.folkways.plugins.farming.HarvestMethods",
    "net.minecraft.resources.ResourceLocation", "net.minecraft.core.BlockPos",
  ], body: `
var args = Args.of(ctx);
var level = ctx.server().overworld();
var origin = args.blockPos("origin");
var phase = args.string("phase");
if (phase.equals("delegate")) {
    var colony = Colonies.of(ctx.server(), UUID.fromString(args.string("colony_id"))).orElseThrow();
    var cells = new java.util.LinkedHashSet<BlockPos>();
    for (var pos : BlockPos.betweenClosed(origin.offset(-15, -1, -5), origin.offset(-8, -1, 5))) cells.add(pos.immutable());
    var settings = ColonySettings.empty().with(FarmingContent.CROP,
        new ColonySettings.Value.Choice(ResourceLocation.parse("kaleidoscope_tavern:grapevine_trellis")))
        .with(FarmingContent.HARVEST_MODE, new ColonySettings.Value.Choice(HarvestMethods.PICK));
    var zone = ColonyFront.of(colony).addZone(level, FarmingContent.PLOT, cells, settings).orElseThrow();
    return Map.of("zone", zone.id().toString(), "plants", 12, "grapesSupplied", 0);
}
int vines = 0, ripe = 0;
for (var pos : BlockPos.betweenClosed(origin.offset(-15, 0, -5), origin.offset(-8, 3, 5))) {
    var state = level.getBlockState(pos);
    if (state.getBlock() instanceof GrapevineTrellisBlock vine) {
        vines++;
        vine.performBonemeal(level, level.random, pos.immutable(), state);
    } else if (state.getBlock() instanceof GrapeCropBlock crop) {
        if (!crop.isMaxAge(state)) crop.performBonemeal(level, level.random, pos.immutable(), state);
        if (crop.isMaxAge(level.getBlockState(pos))) ripe++;
    }
}
return Map.of("vines", vines, "ripe", ripe, "growth", "native-bonemeal");
` }),
});
