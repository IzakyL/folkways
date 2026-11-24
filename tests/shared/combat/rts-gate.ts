import { defineTask, javaTask } from "@izakyl/blockwright-minecraft";
import type { Vec } from "./rts-fixtures";

export const SERVER_IMPORTS = [
  "net.minecraft.server.MinecraftServer",
  "net.minecraft.core.BlockPos",
  "net.minecraft.world.level.block.Blocks",
  "net.minecraft.world.level.block.DoorBlock",
  "net.minecraft.world.level.block.LeverBlock",
  "io.github.izakyl.folkways.core.colony.api.Colonies",
  "io.github.izakyl.folkways.core.facility.api.DefinedFacilities",
];

export const GATE_SCENE = `
var args = Args.of(ctx);
var server = (MinecraftServer) ctx.server();
var level = server.overworld();
var colony = Colonies.of(server, UUID.fromString(args.string("colony"))).orElseThrow();
var facilities = DefinedFacilities.of(colony).orElseThrow();
int x = (int) args.integer("x"), y = (int) args.integer("y"), z = (int) args.integer("z");
var door = new BlockPos(x, y, z);
var lever = door.west();
String name = "North gate";
`;

export const SET_UP_GATE = defineTask<Vec & { colony: string; visualReadouts?: boolean }, boolean>({
  name: "folkways.rts-facility-card.set-up-gate", side: "server",
  source: javaTask({
    imports: [...SERVER_IMPORTS, "net.minecraft.core.Direction", "net.minecraft.resources.ResourceLocation",
      "io.github.izakyl.folkways.core.facility.api.Routine", "io.github.izakyl.folkways.core.facility.api.Routines",
      "io.github.izakyl.folkways.core.facility.api.control.Value", "io.github.izakyl.folkways.core.plan.api.Refinement",
      "net.minecraft.world.level.block.state.properties.AttachFace",
      "net.minecraft.world.level.block.state.properties.DoubleBlockHalf",
      "io.github.izakyl.folkways.core.facility.api.control.Readings",
      "net.minecraft.world.level.block.entity.ChestBlockEntity",
      "net.minecraft.world.item.Items", "net.minecraft.world.item.ItemStack"],
    body: GATE_SCENE + `
var lower = Blocks.IRON_DOOR.defaultBlockState().setValue(DoorBlock.FACING, Direction.EAST);
level.setBlock(door, lower, 2);
level.setBlockAndUpdate(door.above(), lower.setValue(DoorBlock.HALF, DoubleBlockHalf.UPPER));
level.setBlockAndUpdate(lever, Blocks.LEVER.defaultBlockState().setValue(LeverBlock.FACE, AttachFace.FLOOR));
var targetRoutine = ResourceLocation.parse("folkways_core:e2e_gate_target");
Routines.register(new Routine() {
  public ResourceLocation id() { return targetRoutine; }

  public Optional<Refinement.Change> expand(Call call) {
    if (!(call.arguments().get("target") instanceof Value.Vector vector) || vector.values().size() != 3) {
      return Optional.empty();
    }
    var target = vector.values();
    boolean open = target.get(0) == x + 3;
    if ((!open && target.get(0) != x - 3) || target.get(1) != y - 1 || target.get(2) != z) {
      return Optional.empty();
    }
    return Routines.of(Routines.WRITE_ALL).orElseThrow().expand(new Call(call.id(), call.owner(), call.facility(),
      call.site(), call.parameters(), Map.of("lever", Value.of(open)), call.readings(), call.rig()));
  }
});
var data = new LinkedHashMap<String, Object>(Map.of(
  "name", name,
  "extent", Map.of("elements", List.of(Map.of("space", Map.of("dimension", level.dimension().location().toString()),
    "min", List.of(x - 1, y, z), "max", List.of(x, y + 1, z)))),
  "controls", Map.of("lever", Map.of("part", "lever", "offset", List.of(0, 0, 0))),
  "parameters", Map.of("shell", "solid", "charges", 1, "open", true),
  "routines", Map.of(
    "open", Map.of("use", "folkways_core:write_all", "with", Map.of("lever", Map.of("param", "open"))),
    "close", Map.of("use", "folkways_core:write_all", "with", Map.of("lever", false)),
    "pick", Map.of("use", targetRoutine.toString())),
  "triggers", Map.of(
    "open", Map.of("kind", "manual", "routine", "open"),
    "close", Map.of("kind", "manual", "routine", "close"),
    "pick", Map.of("kind", "manual", "routine", "pick"),
    "night", Map.of("kind", "schedule", "routine", "close", "dayTime", 13000, "enabled", true)),
  "ui", Map.of("card", List.of(
    Map.of("button", "open", "icon", "minecraft:iron_door", "enabled_when", Map.of("lever", false)),
    Map.of("button", "close", "icon", "minecraft:iron_bars", "enabled_when", Map.of("lever", true)),
    Map.of("toggle", "night", "icon", "minecraft:clock"),
    Map.of("target", "pick", "pick", "pos", "default", true, "label", "Pick gate target"),
    Map.of("choice", "shell", "options", List.of("solid", "he"), "labels", List.of("Solid", "Explosive")),
    Map.of("number", "charges", "min", 1, "max", 3, "step", 1),
    Map.of("readout", "lever", "as", "light", "label", "Gate open"))),
  "projections", List.of(Map.of("gate", Map.of("cells", List.of(List.of(x, y, z), List.of(x, y + 1, z)),
    "open_when", Map.of("lever", true))))));
if (args.flag("visualReadouts", false)) {
  var chestPos = lever.south();
  level.setBlockAndUpdate(chestPos, Blocks.CHEST.defaultBlockState());
  var chest = (ChestBlockEntity) level.getBlockEntity(chestPos);
  chest.setItem(0, new ItemStack(Items.IRON_INGOT, 24));
  chest.setItem(1, new ItemStack(Items.REDSTONE, 12));
  var contents = ResourceLocation.parse("folkways_core:visual_gate_contents");
  var progress = ResourceLocation.parse("folkways_core:visual_gate_progress");
  var status = ResourceLocation.parse("folkways_core:visual_gate_status");
  Readings.register(contents, (world, at) -> world.getBlockEntity(at.block(world)) instanceof ChestBlockEntity store
      ? Optional.of(new Value.Data(store.saveWithoutMetadata(world.registryAccess()))) : Optional.empty());
  Readings.register(progress, (world, at) -> world.getBlockEntity(at.block(world)) instanceof ChestBlockEntity store
      ? Optional.of(Value.of(store.getItem(0).getCount() / 64.0)) : Optional.empty());
  Readings.register(status, (world, at) -> Optional.of(new Value.Text("Stocked")));
  data.put("readings", Map.of(
      "contents", Map.of("probe", contents.toString(), "offset", List.of(0, 0, 1)),
      "replenishment", Map.of("probe", progress.toString(), "offset", List.of(0, 0, 1)),
      "status", Map.of("probe", status.toString())));
  var card = new ArrayList<Object>((List<?>) ((Map<?, ?>) data.get("ui")).get("card"));
  card.add(Map.of("readout", "status", "as", "text", "label", "Supply status"));
  card.add(Map.of("readout", "replenishment", "as", "progress", "label", "Iron stock"));
  card.add(Map.of("readout", "contents", "as", "slots", "label", "Supply chest"));
  data.put("ui", Map.of("card", card));
}
var problems = facilities.apply(facilities.preview(name, Optional.of(data)));
if (!problems.isEmpty()) {
  throw new IllegalStateException(problems.toString());
}
return true;`,
  }),
});

