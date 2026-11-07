import { defineTask, javaTask } from "@izakyl/blockwright-minecraft";

// Same schema defaults and commission entry point as pattern-construction.take.ts.
const PATTERN_MEMBERS = `
static BlockPos pos(Object raw){var m=(Map<?,?>)raw;
  return new BlockPos(((Number)m.get("x")).intValue(),((Number)m.get("y")).intValue(),((Number)m.get("z")).intValue());}

static CompoundTag defaults(ResourceLocation id,Map<?,?> picked){
  var tag=new CompoundTag();
  for(var setting:Patterns.find(id).orElseThrow().knobs().settings()){
    if (setting instanceof Schema.Setting.Flag flag && picked.containsKey(flag.key())) {
      tag.putBoolean(flag.key(), Boolean.TRUE.equals(picked.get(flag.key())));
      continue;
    }
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
    if (!(setting instanceof Schema.Setting.Items items)) { continue; }
    var specs=new ArrayList<ItemSpec>();
    if(picked.get(items.key()) instanceof List<?> ids){
      for (var one : ids) { specs.add(ItemSpec.of(ResourceLocation.parse(String.valueOf(one)))); }
    }else{
      for (var filter : items.byDefault()) {
        specs.add(filter.tag() ? ItemSpec.of(TagKey.create(Registries.ITEM, filter.id())) : ItemSpec.of(filter.id()));
      }
    }
    if (specs.isEmpty()) { continue; }
    tag.put(items.key(),ItemSpec.anyOf(specs).save());
  }
  return tag;
}
`;

export const HEIGHT = defineTask<Record<string, any>, Record<string, any>>({
  name: "folkways.construction.height", side: "server", source: javaTask({
    imports: [
      "net.minecraft.world.level.levelgen.Heightmap",
      "net.minecraft.core.BlockPos",
      "net.minecraft.core.Direction",
      "net.minecraft.world.level.block.Blocks",
      "net.minecraft.world.level.block.BedBlock",
      "net.minecraft.world.level.block.state.properties.BedPart",
      "net.minecraft.world.Container",
      "net.minecraft.world.item.Items",
      "net.minecraft.world.item.ItemStack",
    ],
    body: `
var level=ctx.server().overworld();int y=64;int depth=(int)Args.of(ctx).integer("depth");
// The construction take's work yard, raised above the bedrock-only fixture world.
for (int x = -32; x <= 64; x++) {
  for (int z = -24; z <= 40; z++) {
    for (int h = y - depth; h < y; h++) {
      level.setBlock(new BlockPos(x, h, z), (h == y - 1 ? Blocks.GRASS_BLOCK : Blocks.DIRT).defaultBlockState(), 2);
    }
  }
}
var members=new ArrayList<Object>();
for(int x:new int[]{-6,-8}){var at=new BlockPos(x,y,0);level.setBlock(at,Blocks.CHEST.defaultBlockState(),2);
 members.add(Map.of("x",x,"y",y,"z",0));
 if (x == -8) {
   var chest = (Container) level.getBlockEntity(at);
   for (int slot = 0; slot < 27; slot++) { chest.setItem(slot, new ItemStack(Items.BREAD, 64)); }
   chest.setChanged();
 }
}
for(int i=0;i<6;i++){var at=new BlockPos(-8+i*3,y,-5);
 level.setBlock(at,Blocks.WHITE_BED.defaultBlockState().setValue(BedBlock.FACING,Direction.NORTH)
   .setValue(BedBlock.PART,BedPart.FOOT),3);
 level.setBlock(at.north(),Blocks.WHITE_BED.defaultBlockState().setValue(BedBlock.FACING,Direction.NORTH)
   .setValue(BedBlock.PART,BedPart.HEAD),3);
 members.add(Map.of("x",at.getX(),"y",y,"z",at.getZ()));}
return Sync.stamp(Map.of("y",y,"members",members));`,
  }),
});

