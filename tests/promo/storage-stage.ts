import { defineTask, javaTask } from "@izakyl/blockwright-minecraft";

// Server and client tasks the storage promo stages its world with and reads it back by: stores from several storage
// mods filled through their own item handlers, an ME network and a Refined Storage network built block by block with
// their native APIs, and the state each shot is judged by.

export type Cell = { x: number; y: number; z: number };

/** Puts goods into whatever stands at each cell through its own item handler, as a hopper would. */
export const FILL = defineTask<{ fills: Array<{ at: Cell; items: Array<[string, number]> }> }, any>({
  name: "folkways.promo.storage.fill",
  side: "server",
  timeoutMs: 20_000,
  source: javaTask({
    imports: ["net.minecraft.core.BlockPos", "net.minecraft.core.Direction", "net.minecraft.world.item.ItemStack",
      "net.minecraft.core.registries.BuiltInRegistries", "net.minecraft.resources.ResourceLocation",
      "net.neoforged.neoforge.capabilities.Capabilities", "net.neoforged.neoforge.items.ItemHandlerHelper"],
    body: `
var level = ctx.server().overworld();
var out = new ArrayList<Object>();
for (var raw : Args.of(ctx).list("fills")) {
    var fill = (Map<?, ?>) raw;
    var at = (Map<?, ?>) fill.get("at");
    var pos = new BlockPos(((Number) at.get("x")).intValue(), ((Number) at.get("y")).intValue(), ((Number) at.get("z")).intValue());
    var handler = level.getCapability(Capabilities.ItemHandler.BLOCK, pos, Direction.NORTH);
    if (handler == null) handler = level.getCapability(Capabilities.ItemHandler.BLOCK, pos, null);
    if (handler == null) throw new IllegalStateException("no item handler at " + pos + ": " + level.getBlockState(pos));
    for (var one : (List<?>) fill.get("items")) {
        var pair = (List<?>) one;
        var item = BuiltInRegistries.ITEM.get(ResourceLocation.parse(String.valueOf(pair.get(0))));
        int left = ((Number) pair.get(1)).intValue();
        while (left > 0) {
            var stack = new ItemStack(item, Math.min(left, item.getDefaultMaxStackSize()));
            int put = stack.getCount() - ItemHandlerHelper.insertItem(handler, stack, false).getCount();
            if (put == 0) break;
            left -= put;
        }
        if (left > 0) throw new IllegalStateException(pos + " took all but " + left + " of " + pair.get(0));
    }
    out.add(Map.of("at", pos.toShortString(), "block", level.getBlockState(pos).getBlock().toString()));
}
return out;`,
  }),
});

/**
 * The ME network: a creative cell, a drive with a 64k cell, two crafting CPUs (one with an accelerator), a pattern
 * provider feeding a molecular assembler per crafting pattern, and an ME crafting terminal on a glass cable facing the
 * colony. `o` is the energy cell; the cable runs along x at o.z - 1, the terminal on its north face at `terminal`.
 */
