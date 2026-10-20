import { defineTask, javaTask, world, type MinecraftServer } from "@izakyl/blockwright-minecraft";
import { MARK_MEMBERS, runColonyTask } from "../shared/colony/colony-tasks";
import type { Vec } from "../shared/colony/colony-founding";
import { commandPos, tryCommand } from "../shared/harness/bw-helpers";
import { licenseCrewFast, setMaintainDemandsFast } from "./fast-setup";

const K = "kaleidoscope_cookery:";

function layout(origin: Vec) {
  const at = (x: number, y: number, z: number): Vec => ({ x: origin.x + x, y: origin.y + y, z: origin.z + z });
  return {
    stations: { board: at(-14, 1, -8), tea: at(-10, 1, -8), spit: at(-6, 0, -8) },
    sources: [at(-13, 0, -7), at(-11, 0, -7), at(-7, 0, -7)],
    targets: [at(-14, 0, -7), at(-5, 6, 0), at(-5, 0, -7)],
    posts: [at(-14, 0, -9), at(-10, 0, -9), at(-6, 0, -9)],
    products: [K + "sashimi", K + "barley_tea", "minecraft:cooked_rabbit"],
  };
}

/** Scope the filming server's existing recipe exclusions before any workstation is published. */
export async function configureMarketMenu(server: MinecraftServer) {
  return runColonyTask(server, MENU, {});
}

/** Ordinary maintain orders drive native cutting and the powered rotating spit; tea starts separately.
 * Initial supplies contain no requested products. All work and upstairs tea delivery belong to Folkways. */
export async function stageExtraKitchen(
  server: MinecraftServer, player: string, colony_id: string, origin: Vec, workersUUIDs: string[],
) {
  if (workersUUIDs.length !== 3) throw new Error("The extra kitchen requires three new workers");
  const plan = layout(origin);
  const at = (x: number, y: number, z: number): Vec => ({ x: origin.x + x, y: origin.y + y, z: origin.z + z });
  await tryCommand(server, `fill ${commandPos(at(-15, -1, -10))} ${commandPos(at(-4, -1, -7))} minecraft:smooth_sandstone`);
  await world.command(server, `setblock ${commandPos(at(-14, 0, -8))} minecraft:stripped_spruce_log[axis=x]`);
  await world.command(server, `setblock ${commandPos(plan.stations.board)} ${K}chopping_board[facing=north]`);
  await world.command(server, `fill ${commandPos(at(-11, 0, -8))} ${commandPos(at(-9, 0, -8))} minecraft:smooth_stone`);
  await world.command(server, `setblock ${commandPos(at(-10, 0, -8))} minecraft:magma_block`);
  await world.command(server, `setblock ${commandPos(plan.stations.tea)} ${K}teapot`);

  const contents: Array<Array<[string, number]>> = [
    [["minecraft:cod", 64], ["minecraft:cod", 64], ["minecraft:cod", 64],
      [K + "iron_kitchen_knife", 1], [K + "iron_kitchen_knife", 1]],
    [...Array.from({ length: 16 }, (): [string, number] => ["minecraft:water_bucket", 1]),
      [K + "barley_tea_bag", 64],
      ...Array.from({ length: 8 }, (): [string, number] => [K + "empty_cup", 16])],
    [["minecraft:rabbit", 64], ["minecraft:rabbit", 64],
      [K + "iron_kitchen_knife", 1], [K + "iron_kitchen_knife", 1]],
  ];
  for (let i = 0; i < plan.sources.length; i++) {
    const items = contents[i].map(([id, count], slot) => `{Slot:${slot}b,id:"${id}",count:${count}}`).join(",");
    await world.command(server, `setblock ${commandPos(plan.sources[i])} minecraft:barrel[facing=up]{Items:[${items}]}`);
  }
  for (const target of plan.targets) await world.command(server, `setblock ${commandPos(target)} minecraft:barrel[facing=up]`);
  const prepared = await runColonyTask(server, PREPARE, { colony_id, workers: workersUUIDs, ...plan });
  const members = [...Object.values(plan.stations), ...plan.sources, ...plan.targets];
  const marked = await runColonyTask(server, MARK_MEMBERS, {
    player, colony_id, cells: members.flatMap(cell => [cell.x, cell.y, cell.z]),
  });
  const licensed = await licenseCrewFast(server, workersUUIDs.map(uuid => ({ uuid, trades: ["crafting", "hauling"] })));
  const filters = await runColonyTask(server, FILTER_TARGETS, { colony_id, ...plan });
  // Start only cutting and roasting here. The director files the twelve-cup tea order
  // after the native spit has started, so brewing and upstairs delivery happen during the shot.
  const demands = [
    { at: plan.targets[0], item: plan.products[0], count: 24, low: 12 },
    { at: plan.targets[2], item: plan.products[2], count: 8, low: 8 },
  ];
  const orders = await setMaintainDemandsFast(server, player, demands);
  return { ...plan, members, workers: workersUUIDs, prepared, marked, licensed, filters, demands, orders,
    extraBeds: 3, initialProducts: 0, via: "native-stations-and-maintain-orders" };
}