export const CREW = defineTask<Record<string, any>, Record<string, any>>({
  name: "folkways.construction.crew", side: "server", source: javaTask({
    imports: [
      "io.github.izakyl.folkways.front.engine.colony.ColonyGround",
      "io.github.izakyl.folkways.core.resident.api.Bodies",
      "io.github.izakyl.folkways.core.vocation.api.Vocations",
      "net.minecraft.world.item.Items",
      "net.minecraft.world.item.ItemStack",
    ],
    body: `
var level=ctx.server().overworld();var args=Args.of(ctx);
var colony=ColonyGround.of(ctx.server(),UUID.fromString(args.string("colony_id"))).orElseThrow();int index=0;
for(var resident:colony.residents()){
  var entity=level.getEntity(resident.id());var body=Bodies.of(entity).orElseThrow();
  for (var vocation : Vocations.all()) {
    body.licences().trade(vocation).ifPresent(trade ->
      body.licences().allow(trade, Set.of("building", "hauling").contains(vocation.id().getPath())));
  }
  body.pack().insert(new ItemStack(Items.BREAD,32));
  body.pack().insert(new ItemStack(Items.IRON_PICKAXE));body.pack().insert(new ItemStack(Items.IRON_AXE));
  body.pack().insert(new ItemStack(Items.IRON_SHOVEL));
  entity.teleportTo(args.number("stageX")-3+index++*2,args.number("y"),0.5);
}
return Sync.stamp(Map.of("residents",index));`,
  }),
});

export const COMMISSION = defineTask<Record<string, any>, Record<string, any>>({
  name: "folkways.promo.patterns-commission",
  side: "server",
  timeoutMs: 360_000,
  source: javaTask({
    imports: [
      "io.github.izakyl.folkways.front.engine.colony.ColonyGround",
      "io.github.izakyl.folkways.plugins.build.Commissions",
      "io.github.izakyl.folkways.plugins.build.draft.Hint",
      "io.github.izakyl.folkways.plugins.build.draft.Patterns",
      "io.github.izakyl.folkways.front.api.Schema",
      "io.github.izakyl.folkways.core.item.api.ItemSpec",
      "net.minecraft.core.registries.Registries",
      "net.minecraft.tags.TagKey",
      "net.minecraft.core.BlockPos",
      "net.minecraft.core.Direction",
      "net.minecraft.nbt.CompoundTag",
      "net.minecraft.resources.ResourceLocation",
      "net.minecraft.server.MinecraftServer",
      "java.util.concurrent.CompletableFuture",
    ],
    body: `
var server=ctx.server();var level=server.overworld();var args=Args.of(ctx);
var colony=ColonyGround.of(server,UUID.fromString(args.string("colony_id"))).orElseThrow();
var said=new ArrayList<Object>();boolean[] all={true};
// Each site is drawn off the server's thread and filed once it comes back; the next is asked for after it.
CompletableFuture<Void> filed=CompletableFuture.completedFuture(null);
for(var raw:args.list("sites")){
  var site=(Map<?,?>)raw;
  Hint hint=site.containsKey("points")
    ? new Hint.Path(((List<?>)site.get("points")).stream().map(Task::pos).toList())
    : new Hint.Zone(pos(site.get("min")),pos(site.get("max")));
  var id=ResourceLocation.parse(String.valueOf(site.get("pattern")));
  var picked = site.containsKey("knobs") ? (Map<?, ?>) site.get("knobs") : Map.of();
  filed=filed.thenCompose(done -> Commissions.commission(level, colony, id, hint.save(), defaults(id, picked),
      Direction.NORTH, "", Optional.empty(), "candb-promo"))
    .thenAccept(result->{
      all[0]&=result.filed();
      said.add(Map.of("pattern", String.valueOf(site.get("pattern")), "filed", result.filed(),
        "said", result.message().getString()));
    });
}
return filed.thenApply(done->{
  if (!all[0]) { throw new TaskException("refused", "not every commission was filed", Map.of("said", said)); }
  return Sync.stamp(Map.of("ok",true,"said",said));
});
`,
    members: PATTERN_MEMBERS,
  }),
});