export const AE2_RIG = defineTask<{ o: Cell; terminal: Cell; patterns: Array<{ recipe: string; grid: string[]; out: [string, number] }> }, any>({
  name: "folkways.promo.storage.ae2",
  side: "server",
  timeoutMs: 20_000,
  source: javaTask({
    imports: ["net.minecraft.core.BlockPos", "net.minecraft.core.Direction", "appeng.api.parts.PartHelper",
      "appeng.api.util.AEColor", "appeng.blockentity.storage.DriveBlockEntity", "appeng.core.definitions.AEBlocks",
      "appeng.core.definitions.AEItems", "appeng.core.definitions.AEParts", "appeng.api.crafting.PatternDetailsHelper",
      "appeng.blockentity.crafting.PatternProviderBlockEntity", "appeng.api.config.Actionable",
      "appeng.api.stacks.AEItemKey", "appeng.api.networking.GridHelper", "appeng.api.networking.security.IActionSource",
      "net.minecraft.world.item.ItemStack", "net.minecraft.world.item.crafting.RecipeType",
      "net.minecraft.core.registries.BuiltInRegistries", "net.minecraft.resources.ResourceLocation"],
    body: `
var level = ctx.server().overworld();
var a = Args.of(ctx);
var o = cell(a.value("o"));
var terminal = cell(a.value("terminal"));
level.setBlockAndUpdate(o, AEBlocks.CREATIVE_ENERGY_CELL.block().defaultBlockState());
level.setBlockAndUpdate(o.east(), AEBlocks.DRIVE.block().defaultBlockState());
((DriveBlockEntity) level.getBlockEntity(o.east())).getInternalInventory().setItemDirect(0, AEItems.ITEM_CELL_64K.stack());
// Two CPUs that do not touch: half of them is the colony's by default, so the colony gets one and players keep one.
level.setBlockAndUpdate(o.east(2), AEBlocks.CRAFTING_STORAGE_4K.block().defaultBlockState());
level.setBlockAndUpdate(o.east(2).above(), AEBlocks.CRAFTING_ACCELERATOR.block().defaultBlockState());
level.setBlockAndUpdate(o.above(), AEBlocks.CRAFTING_STORAGE_4K.block().defaultBlockState());
var patterns = a.list("patterns");
for (int i = 0; i < patterns.size(); i++) {
    var p = (Map<?, ?>) patterns.get(i);
    var at = o.east(3 + i);
    level.setBlockAndUpdate(at, AEBlocks.PATTERN_PROVIDER.block().defaultBlockState());
    level.setBlockAndUpdate(at.above(), AEBlocks.MOLECULAR_ASSEMBLER.block().defaultBlockState());
    var id = ResourceLocation.parse(String.valueOf(p.get("recipe")));
    var recipe = level.getRecipeManager().getAllRecipesFor(RecipeType.CRAFTING).stream()
        .filter(h -> h.id().equals(id)).findFirst().orElseThrow(() -> new IllegalStateException("no recipe " + id));
    ItemStack[] in = new ItemStack[9];
    var grid = (List<?>) p.get("grid");
    for (int k = 0; k < 9; k++) {
        String item = String.valueOf(grid.get(k));
        in[k] = item.isEmpty() ? ItemStack.EMPTY : new ItemStack(BuiltInRegistries.ITEM.get(ResourceLocation.parse(item)));
    }
    var made = (List<?>) p.get("out");
    var output = new ItemStack(BuiltInRegistries.ITEM.get(ResourceLocation.parse(String.valueOf(made.get(0)))), ((Number) made.get(1)).intValue());
    var encoded = PatternDetailsHelper.encodeCraftingPattern(recipe, in, output, false, false);
    ((PatternProviderBlockEntity) level.getBlockEntity(at)).getLogic().getPatternInv().setItemDirect(0, encoded);
}
int last = 3 + patterns.size() - 1;
for (int x = 0; x <= Math.max(last, terminal.getX() - o.getX()); x++) {
    PartHelper.setPart(level, o.offset(x, 0, -1), null, null, AEParts.GLASS_CABLE.item(AEColor.TRANSPARENT));
}
PartHelper.setPart(level, terminal, Direction.NORTH, null, AEParts.CRAFTING_TERMINAL.get());
return Map.of("built", true);`,
    members: `
static BlockPos cell(Object raw) {
    var m = (Map<?, ?>) raw;
    return new BlockPos(((Number) m.get("x")).intValue(), ((Number) m.get("y")).intValue(), ((Number) m.get("z")).intValue());
}`,
  }),
});

/**
 * The Refined Storage network: a creative controller, a disk drive with a 64k disk, an autocrafter holding one
 * crafting pattern, and a crafting grid facing the colony at `grid`. `o` is the controller; the drive is east of it,
 * the autocrafter east of the drive, the grid east of the autocrafter (`grid`).
 */