/** Start the ordinary tea order after the spit has begun its native cooking cycle. */
export async function startExtraTea(server: MinecraftServer, player: string, colony_id: string, origin: Vec) {
  const plan = layout(origin);
  return setMaintainDemandsFast(server, player, [{ at: plan.targets[1], item: plan.products[1], count: 12, low: 12 }]);
}

export async function readExtraKitchen(
  server: MinecraftServer, colony_id: string, origin: Vec, workersUUIDs: string[],
) {
  return runColonyTask(server, READ, { colony_id, workers: workersUUIDs, ...layout(origin) });
}

const MENU = defineTask<Record<string, never>, Record<string, any>>({
  name: "folkways.promo.extra-kitchen-menu", side: "server", timeoutMs: 20_000,
  source: javaTask({ imports: [
    "io.github.izakyl.folkways.plugins.FolkwaysVanillaConfig", "net.neoforged.neoforge.common.ModConfigSpec",
    "net.minecraft.resources.ResourceLocation",
  ], body: `
ModConfigSpec.ConfigValue<List<? extends String>> refused =
    FolkwaysVanillaConfig.SPEC.getValues().get("crafting.refusedRecipes");
var before = List.copyOf(refused.get());
var changed = new LinkedHashSet<String>(before);
var excluded = new ArrayList<String>();
for (int count = 1; count <= 9; count++) {
    String id = "kaleidoscope_cookery:pot/rabbit_to_cooked_rabbit_" + count;
    changed.add(id);
    excluded.add(id);
}
// Change only this filming server's live configuration; preserve every existing exclusion.
refused.set(List.copyOf(changed));
if (!excluded.stream().map(ResourceLocation::parse).allMatch(FolkwaysVanillaConfig.refusedRecipes()::contains))
    throw new IllegalStateException("The filming menu did not exclude competing pot rabbit recipes");
return Sync.stamp(Map.of("excludedPotRabbitRecipes", excluded, "previousCount", before.size(),
    "currentCount", changed.size(), "scope", "isolated-filming-server", "via", "native-server-recipe-config"));
` }),
});

