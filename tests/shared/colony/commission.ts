// Patterns commissioned the way a player draws them: a zone framed or a path marked, the knobs set, and the colony's
// build site files what the pattern draws there (or keeps growing it round by round, for a mine). Each site's answer
// says whether it was filed and what the colony said.
import { defineTask, javaTask, type MinecraftServer } from "@izakyl/blockwright-minecraft";
import { runColonyTask } from "./colony-tasks";

export type Cell = { x: number; y: number; z: number };
export type Commission = { pattern: string; name: string; points?: Cell[]; min?: Cell; max?: Cell; knobs?: Record<string, unknown> };

export async function commissionAll(server: MinecraftServer, colony: string, sites: Commission[], by = "bench") {
  return runColonyTask(server, COMMISSION, { colony_id: colony, sites, by }) as Promise<{ said: Array<{ pattern: string; filed: boolean; said: string }> }>;
}

export const COMMISSION = defineTask<Record<string, any>, Record<string, any>>({
  name: "folkways.shared.commission",
  side: "server",
  timeoutMs: 20_000,
  source: javaTask({
    imports: [
      "io.github.izakyl.folkways.front.engine.colony.ColonyGround",
      "io.github.izakyl.folkways.plugins.build.Commissions",
      "io.github.izakyl.folkways.plugins.build.draft.Hint",
      "io.github.izakyl.folkways.plugins.build.draft.Patterns",
      "io.github.izakyl.folkways.front.api.Schema",
      "io.github.izakyl.folkways.core.api.terms.ItemSpec",
      "net.minecraft.core.registries.Registries",
      "net.minecraft.tags.TagKey",
      "net.minecraft.core.BlockPos",
      "net.minecraft.core.Direction",
      "net.minecraft.nbt.CompoundTag",
      "net.minecraft.resources.ResourceLocation",
    ],
    body: `
var server=ctx.server();var level=server.overworld();var args=Args.of(ctx);
var colony=ColonyGround.of(server,UUID.fromString(args.string("colony_id"))).orElseThrow();
var said=new ArrayList<Object>();
for(var raw:args.list("sites")){
  var site=(Map<?,?>)raw;
  Hint hint=site.containsKey("points")
    ? new Hint.Path(((List<?>)site.get("points")).stream().map(Task::pos).toList())
    : new Hint.Zone(pos(site.get("min")),pos(site.get("max")));
  var id=ResourceLocation.parse(String.valueOf(site.get("pattern")));
  var result=Commissions.commission(level,colony,id,hint.save(),defaults(id,site.containsKey("knobs")?(Map<?,?>)site.get("knobs"):Map.of()),
    Direction.NORTH,String.valueOf(site.get("name")),Optional.empty(),args.string("by"));
  said.add(Map.of("pattern",String.valueOf(site.get("pattern")),"filed",result.filed(),"said",result.message().getString()));
}
return Sync.stamp(Map.of("ok",true,"said",said));
`,
    members: `
static BlockPos pos(Object raw){var m=(Map<?,?>)raw;
  return new BlockPos(((Number)m.get("x")).intValue(),((Number)m.get("y")).intValue(),((Number)m.get("z")).intValue());}
static CompoundTag defaults(ResourceLocation id,Map<?,?> picked){
  var tag=new CompoundTag();
  for(var setting:Patterns.find(id).orElseThrow().knobs().settings()){
    if(setting instanceof Schema.Setting.Choice choice&&picked.containsKey(choice.key())){
      var word=String.valueOf(picked.get(choice.key()));
      choice.options().stream().filter(option->option.getPath().equals(word)).findFirst()
        .ifPresent(option->tag.putString(choice.key(),option.toString()));
      continue;
    }
    if(setting instanceof Schema.Setting.Count count&&picked.containsKey(count.key())){
      tag.putInt(count.key(),((Number)picked.get(count.key())).intValue());
      continue;
    }
    if(setting instanceof Schema.Setting.Flag flag&&picked.containsKey(flag.key())){
      tag.putBoolean(flag.key(),Boolean.TRUE.equals(picked.get(flag.key())));
      continue;
    }
    if(!(setting instanceof Schema.Setting.Items items))continue;
    var specs=new ArrayList<ItemSpec>();
    if(picked.get(items.key()) instanceof List<?> ids){
      for(var one:ids)specs.add(ItemSpec.of(ResourceLocation.parse(String.valueOf(one))));
    }else{
      for(var filter:items.byDefault())specs.add(filter.tag()?ItemSpec.of(TagKey.create(Registries.ITEM,filter.id())):ItemSpec.of(filter.id()));
    }
    if(specs.isEmpty())continue;
    tag.put(items.key(),ItemSpec.anyOf(specs).save());
  }
  return tag;
}
`,
  }),
});