export const RS2_RIG = defineTask<{ o: Cell; grid: Cell; pattern: { grid: string[] } }, any>({
  name: "folkways.promo.storage.rs2",
  side: "server",
  timeoutMs: 20_000,
  source: javaTask({
    imports: ["net.minecraft.core.BlockPos", "net.minecraft.core.Direction",
      "com.refinedmods.refinedstorage.common.api.RefinedStorageApi",
      "com.refinedmods.refinedstorage.common.content.Blocks", "com.refinedmods.refinedstorage.common.content.DataComponents",
      "com.refinedmods.refinedstorage.common.storage.AbstractDiskContainerBlockEntity",
      "com.refinedmods.refinedstorage.common.storage.StorageTypes",
      "com.refinedmods.refinedstorage.common.api.autocrafting.Autocrafter",
      "com.refinedmods.refinedstorage.common.api.support.network.AbstractNetworkNodeContainerBlockEntity",
      "com.refinedmods.refinedstorage.common.autocrafting.CraftingPatternState",
      "com.refinedmods.refinedstorage.common.autocrafting.PatternState",
      "com.refinedmods.refinedstorage.common.autocrafting.patterngrid.PatternType",
      "com.refinedmods.refinedstorage.api.core.Action",
      "com.refinedmods.refinedstorage.api.network.storage.StorageNetworkComponent",
      "com.refinedmods.refinedstorage.api.storage.Actor",
      "com.refinedmods.refinedstorage.common.support.resource.ItemResource",
      "net.minecraft.world.item.ItemStack", "net.minecraft.world.item.crafting.CraftingInput",
      "net.minecraft.core.registries.BuiltInRegistries", "net.minecraft.resources.ResourceLocation"],
    body: `
var level = ctx.server().overworld();
var a = Args.of(ctx);
var o = cell(a.value("o"));
var grid = cell(a.value("grid"));
level.setBlockAndUpdate(o, Blocks.INSTANCE.getCreativeController().getDefault().defaultBlockState());
level.setBlockAndUpdate(o.east(), Blocks.INSTANCE.getDiskDrive().defaultBlockState());
var repo = RefinedStorageApi.INSTANCE.getStorageRepository(level);
UUID id = UUID.randomUUID();
repo.set(id, StorageTypes.ITEM.create(64000L, repo::markAsChanged));
var disk = new ItemStack(BuiltInRegistries.ITEM.get(ResourceLocation.fromNamespaceAndPath("refinedstorage", "64k_storage_disk")));
disk.set(DataComponents.INSTANCE.getStorageReference(), id);
var drive = (AbstractDiskContainerBlockEntity<?>) level.getBlockEntity(o.east());
drive.getDiskInventory().setItem(0, disk);
drive.setChanged();
level.setBlockAndUpdate(o.east(2), Blocks.INSTANCE.getAutocrafter().getDefault().defaultBlockState());
level.setBlockAndUpdate(grid, Blocks.INSTANCE.getCraftingGrid().getDefault().defaultBlockState());
var pattern = new ItemStack(com.refinedmods.refinedstorage.common.content.Items.INSTANCE.getPattern());
pattern.set(DataComponents.INSTANCE.getPatternState(), new PatternState(UUID.randomUUID(), PatternType.CRAFTING));
var stacks = new ArrayList<ItemStack>();
for (var raw : (List<?>) ((Map<?, ?>) a.value("pattern")).get("grid")) {
    String item = String.valueOf(raw);
    stacks.add(item.isEmpty() ? ItemStack.EMPTY : new ItemStack(BuiltInRegistries.ITEM.get(ResourceLocation.parse(item))));
}
pattern.set(DataComponents.INSTANCE.getCraftingPatternState(), new CraftingPatternState(false, CraftingInput.ofPositioned(3, 3, stacks)));
var crafter = (AbstractNetworkNodeContainerBlockEntity<?>) level.getBlockEntity(o.east(2));
((Autocrafter) crafter.getContainerProvider().getContainers().iterator().next()).getPatternContainer().setItem(0, pattern);
return Map.of("built", true);`,
    members: `
static BlockPos cell(Object raw) {
    var m = (Map<?, ?>) raw;
    return new BlockPos(((Number) m.get("x")).intValue(), ((Number) m.get("y")).intValue(), ((Number) m.get("z")).intValue());
}`,
  }),
});