export const STOCK_ITEMS = defineTask<Record<string, any>, Record<string, any>>({
  name: "folkways.promo.patterns-stock-items",
  side: "server",
  timeoutMs: 360_000,
  source: javaTask({
    imports: [
      "net.minecraft.core.BlockPos",
      "net.minecraft.core.registries.BuiltInRegistries",
      "net.minecraft.resources.ResourceLocation",
      "net.minecraft.server.MinecraftServer",
      "net.minecraft.world.Container",
      "net.minecraft.world.item.ItemStack",
    ],
    body: `
var server=ctx.server();var level=server.overworld();var args=Args.of(ctx);
var stocked=new LinkedHashMap<String,Integer>();
var chests=new ArrayList<Container>();
for(var raw:args.list("chests")){var m=(Map<?,?>)raw;
  chests.add((Container) level.getBlockEntity(new BlockPos(((Number) m.get("x")).intValue(),
    ((Number) m.get("y")).intValue(), ((Number) m.get("z")).intValue())));
}
var items=(Map<?,?>)args.value("items");
for(var entry:items.entrySet()){
  var item=BuiltInRegistries.ITEM.get(ResourceLocation.parse(String.valueOf(entry.getKey())));
  int left=((Number)entry.getValue()).intValue()+8;
  for(var chest:chests){
    for(int slot=0;slot<chest.getContainerSize()&&left>0;slot++){
      if (!chest.getItem(slot).isEmpty()) { continue; }
      int count=Math.min(left,item.getDefaultMaxStackSize());
      chest.setItem(slot,new ItemStack(item,count));left-=count;
      stocked.merge(entry.getKey().toString(),count,Integer::sum);
    }
    chest.setChanged();
    if (left <= 0) { break; }
  }
}
return Sync.stamp(Map.of("ok",true,"stocked",stocked));
`,
  }),
});

export const GALLERY = defineTask<Record<string, any>, Record<string, any>>({
  name: "folkways.promo.candb-gallery", side: "server", timeoutMs: 360_000,
  source: javaTask({
    imports: [
      "io.github.izakyl.folkways.plugins.build.draft.Patterns",
      "io.github.izakyl.folkways.plugins.build.draft.Hint",
      "io.github.izakyl.folkways.plugins.build.draft.Chosen",
      "io.github.izakyl.folkways.plugins.build.draft.Commission",
      "io.github.izakyl.folkways.plugins.build.draft.Drafting",
      "io.github.izakyl.folkways.plugins.build.draft.Drawn",
      "io.github.izakyl.folkways.plugins.build.draft.World",
      "io.github.izakyl.folkways.front.api.Schema",
      "io.github.izakyl.folkways.core.item.api.ItemSpec",
      "io.github.izakyl.folkways.core.work.api.StructureCompat",
      "io.github.izakyl.folkways.core.work.api.Voxelizer",
      "net.minecraft.core.BlockPos",
      "net.minecraft.core.Direction",
      "net.minecraft.nbt.CompoundTag",
      "net.minecraft.resources.ResourceLocation",
      "net.minecraft.core.registries.Registries",
      "net.minecraft.tags.TagKey",
    ],
    members: PATTERN_MEMBERS,
    body: `
var level = ctx.server().overworld();
var reports = new ArrayList<Object>();
for (var raw : Args.of(ctx).list("sites")) {
    var site = (Map<?, ?>) raw;
    var id = ResourceLocation.parse(String.valueOf(site.get("pattern")));
    var pattern = Patterns.find(id).orElseThrow();
    Hint hint = site.containsKey("points")
        ? new Hint.Path(((List<?>) site.get("points")).stream().map(Task::pos).toList())
        : new Hint.Zone(pos(site.get("min")), pos(site.get("max")));
    var commission = new Commission(hint, Chosen.load(pattern.knobs(), defaults(id, (Map<?, ?>) site.get("knobs"))),
        World.NONE, Direction.NORTH, 73);
    long began = System.nanoTime();
    var drawn = Drafting.draw(pattern, commission).join();
    if (!(drawn instanceof Drawn.Ready ready)) {
        throw new IllegalStateException(id + ": " + drawn);
    }
    int partial = 0;
    try (var processing = Voxelizer.installed().orElseThrow().drawing()) {
        for (var cell : ready.draft().cells()) {
            var at = ready.corner().offset(cell.offset());
            level.setBlock(at, cell.state(), 3);
            if (cell.payload().present()) {
                partial++;
                var entity = StructureCompat.of(cell.state()).orElseThrow()
                    .detached(level.registryAccess(), at, cell.state(), cell.payload());
                level.setBlockEntity(entity);
                entity.setChanged();
                level.sendBlockUpdated(at, cell.state(), cell.state(), 3);
            }
        }
    }
    reports.add(Map.of("pattern", id.toString(), "cells", ready.draft().cells().size(), "partial", partial,
        "drawMillis", (System.nanoTime() - began) / 1_000_000));
}
return Sync.stamp(Map.of("reports", reports));
` }),
});