const FILTER_TARGETS = defineTask<Record<string, any>, Record<string, any>>({
  name: "folkways.promo.extra-kitchen-target-filters", side: "server", timeoutMs: 20_000,
  source: javaTask({ imports: [
    "io.github.izakyl.folkways.front.engine.colony.ColonyGround",
    "io.github.izakyl.folkways.front.engine.colony.ColonyViews",
    "io.github.izakyl.folkways.front.engine.colony.ColonyBoards",
    "io.github.izakyl.folkways.core.api.terms.ItemFilter",
    "io.github.izakyl.folkways.plugins.orders.OrdersContent", "net.minecraft.resources.ResourceLocation",
  ], body: `
var args = Args.of(ctx);
var level = ctx.server().overworld();
var colony = ColonyGround.of(ctx.server(), UUID.fromString(args.string("colony_id"))).orElseThrow();
var targets = args.list("targets");
var products = args.strings("products");
var filtered = new ArrayList<Object>();
for (int i = 0; i < targets.size(); i++) {
    var at = Args.of(Map.of("at", targets.get(i)), "target").blockPos("at");
    var attempt = ColonyBoards.setItemFilters(colony, OrdersContent.ID, Optional.of(at), "filter",
        List.of(ItemFilter.item(ResourceLocation.parse(products.get(i)))),
        ColonyViews.forBoard(colony, level, Optional.of(at)))
        .orElseThrow(() -> new IllegalStateException("No native shelf filter at " + at));
    if (attempt.refusal().isPresent()) throw new IllegalStateException("Native shelf filter refused: " + attempt.refusal().get().key());
    // Fresh shelves begin in exclude mode. Use the action exposed by the real container board.
    var mode = ColonyBoards.act(level, colony, OrdersContent.ID, Optional.of(at), "filter_mode")
        .orElseThrow(() -> new IllegalStateException("No native shelf filter mode at " + at));
    if (mode.refusal().isPresent()) throw new IllegalStateException("Native shelf filter mode refused: " + mode.refusal().get().key());
    filtered.add(Map.of("position", List.of(at.getX(), at.getY(), at.getZ()), "only", products.get(i)));
}
return Sync.stamp(Map.of("targets", filtered, "via", "native-shelf-filter"));
` }),
});