/** Counts of items in each network, read through the native APIs: what the residents and the player's order moved. */
export const NETWORKS = defineTask<{ ae2: Cell; rs2: Cell; items: string[] }, any>({
  name: "folkways.promo.storage.networks",
  side: "server",
  timeoutMs: 20_000,
  source: javaTask({
    imports: ["net.minecraft.core.BlockPos", "appeng.api.stacks.AEItemKey", "appeng.api.networking.GridHelper",
      "com.refinedmods.refinedstorage.api.network.storage.StorageNetworkComponent",
      "com.refinedmods.refinedstorage.common.api.support.network.AbstractNetworkNodeContainerBlockEntity",
      "com.refinedmods.refinedstorage.common.support.resource.ItemResource",
      "net.minecraft.core.registries.BuiltInRegistries", "net.minecraft.resources.ResourceLocation"],
    body: `
var level = ctx.server().overworld();
var a = Args.of(ctx);
var grid = GridHelper.getNodeHost(level, cell(a.value("ae2"))).getGridNode(null).getGrid();
var ae2 = new LinkedHashMap<String, Object>();
var cached = grid.getStorageService().getCachedInventory();
var rs2 = new LinkedHashMap<String, Object>();
var controller = (AbstractNetworkNodeContainerBlockEntity<?>) level.getBlockEntity(cell(a.value("rs2")));
var storage = controller.getContainerProvider().getContainers().iterator().next().getNode().getNetwork().getComponent(StorageNetworkComponent.class);
for (var raw : a.list("items")) {
    var item = BuiltInRegistries.ITEM.get(ResourceLocation.parse(String.valueOf(raw)));
    ae2.put(String.valueOf(raw), cached.get(AEItemKey.of(item)));
    rs2.put(String.valueOf(raw), storage.get(new ItemResource(item)));
}
var jobs = new ArrayList<Object>();
for (var cpu : grid.getCraftingService().getCpus()) {
    var job = cpu.getJobStatus();
    jobs.add(job == null ? "idle" : job.crafting().what().toString() + " x" + job.crafting().amount());
}
return Map.of("ae2", ae2, "rs2", rs2, "cpus", jobs);`,
    members: `
static BlockPos cell(Object raw) {
    var m = (Map<?, ?>) raw;
    return new BlockPos(((Number) m.get("x")).intValue(), ((Number) m.get("y")).intValue(), ((Number) m.get("z")).intValue());
}`,
  }),
});

/**
 * Each resident as the shots judge it: where it stands, what it carries, what hangs on its back (the Curios back
 * slot), the upgrades and discs in that backpack, and whether its backpack jukebox is playing.
 */