export const AUDIT = defineTask<Record<string, any>, Record<string, any>>({
  name: "folkways.promo.candb-audit", side: "server", timeoutMs: 60_000,
  source: javaTask({
    imports: [
      "io.github.izakyl.folkways.front.engine.colony.ColonyGround",
      "io.github.izakyl.folkways.plugins.build.Blueprint",
      "io.github.izakyl.folkways.plugins.build.BlueprintBuildOrder",
      "io.github.izakyl.folkways.core.work.api.StructureCompat",
      "io.github.izakyl.folkways.core.world.api.StructurePayload",
      "io.github.izakyl.folkways.core.resident.api.Bodies",
      "net.minecraft.core.BlockPos",
      "net.minecraft.world.level.block.state.BlockState",
      "net.minecraft.resources.ResourceLocation",
      "net.minecraft.core.registries.BuiltInRegistries",
      "net.minecraft.world.Container",
      "net.minecraft.world.item.ItemStack",
      "io.github.izakyl.folkways.core.item.api.ItemSpec",
    ],
    members: `
static Map<BlockPos, BlockState> states = new LinkedHashMap<>();
static Map<BlockPos, StructurePayload> payloads = new LinkedHashMap<>();
static Map<String, Integer> owed = new LinkedHashMap<>();
static Set<Object> nodes = new HashSet<>();
static Object field(Object target, String name) throws Exception {
    var f = target.getClass().getDeclaredField(name);
    f.setAccessible(true);
    return f.get(target);
}

static void count(Container inventory, Map<String, Integer> counts) {
    for (int slot = 0; slot < inventory.getContainerSize(); slot++) {
        var stack = inventory.getItem(slot);
        if (!stack.isEmpty()) {
            counts.merge(BuiltInRegistries.ITEM.getKey(stack.getItem()).toString(), stack.getCount(), Integer::sum);
        }
    }
}
`,
    body: `
var args = Args.of(ctx);
var level = ctx.server().overworld();
var colony = ColonyGround.of(ctx.server(), UUID.fromString(args.string("colony_id"))).orElseThrow();
var presence = colony.service(ResourceLocation.parse("folkways:build"), Object.class).orElseThrow();
var book = field(presence, "book");
var drawings = (Map<UUID, Blueprint>) field(book, "blueprints");
var orders = (Map<UUID, BlueprintBuildOrder>) field(book, "orders");
for (var order : orders.values()) {
    var drawing = drawings.get(order.blueprintId());
    if (drawing == null) { continue; }
    for (var block : drawing.blocks()) {
        var at = order.anchor().cell().offset(block.offset());
        states.put(at, block.state());
        if (block.data().payload(block.state()).present()) { payloads.put(at, block.data().payload(block.state())); }
    }
}
var currentOwed = new LinkedHashMap<String, Integer>();
for (var needs : ((Map<?, ?>) field(presence, "owed")).values()) {
    for (var entry : ((Map<?, ?>) needs).entrySet()) {
        var id = ((ItemSpec) entry.getKey()).item().orElseThrow().toString();
        currentOwed.merge(id, ((Number) entry.getValue()).intValue(), Integer::sum);
    }
}
if (owed.isEmpty() && !currentOwed.isEmpty()) { owed.putAll(currentOwed); }
int correct = 0;
int partialCorrect = 0;
for (var entry : states.entrySet()) {
    var at = entry.getKey();
    if (level.getBlockState(at) != entry.getValue()) { continue; }
    if (payloads.containsKey(at)) {
        var entity = level.getBlockEntity(at);
        if (entity == null || !StructureCompat.of(entry.getValue()).orElseThrow().matches(entity, payloads.get(at))) {
            continue;
        }
        partialCorrect++;
    }
    correct++;
}
var stock = new LinkedHashMap<String, Integer>();
for (var raw : args.list("chests")) {
    var at = (Map<?, ?>) raw;
    count((Container) level.getBlockEntity(new BlockPos(((Number) at.get("x")).intValue(),
        ((Number) at.get("y")).intValue(), ((Number) at.get("z")).intValue())), stock);
}
var crew = new ArrayList<Object>();
for (var resident : colony.residents()) {
    var entity = level.getEntity(resident.id());
    if (entity != null) {
        var body = Bodies.of(entity).orElseThrow();
        count(body.pack(), stock);
        crew.add(Map.of("at", entity.position().toString(), "doing", body.doing().toString(),
            "pack", body.pack().contents().stream().filter(stack -> !stack.isEmpty()).map(Object::toString).toList()));
    }
}
var sites = new ArrayList<Object>();
for (var site : ((Map<?, ?>) field(presence, "sites")).values()) {
    nodes.addAll(((Map<?, ?>) field(site, "live")).keySet());
    nodes.addAll(((Map<?, ?>) field(site, "held")).keySet());
    var kinds = new LinkedHashMap<String, Integer>();
    for (var piece : ((Map<?, ?>) field(site, "live")).values()) {
        var work = piece.getClass().getDeclaredMethod("work");
        work.setAccessible(true);
        kinds.merge(work.invoke(piece).getClass().getSimpleName(), 1, Integer::sum);
    }
    var pieces = new ArrayList<Object>();
    if (((Map<?, ?>) field(site, "live")).size() <= 2) {
        for (var piece : ((Map<?, ?>) field(site, "live")).values()) {
            var positions = site.getClass().getDeclaredMethod("positions", level.getClass(), piece.getClass());
            positions.setAccessible(true);
            var work = field(piece, "work");
            pieces.add(Map.of("focus", field(work, "focus").toString(),
                "plannedStances", field(work, "stances").toString(),
                "doing", String.valueOf(field(piece, "doing")), "tries", field(piece, "tries"),
                "restUntil", field(piece, "restUntil"), "positions", positions.invoke(site, level, piece).toString()));
        }
    }
    sites.add(Map.of("live", ((Map<?, ?>) field(site, "live")).size(), "kinds", kinds,
        "refusals", field(site, "refused").toString(), "pieces", pieces));
}
var out = new LinkedHashMap<String, Object>();
var voxels = new LinkedHashMap<String, Long>();
for (var entry : states.entrySet()) {
    if (payloads.containsKey(entry.getKey())) {
        var volume = StructureCompat.of(entry.getValue()).orElseThrow().voxels(payloads.get(entry.getKey()));
        volume.counts().forEach((item, count) ->
            voxels.merge(item.item().orElseThrow().toString(), count.longValue(), Long::sum));
    } else if (!entry.getValue().isAir()) {
        voxels.merge(BuiltInRegistries.BLOCK.getKey(entry.getValue().getBlock()).toString(), 4096L, Long::sum);
    }
}
var dropped = new LinkedHashMap<String, Integer>();
for (var entity : level.getAllEntities()) {
    if (entity instanceof net.minecraft.world.entity.item.ItemEntity item) {
        var stack = item.getItem();
        dropped.merge(BuiltInRegistries.ITEM.getKey(stack.getItem()).toString(), stack.getCount(), Integer::sum);
    }
}
int scaffolds = 0;
for (int x = -4; x <= 24; x++) {
    for (int z = 0; z <= 30; z++) {
        for (int y = 64; y <= 85; y++) {
            if (level.getBlockState(new BlockPos(x, y, z)).is(net.minecraft.world.level.block.Blocks.SCAFFOLDING)) {
                scaffolds++;
            }
        }
    }
}
out.put("scaffolds", scaffolds);
out.put("dropped", dropped);
out.put("voxels", voxels);
out.put("tick", level.getGameTime());
out.put("expected", states.size());
out.put("correct", correct);
out.put("partial", payloads.size());
out.put("partialCorrect", partialCorrect);
out.put("owed", owed);
out.put("currentOwed", currentOwed);
out.put("stock", stock);
out.put("sites", sites);
out.put("observedNodes", nodes.size());
out.put("orders", orders.size());
out.put("growing", ((Map<?, ?>) field(presence, "opening")).size());
out.put("crew", crew);
return Sync.stamp(out);
` }),
});