const PREPARE = defineTask<Record<string, any>, Record<string, any>>({
  name: "folkways.promo.extra-kitchen-prepare", side: "server", timeoutMs: 20_000,
  source: javaTask({ imports: [
    "com.github.ysbbbbbb.kaleidoscopecookery.init.ModBlocks", "com.github.ysbbbbbb.kaleidoscopecookery.init.ModItems",
    "com.github.ysbbbbbb.kaleidoscopecookery.init.ModRecipes",
    "com.github.ysbbbbbb.kaleidoscopecookery.block.kitchen.ShawarmaSpitBlock",
    "io.github.izakyl.folkways.core.api.resident.body.Bodies", "io.github.izakyl.folkways.core.api.resident.body.Keenness",
    "io.github.izakyl.folkways.plugins.wares.WaresContent", "io.github.izakyl.folkways.core.api.vocation.Vocations",
    "net.minecraft.core.BlockPos", "net.minecraft.core.Direction", "net.minecraft.resources.ResourceLocation",
    "net.minecraft.world.item.*", "net.minecraft.world.item.crafting.RecipeType", "net.minecraft.world.level.block.Blocks",
  ], body: `
var args = Args.of(ctx);
var level = ctx.server().overworld();
var stations = args.requireObject("stations");
var spit = stations.blockPos("spit");
var state = ModBlocks.SHAWARMA_SPIT.get().defaultBlockState().setValue(ShawarmaSpitBlock.FACING, Direction.NORTH);
level.setBlockAndUpdate(spit, state);
// Native placement constructs the two real block entities. Ordinary redstone powers each half.
state.getBlock().setPlacedBy(level, spit, state, null, new ItemStack(ModItems.SHAWARMA_SPIT.get()));
// The supported rear torch powers the lower half directly and strongly powers the
// solid timber above it, which powers the upper half without exposed redstone blocks.
level.setBlockAndUpdate(spit.south(), Blocks.REDSTONE_TORCH.defaultBlockState());
level.setBlockAndUpdate(spit.south().above(), Blocks.DARK_OAK_PLANKS.defaultBlockState());
if (!level.getBlockState(spit).getValue(ShawarmaSpitBlock.POWERED)
    || !level.getBlockState(spit.above()).getValue(ShawarmaSpitBlock.POWERED))
    throw new IllegalStateException("Native redstone did not power both spit halves");
var recipes = level.getRecipeManager();
var chopping = recipes.getAllRecipesFor(ModRecipes.CHOPPING_BOARD_RECIPE).stream()
    .filter(recipe -> recipe.id().equals(ResourceLocation.parse("kaleidoscope_cookery:chopping_board/sashimi_from_cod"))).findFirst().orElseThrow();
var tea = recipes.getAllRecipesFor(ModRecipes.TEAPOT_RECIPE).stream()
    .filter(recipe -> recipe.id().equals(ResourceLocation.parse("kaleidoscope_cookery:teapot/barley_tea"))).findFirst().orElseThrow();
var roast = recipes.getAllRecipesFor(RecipeType.CAMPFIRE_COOKING).stream()
    .filter(recipe -> recipe.value().getResultItem(level.registryAccess()).is(Items.COOKED_RABBIT)).findFirst().orElseThrow();
var crew = new ArrayList<Object>();
var ids = args.strings("workers");
var posts = args.list("posts");
for (int i = 0; i < ids.size(); i++) {
    var body = Bodies.of(level.getEntity(UUID.fromString(ids.get(i)))).orElseThrow();
    body.pack().clearContent();
    body.pack().insert(new ItemStack(Items.COOKED_BEEF, 24));
    if (i == 1) {
        body.pack().insert(new ItemStack(ModItems.BARLEY_TEA_BAG.get(), 8));
        body.pack().insert(new ItemStack(ModItems.EMPTY_CUP.get(), 16));
        body.pack().insert(new ItemStack(Items.WATER_BUCKET));
    } else {
        body.pack().insert(new ItemStack(ModItems.IRON_KITCHEN_KNIFE.get()));
        body.pack().insert(new ItemStack(i == 0 ? Items.COD : Items.RABBIT, 16));
    }
    body.licences().trade(WaresContent.trade()).ifPresent(trade -> body.licences().setKeenness(trade, Keenness.FIRST));
    body.licences().trade(Vocations.required(Vocations.HAULING)).ifPresent(trade -> body.licences().setKeenness(trade, Keenness.FOURTH));
    var post = Args.of(Map.of("at", posts.get(i)), "post").blockPos("at");
    body.mob().teleportTo(post.getX() + 0.5, post.getY(), post.getZ() + 0.5);
    crew.add(Map.of("uuid", ids.get(i), "role", List.of("cutting", "brewing", "roasting").get(i), "position", post.toShortString()));
}
return Sync.stamp(Map.of("workers", crew, "recipes", List.of(chopping.id().toString(), tea.id().toString(), roast.id().toString()),
    "teaTime", tea.value().time(), "spitPowered", true));
` }),
});