export const CREW = defineTask<{ colony_id: string }, any>({
  name: "folkways.promo.storage.crew",
  side: "server",
  timeoutMs: 20_000,
  source: javaTask({
    imports: ["io.github.izakyl.folkways.vanilla.person.ResidentEntity",
      "io.github.izakyl.folkways.core.resident.api.Bodies"],
    body: `
var level = ctx.server().overworld();
var playing = Class.forName("io.github.izakyl.folkways.core.shell.storage.sophisticated.ResidentJukebox")
    .getDeclaredMethod("playing", UUID.class);
playing.setAccessible(true);
var out = new LinkedHashMap<String, Object>();
for (var entity : level.getAllEntities()) {
    if (!(entity instanceof ResidentEntity r)) continue;
    var one = new LinkedHashMap<String, Object>();
    one.put("name", r.getName().getString());
    one.put("x", r.getX()); one.put("y", r.getY()); one.put("z", r.getZ());
    var pack = new ArrayList<String>();
    for (int i = 0; i < r.pack().getContainerSize(); i++) {
        var s = r.pack().getItem(i);
        if (!s.isEmpty()) pack.add(s.getCount() + " " + net.minecraft.core.registries.BuiltInRegistries.ITEM.getKey(s.getItem()));
    }
    one.put("pack", pack);
    var back = top.theillusivec4.curios.api.CuriosApi.getCuriosInventory(r)
        .flatMap(inv -> Optional.ofNullable(inv.getCurios().get("back")))
        .map(handler -> handler.getStacks().getStackInSlot(0)).orElse(net.minecraft.world.item.ItemStack.EMPTY);
    one.put("back", back.isEmpty() ? "" : net.minecraft.core.registries.BuiltInRegistries.ITEM.getKey(back.getItem()).toString());
    var upgrades = new ArrayList<String>();
    var discs = new ArrayList<String>();
    if (!back.isEmpty()) {
        var wrapper = net.p3pp3rf1y.sophisticatedbackpacks.backpack.wrapper.BackpackWrapper.fromStack(back);
        var handler = wrapper.getUpgradeHandler();
        for (int i = 0; i < handler.getSlots(); i++) {
            var up = handler.getStackInSlot(i);
            if (!up.isEmpty()) upgrades.add(net.minecraft.core.registries.BuiltInRegistries.ITEM.getKey(up.getItem()).toString());
        }
        for (var w : handler.getSlotWrappers().values()) {
            for (var m : w.getClass().getMethods()) {
                if (m.getName().equals("getDiscInventory") && m.getParameterCount() == 0) {
                    var discInv = (net.neoforged.neoforge.items.IItemHandler) m.invoke(w);
                    for (int i = 0; i < discInv.getSlots(); i++) {
                        var d = discInv.getStackInSlot(i);
                        if (!d.isEmpty()) discs.add(net.minecraft.core.registries.BuiltInRegistries.ITEM.getKey(d.getItem()).toString());
                    }
                }
            }
        }
    }
    one.put("upgrades", upgrades);
    one.put("discs", discs);
    one.put("playing", playing.invoke(null, r.getUUID()));
    one.put("doing", Bodies.of(r).map(b -> String.valueOf(b.doing())).orElse(""));
    out.put(r.getUUID().toString(), one);
}
return out;`,
  }),
});

/** Whether each cell is a colony store, and what the colony last read in it. */
export const STORES = defineTask<{ colony_id: string; cells: Cell[] }, any>({
  name: "folkways.promo.storage.stores",
  side: "server",
  timeoutMs: 20_000,
  source: javaTask({
    imports: ["net.minecraft.core.BlockPos", "io.github.izakyl.folkways.core.front.engine.colony.ColonyGround",
      "io.github.izakyl.folkways.core.world.api.WorldPos"],
    body: `
var level = ctx.server().overworld();
var a = Args.of(ctx);
var colony = ColonyGround.of(ctx.server(), UUID.fromString(a.string("colony_id"))).orElseThrow();
var snapshot = colony.inventory().snapshot();
var out = new ArrayList<Object>();
for (var raw : a.list("cells")) {
    var m = (Map<?, ?>) raw;
    var pos = new BlockPos(((Number) m.get("x")).intValue(), ((Number) m.get("y")).intValue(), ((Number) m.get("z")).intValue());
    var one = new LinkedHashMap<String, Object>();
    one.put("at", pos.toShortString());
    one.put("member", ColonyGround.holds(level, colony, pos));
    var store = snapshot.store(WorldPos.of(level, pos));
    one.put("state", store.map(s -> s.state().toString()).orElse("none"));
    one.put("kinds", store.map(s -> s.entries().size()).orElse(0));
    one.put("usable", store.map(s -> s.usability().usable()).orElse(false));
    out.add(one);
}
return out;`,
  }),
});