export const SET_EDGE_SCROLL = defineTask<{ enabled: boolean }, boolean>({
  name: "folkways.rts-facility-card.set-edge-scroll", side: "client",
  source: javaTask({
    imports: ["io.github.izakyl.folkways.core.FolkwaysClientConfig"],
    body: `FolkwaysClientConfig.edgeScrollCamera(Args.of(ctx).flag("enabled", false)); return true;`,
  }),
});

// Read the same stored key strings that CommandButton draws. They are custom-rendered outside the label tree.
export const READ_HOTKEYS = defineTask<void, string[]>({
  name: "folkways.rts-facility-card.read-hotkeys", side: "client",
  source: javaTask({
    imports: ["com.lowdragmc.lowdraglib2.gui.ui.UIElement",
      "io.github.izakyl.folkways.core.front.api.ui.OverheadTools"],
    body: `
var handler = OverheadTools.selectedHandler().orElseThrow();
var window = handler.getClass().getDeclaredField("window");
window.setAccessible(true);
var card = window.get(handler);
var desk = card.getClass().getDeclaredField("desk");
desk.setAccessible(true);
var pending = new ArrayDeque<UIElement>();
pending.add((UIElement) desk.get(card));
var hotkeys = new ArrayList<String>();
while (!pending.isEmpty()) {
  var element = pending.removeFirst();
  if (element.getClass().getSimpleName().equals("CommandButton")) {
    var key = element.getClass().getDeclaredField("key");
    key.setAccessible(true);
    hotkeys.add((String) key.get(element));
  }
  pending.addAll(element.getChildren());
}
return hotkeys;`,
  }),
});

export const IS_PICKING_TARGET = defineTask<void, boolean>({
  name: "folkways.rts-facility-card.is-picking-target", side: "client",
  source: javaTask({
    imports: ["io.github.izakyl.folkways.core.front.api.ui.OverheadTools"],
    body: `
var handler = OverheadTools.selectedHandler().orElseThrow();
var method = handler.getClass().getDeclaredMethod("pickingFacilityTarget");
method.setAccessible(true);
return method.invoke(handler);
`,
  }),
});