const READ = defineTask<Record<string, any>, Record<string, any>>({
  name: "folkways.promo.extra-kitchen-read", side: "server", timeoutMs: 20_000,
  source: javaTask({ imports: [
    "com.github.ysbbbbbb.kaleidoscopecookery.blockentity.kitchen.ChoppingBoardBlockEntity",
    "com.github.ysbbbbbb.kaleidoscopecookery.blockentity.kitchen.TeapotBlockEntity",
    "com.github.ysbbbbbb.kaleidoscopecookery.blockentity.kitchen.ShawarmaSpitBlockEntity",
    "com.github.ysbbbbbb.kaleidoscopecookery.block.kitchen.ShawarmaSpitBlock",
    "io.github.izakyl.folkways.front.engine.colony.ColonyGround", "io.github.izakyl.folkways.front.engine.colony.ColonyFront",
    "io.github.izakyl.folkways.core.api.resident.body.Bodies", "io.github.izakyl.folkways.core.api.terms.Goods",
    "net.minecraft.core.BlockPos", "net.minecraft.core.registries.BuiltInRegistries", "net.minecraft.resources.ResourceLocation",
    "net.minecraft.world.Container", "net.minecraft.world.item.ItemStack", "net.minecraft.server.level.ServerLevel",
  ], body: `
var args = Args.of(ctx);
var level = ctx.server().overworld();
var colony = ColonyGround.of(ctx.server(), UUID.fromString(args.string("colony_id"))).orElseThrow();
var stations = args.requireObject("stations");
var board = (ChoppingBoardBlockEntity) level.getBlockEntity(stations.blockPos("board"));
var tea = (TeapotBlockEntity) level.getBlockEntity(stations.blockPos("tea"));
var spit = stations.blockPos("spit");
var roasts = new ArrayList<Object>();
for (var at : List.of(spit, spit.above())) {
    var roast = (ShawarmaSpitBlockEntity) level.getBlockEntity(at);
    roasts.add(Map.of("raw", item(roast.cookingItem), "cooked", item(roast.cookedItem), "ticks", roast.cookTime,
        "powered", roast.getBlockState().getValue(ShawarmaSpitBlock.POWERED)));
}
var workers = new ArrayList<Object>();
for (var id : args.strings("workers")) {
    var body = Bodies.of(level.getEntity(UUID.fromString(id))).orElseThrow();
    var mob = body.mob();
    workers.add(Map.of("uuid", id, "doing", body.doing().map(doing -> doing.toward().orElse(doing.what()).getPath()).orElse("idle"),
        "wares", body.doing().map(doing -> doing.wares().toString()).orElse(""),
        "position", List.of(mob.getX(), mob.getY(), mob.getZ()), "mounted", mob.isPassenger(), "walking", !mob.getNavigation().isDone(),
        "balk", body.balk().<Object>map(balk -> Map.of("doing", balk.doing().toString(),
            "reason", balk.why().translationKey(), "at", balk.at())).orElse(Map.of())));
}
var products = new LinkedHashMap<String, Long>();
for (var id : args.strings("products")) {
    var stack = new ItemStack(BuiltInRegistries.ITEM.get(ResourceLocation.parse(id)));
    var spec = Goods.specOf(stack);
    long count = ColonyFront.of(colony).stocked(ctx.server(), spec);
    products.put(id.substring(id.indexOf(':') + 1), count);
}
var targets = new ArrayList<Object>();
for (var raw : args.list("targets")) targets.add(inventory(level, Args.of(Map.of("at", raw), "target").blockPos("at")));
var sources = new ArrayList<Object>();
for (var raw : args.list("sources")) sources.add(inventory(level, Args.of(Map.of("at", raw), "source").blockPos("at")));
return Sync.stamp(Map.of("time", level.getGameTime(), "board", Map.of("input", item(board.getCurrentCutStack()),
    "cuts", board.getCurrentCutCount(), "maxCuts", board.getMaxCutCount()),
    "tea", Map.of("status", tea.getStatus(), "ticks", tea.getCurrentTick(), "input", item(tea.getInput()),
        "result", item(tea.getResult()), "fluid", tea.getTeaFluidId().toString(), "heated", tea.hasHeatSource(level)),
    "spit", roasts, "products", products, "workers", workers, "targets", targets, "sources", sources));
`, members: `
static Map<String,Object> item(ItemStack stack) {
    return Map.of("id", BuiltInRegistries.ITEM.getKey(stack.getItem()).toString(), "count", stack.getCount());
}
static Map<String,Object> inventory(ServerLevel level, BlockPos at) {
    var contents = new LinkedHashMap<String, Integer>();
    if (level.getBlockEntity(at) instanceof Container container) {
        for (int slot = 0; slot < container.getContainerSize(); slot++) {
            var stack = container.getItem(slot);
            if (!stack.isEmpty()) contents.merge(BuiltInRegistries.ITEM.getKey(stack.getItem()).toString(), stack.getCount(), Integer::sum);
        }
    }
    return Map.of("position", List.of(at.getX(), at.getY(), at.getZ()), "items", contents);
}

` }),
});