/** Opens the colony panel for the player as the book's right click does. */
export const OPEN_PANEL = defineTask<{ player: string }, any>({
  name: "folkways.promo.storage.panel",
  side: "server",
  timeoutMs: 20_000,
  source: javaTask({
    imports: ["io.github.izakyl.folkways.core.front.ui.screen.ColonyShell"],
    body: `
var player = ctx.server().getPlayerList().getPlayerByName(Args.of(ctx).string("player"));
ColonyShell.open(player);
return Map.of("menu", String.valueOf(player.containerMenu));`,
  }),
});

/** Turns network service on for each resident: the trade is off by default, colony-wide and for every licence. */
export const NETWORK_SERVICE = defineTask<{ uuids: string[] }, any>({
  name: "folkways.promo.storage.network-service",
  side: "server",
  timeoutMs: 20_000,
  source: javaTask({
    imports: ["io.github.izakyl.folkways.core.vocation.api.Vocations", "io.github.izakyl.folkways.core.resident.api.Bodies"],
    body: `
var level = ctx.server().overworld();
var out = new ArrayList<Object>();
for (var raw : Args.of(ctx).list("uuids")) {
    var entity = level.getEntity(UUID.fromString(String.valueOf(raw)));
    var body = Bodies.of(entity).orElseThrow();
    var trade = body.licences().trade(Vocations.required(Vocations.NETWORK_SERVICE)).orElseThrow();
    body.licences().allow(trade, true);
    out.add(Map.of("uuid", raw, "allowed", body.licences().allows(trade)));
}
return out;`,
  }),
});

/** The client's GUI scale, toasts and chat, set for a take: the panel and the terminal read at the film's size. */
export const CLIENT_SETUP = defineTask<{ guiScale: number }, any>({
  name: "folkways.promo.storage.client",
  side: "client",
  timeoutMs: 20_000,
  source: javaTask({
    imports: ["net.minecraft.client.Minecraft", "net.minecraft.client.tutorial.TutorialSteps"],
    body: `
var mc = Minecraft.getInstance();
mc.options.guiScale().set((int) Args.of(ctx).integer("guiScale"));
mc.options.hideGui = false;
mc.options.tutorialStep = TutorialSteps.NONE;
mc.getTutorial().setStep(TutorialSteps.NONE);
mc.getToasts().clear();
mc.gui.getChat().clearMessages(true);
mc.resizeDisplay();
mc.options.save();
return Map.of("guiScale", mc.getWindow().getGuiScale(), "width", mc.getWindow().getGuiScaledWidth(),
    "height", mc.getWindow().getGuiScaledHeight(), "lang", mc.options.languageCode);`,
  }),
});

/** Closes whatever screen and container the player has open, on both sides. */
export const CLOSE = defineTask<Record<string, never>, any>({
  name: "folkways.promo.storage.close",
  side: "client",
  timeoutMs: 20_000,
  source: javaTask({
    imports: ["net.minecraft.client.Minecraft"],
    body: `
var mc = Minecraft.getInstance();
if (mc.player != null) mc.player.closeContainer();
mc.setScreen(null);
return Map.of("closed", true);`,
  }),
});

/** The AE2 terminal screen's search box and first item slot, in GUI pixels, read from the screen itself. */
export const AE2_SCREEN = defineTask<Record<string, never>, any>({
  name: "folkways.promo.storage.ae2-screen",
  side: "client",
  timeoutMs: 20_000,
  source: javaTask({
    imports: ["net.minecraft.client.Minecraft"],
    body: `
var mc = Minecraft.getInstance();
var screen = mc.screen;
if (screen == null) return Map.of("screen", "none");
var out = new LinkedHashMap<String, Object>();
out.put("screen", screen.getClass().getSimpleName());
var widgets = new ArrayList<Object>();
for (var child : screen.children()) {
    if (child instanceof net.minecraft.client.gui.components.AbstractWidget w) {
        widgets.add(Map.of("class", w.getClass().getSimpleName(), "x", w.getX(), "y", w.getY(), "w", w.getWidth(),
            "h", w.getHeight(), "text", w.getMessage().getString()));
    }
}
out.put("widgets", widgets);
if (screen instanceof net.minecraft.client.gui.screens.inventory.AbstractContainerScreen<?> container) {
    out.put("left", container.getGuiLeft());
    out.put("top", container.getGuiTop());
}
return out;`,
  }),
});