export const STOCK_ALL = defineTask<Record<string, any>, Record<string, any>>({
  name: "folkways.patternfilm.stock-all",
  side: "server",
  timeoutMs: 20_000,
  source: javaTask({
    imports: [
      "net.minecraft.core.BlockPos",
      "io.github.izakyl.folkways.front.engine.colony.ColonyGround",
      "net.minecraft.core.registries.BuiltInRegistries",
      "net.minecraft.resources.ResourceLocation",
      "net.minecraft.server.MinecraftServer",
      "net.minecraft.world.Container",
      "net.minecraft.world.item.ItemStack",
    ],
    body: `
var server=ctx.server();var level=server.overworld();var args=Args.of(ctx);
var stocked=new LinkedHashMap<String,Integer>();
var unstocked=new LinkedHashMap<String,Integer>();
var chests=new ArrayList<Container>();
for(var raw:args.list("chests")){var m=(Map<?,?>)raw;
  chests.add((Container) level.getBlockEntity(new BlockPos(((Number) m.get("x")).intValue(),
    ((Number) m.get("y")).intValue(), ((Number) m.get("z")).intValue())));
}
var colony=ColonyGround.of(server,UUID.fromString(args.string("colony_id"))).orElseThrow();
var presence=colony.service(ResourceLocation.parse("folkways:build"),Object.class).orElseThrow();
var f=presence.getClass().getDeclaredField("owed");f.setAccessible(true);
var items=new LinkedHashMap<String,Integer>();
for (var owed : ((Map<?, ?>) f.get(presence)).values()) {
  for (var need : ((Map<?, ?>) owed).entrySet()) {
    var spec = (io.github.izakyl.folkways.core.item.api.ItemSpec) need.getKey();
    items.merge(spec.item().orElseThrow().toString(), ((Number) need.getValue()).intValue(), Integer::sum);
  }
}
for(var entry:items.entrySet()){
  var item=BuiltInRegistries.ITEM.get(ResourceLocation.parse(String.valueOf(entry.getKey())));
  int left=((Number)entry.getValue()).intValue()+16;
  for (var chest : chests) {
    for (int slot = 0; slot < chest.getContainerSize(); slot++) {
      if (chest.getItem(slot).is(item)) { left -= chest.getItem(slot).getCount(); }
    }
  }
  for(var chest:chests){
    for(int slot=0;slot<chest.getContainerSize()&&left>0;slot++){
      if (!chest.getItem(slot).isEmpty()) { continue; }
      int count=Math.min(left,item.getDefaultMaxStackSize());
      chest.setItem(slot,new ItemStack(item,count));left-=count;
      stocked.merge(entry.getKey().toString(),count,Integer::sum);
    }
    chest.setChanged();
    if (left <= 0) { break; }
  }
  if (left > 0) { unstocked.merge(entry.getKey().toString(), left, Integer::sum); }
}
return Sync.stamp(Map.of("ok",true,"stocked",stocked,"unstocked",unstocked));
`,
  }),
});

// Existing gameplay option: retain real commissions, hauling, carving costs and work time, without access scaffolds.
export const SIMPLE_CONSTRUCTION = defineTask<void, Record<string, any>>({
  name: "folkways.promo.candb-simple-construction", side: "server",
  source: javaTask({
    imports: [
      "io.github.izakyl.folkways.plugins.FolkwaysVanillaConfig",
      "net.neoforged.neoforge.common.ModConfigSpec",
    ],
    body: `
var field = FolkwaysVanillaConfig.class.getDeclaredField("SIMPLE_CONSTRUCTION");
field.setAccessible(true);
((ModConfigSpec.BooleanValue) field.get(null)).set(true);
return Sync.stamp(Map.of("simpleConstruction", true));
` }),
});