/**
 * Stocks both networks once their nodes have come up (a tick or more after the blocks went in), and reports whether
 * each is powered with its crafting CPUs and patterns in place.
 */
export const STOCK = defineTask<{ ae2: Cell; rs2: Cell; ae2Stock: Array<[string, number]>; rs2Stock: Array<[string, number]> }, any>({
  name: "folkways.promo.storage.stock",
  side: "server",
  timeoutMs: 20_000,
  source: javaTask({
    imports: ["net.minecraft.core.BlockPos", "appeng.api.config.Actionable", "appeng.api.stacks.AEItemKey",
      "appeng.api.networking.GridHelper", "appeng.api.networking.security.IActionSource",
      "com.refinedmods.refinedstorage.api.core.Action",
      "com.refinedmods.refinedstorage.api.network.storage.StorageNetworkComponent",
      "com.refinedmods.refinedstorage.api.storage.Actor",
      "com.refinedmods.refinedstorage.common.api.support.network.AbstractNetworkNodeContainerBlockEntity",
      "com.refinedmods.refinedstorage.common.support.resource.ItemResource",
      "net.minecraft.core.registries.BuiltInRegistries", "net.minecraft.resources.ResourceLocation"],
    body: `
var level = ctx.server().overworld();
var a = Args.of(ctx);
var host = GridHelper.getNodeHost(level, cell(a.value("ae2")));
var node = host == null ? null : host.getGridNode(null);
if (node == null || node.getGrid() == null) return Map.of("ready", false, "why", "ae2 node not up");
var inv = node.getGrid().getStorageService().getInventory();
var ae2 = new ArrayList<Object>();
for (var raw : a.list("ae2Stock")) {
    var pair = (List<?>) raw;
    var item = BuiltInRegistries.ITEM.get(ResourceLocation.parse(String.valueOf(pair.get(0))));
    ae2.add(pair.get(0) + "=" + inv.insert(AEItemKey.of(item), ((Number) pair.get(1)).longValue(), Actionable.MODULATE, IActionSource.empty()));
}
var controller = (AbstractNetworkNodeContainerBlockEntity<?>) level.getBlockEntity(cell(a.value("rs2")));
var network = controller.getContainerProvider().getContainers().iterator().next().getNode().getNetwork();
if (network == null) return Map.of("ready", false, "why", "rs2 network not up", "ae2", ae2);
var storage = network.getComponent(StorageNetworkComponent.class);
var rs2 = new ArrayList<Object>();
for (var raw : a.list("rs2Stock")) {
    var pair = (List<?>) raw;
    var item = BuiltInRegistries.ITEM.get(ResourceLocation.parse(String.valueOf(pair.get(0))));
    rs2.add(pair.get(0) + "=" + storage.insert(new ItemResource(item), ((Number) pair.get(1)).longValue(), Action.EXECUTE, Actor.EMPTY));
}
return Map.of("ready", true, "ae2", ae2, "rs2", rs2, "cpus", node.getGrid().getCraftingService().getCpus().size(),
    "ae2Powered", node.isPowered(), "craftable", node.getGrid().getCraftingService().getCraftables(k -> true).size());`,
    members: `
static BlockPos cell(Object raw) {
    var m = (Map<?, ?>) raw;
    return new BlockPos(((Number) m.get("x")).intValue(), ((Number) m.get("y")).intValue(), ((Number) m.get("z")).intValue());
}`,
  }),
});
