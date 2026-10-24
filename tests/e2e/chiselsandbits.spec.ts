import { expect, test } from "@playwright/test";
import { defineTask, javaTask, media, screen, tick, world } from "@izakyl/blockwright-minecraft";
import { cameraLookingAt } from "@izakyl/blockwright-minecraft/internal";
import { foundColonyFast, waitForGroundedPlayer } from "../shared/colony/colony-founding";
import { launchPair, type E2EPair } from "../shared/harness/pair";
import { seededServer } from "../shared/harness/mod-settings";
import { ensureOut, outAbs, tracePath } from "../shared/harness/out-paths";
import { frames } from "../shared/harness/bw-helpers";
import { newRunId } from "../shared/harness/run-id";
import { BUDGET } from "../shared/harness/budgets";

type Args = { player: string; x: number; y: number; z: number; mode: string };
const STATE = defineTask<Args, Record<string, any>>({
  name: "folkways.chiselsandbits.build-state", side: "server", timeoutMs: 20_000,
  source: javaTask({ imports: [
    "io.github.izakyl.folkways.chiselsandbits.CandBStructureCodec",
    "io.github.izakyl.folkways.core.api.colony.Books",
    "io.github.izakyl.folkways.core.api.resident.body.Body",
    "io.github.izakyl.folkways.core.api.terms.Goods",
    "io.github.izakyl.folkways.core.api.terms.ItemSpec",
    "io.github.izakyl.folkways.front.engine.colony.ColonyGround",
    "io.github.izakyl.folkways.plugins.build.Blueprint",
    "io.github.izakyl.folkways.plugins.build.BlueprintCapture",
    "io.github.izakyl.folkways.plugins.build.BuildContent",
    "io.github.izakyl.folkways.plugins.build.StructureNbt",
    "io.github.izakyl.folkways.plugins.build.draft.Templates",
    "mod.chiselsandbits.api.block.storage.StateEntryStorage",
    "mod.chiselsandbits.api.blockinformation.BlockInformation",
    "mod.chiselsandbits.api.item.bit.IBitItemManager",
    "mod.chiselsandbits.block.entities.ChiseledBlockEntity",
    "mod.chiselsandbits.registrars.ModBlocks",
    "net.minecraft.core.BlockPos",
    "net.minecraft.core.registries.BuiltInRegistries",
    "net.minecraft.resources.ResourceLocation",
    "net.minecraft.world.Container",
    "net.minecraft.world.level.block.Blocks",
    "net.minecraft.world.level.levelgen.structure.BoundingBox",
  ], body: `
var args = Args.of(ctx);
var player = ctx.server().getPlayerList().getPlayerByName(args.string("player"));
var level = player.serverLevel();
var origin = new BlockPos((int) args.integer("x"), (int) args.integer("y"), (int) args.integer("z"));
var source = origin.offset(3, 0, 3);
var target = origin.offset(6, 0, 5);
var chest = (Container) level.getBlockEntity(origin.west(2));
var wood = IBitItemManager.getInstance().create(new BlockInformation(Blocks.OAK_PLANKS.defaultBlockState(), Optional.empty()), 1);
var stone = IBitItemManager.getInstance().create(new BlockInformation(Blocks.STONE.defaultBlockState(), Optional.empty()), 1);
var mode = args.string("mode");
if (mode.equals("setup")) {
    var storage = new StateEntryStorage();
    for (int x = 0; x < 7; x++) storage.setBlockInformation(x, 0, 0, new BlockInformation(
        (x < 3 ? Blocks.OAK_PLANKS : Blocks.STONE).defaultBlockState(), Optional.empty()));
    level.setBlockAndUpdate(source, ModBlocks.CHISELED_BLOCK.get().defaultBlockState());
    level.getBlockEntity(source).loadWithComponents(CandBStructureCodec.encode(storage), level.registryAccess());
    chest.setItem(4, stone.copyWithCount(4));
}
if (mode.equals("supply")) chest.setItem(5, wood.copyWithCount(3));
var id = ResourceLocation.parse("local:blueprint/e2e_chiseled");
var file = Templates.blueprint(id).orElse(null);
if (mode.equals("start")) {
    var blueprint = StructureNbt.toBlueprint(file, level.registryAccess(), UUID.randomUUID(), "carving", level.getGameTime(),
        StructureNbt.AirMeaning.UNCONSTRAINED, 8).orElseThrow();
    var colony = ColonyGround.of(ctx.server(), Books.heldColony(player).orElseThrow()).orElseThrow();
    var presence = colony.service(BuildContent.ID, Object.class).orElseThrow();
    var method = presence.getClass().getDeclaredMethod("file", net.minecraft.server.level.ServerLevel.class,
        Blueprint.class, BlockPos.class, String.class, Optional.class, String.class);
    method.setAccessible(true);
    if (((Optional<?>) method.invoke(presence, level, blueprint, target, "carving", Optional.of(player.getUUID()),
        player.getGameProfile().getName())).isEmpty()) throw new IllegalStateException("No build order filed");
}
long remaining = Goods.countIn(chest, ItemSpec.exact(wood)) + Goods.countIn(chest, ItemSpec.exact(stone));
for (var entity : level.getAllEntities()) if (entity instanceof Body body) {
    remaining += Goods.countIn(body.pack(), ItemSpec.exact(wood)) + Goods.countIn(body.pack(), ItemSpec.exact(stone));
}
var counts = new LinkedHashMap<String, Integer>();
if (level.getBlockEntity(target) instanceof ChiseledBlockEntity chiseled) {
    try { CandBStructureCodec.counts(chiseled.payload().storage()).forEach((material, count) ->
        counts.put(BuiltInRegistries.BLOCK.getKey(material.blockState().getBlock()).toString(), count));
    } catch (IllegalArgumentException loading) { }
}
return Map.of("stored", file != null, "snbt", file == null ? "" : file.toString(), "remaining", remaining,
    "counts", counts, "source", List.of(source.getX(), source.getY(), source.getZ()));
` }),
});

const CAPTURE = defineTask<{ source: number[] }, boolean>({
  name: "folkways.chiselsandbits.capture", side: "client", timeoutMs: 20_000,
  source: javaTask({ imports: ["io.github.izakyl.folkways.core.api.colony.Books",
    "io.github.izakyl.folkways.plugins.build.CaptureBlueprintPacket", "net.minecraft.client.Minecraft",
    "net.minecraft.core.BlockPos", "net.neoforged.neoforge.network.PacketDistributor"], body: `
var mc = Minecraft.getInstance();
var at = Args.of(ctx).blockPos("source");
PacketDistributor.sendToServer(new CaptureBlueprintPacket(Books.heldColony(mc.player).orElseThrow(), at, at, "e2e_chiseled"));
return true;
` }),
});

const PREVIEW = defineTask<{ snbt: string }, boolean>({
  name: "folkways.chiselsandbits.preview", side: "client", timeoutMs: 20_000,
  source: javaTask({ imports: ["io.github.izakyl.folkways.plugins.build.BlueprintsPage",
    "io.github.izakyl.folkways.plugins.build.HeldBlueprints", "net.minecraft.nbt.TagParser",
    "net.minecraft.network.chat.Component", "net.minecraft.resources.ResourceLocation"], body: `
var file = TagParser.parseTag(Args.of(ctx).string("snbt"));
var take = HeldBlueprints.class.getDeclaredMethod("take", BlueprintsPage.Entry.class, Runnable.class);
take.setAccessible(true);
take.invoke(null, new BlueprintsPage.Entry(ResourceLocation.parse("local:blueprint/e2e_chiseled"),
    Component.literal("carving"), false, file), (Runnable) () -> {});
return true;
` }),
});

const MESH = defineTask<void, Record<string, any>>({
  name: "folkways.chiselsandbits.mesh", side: "client", timeoutMs: 20_000,
  source: javaTask({ body: `
var held = Class.forName("io.github.izakyl.folkways.front.client.HeldPlacement");
var rendererField = held.getDeclaredField("GHOSTS"); rendererField.setAccessible(true);
var renderer = rendererField.get(null);
var meshesField = renderer.getClass().getDeclaredField("meshes"); meshesField.setAccessible(true);
var meshes = (List<?>) meshesField.get(renderer);
var cornerField = held.getDeclaredField("corner"); cornerField.setAccessible(true);
var corner = (net.minecraft.core.BlockPos) cornerField.get(null);
return Map.of("meshes", meshes.size(), "corner", corner == null ? List.of() : List.of(corner.getX(), corner.getY(), corner.getZ()));
` }),
});

test("chiseled payloads preview, haul, build and survive a full restart", async ({}, testInfo) => {
  test.setTimeout(BUDGET.slow);
  ensureOut("e2e");
  const run = newRunId();
  const serverInstance = seededServer(`chiselsandbits-server-${run}`);
  const clientInstance = `chiselsandbits-client-${run}`;
  let pair: E2EPair | undefined;
  const evidence: Record<string, any> = {};
  try {
    const launch = (reuse: boolean) => launchPair({ config: "blockwright.toml#chiselsandbits",
      server: { instance: serverInstance, keepInstanceDir: true, reuseInstanceDir: reuse,
        trace: tracePath("e2e", `chiselsandbits-server-${reuse ? "reload" : "first"}`) },
      client: { instance: clientInstance, keepInstanceDir: true, reuseInstanceDir: reuse,
        trace: tracePath("e2e", `chiselsandbits-client-${reuse ? "reload" : "first"}`) } });
    pair = await launch(false);
    const player = await waitForGroundedPlayer(pair.server);
    await screen.dismiss(pair.client);
    const origin = { x: Math.round(player.pos.x) + 8, y: Math.round(player.pos.y), z: Math.round(player.pos.z) + 8 };
    await foundColonyFast(pair.server, pair.client, player.name, { origin, residentCount: 1 });
    const args: Args = { player: player.name, ...origin, mode: "setup" };
    const read = () => pair!.server.run(STATE, { ...args, mode: "read" });
    evidence.setup = await pair.server.run(STATE, args);
    await pair.client.run(CAPTURE, { source: evidence.setup.source });
    await expect.poll(async () => {
      await tick.sprint(pair!.server, 5);
      evidence.capture = await read();
      return evidence.capture.stored;
    }, { timeout: 15_000 }).toBe(true);
    await world.command(pair.server, `tp ${player.name} ${origin.x + 2.5} ${origin.y + 1} ${origin.z + 5.5} facing ${origin.x + 5.5} ${origin.y} ${origin.z + 5.5}`);
    await tick.sprint(pair.server, 3);
    await frames(pair.client, 3);
    await pair.client.run(PREVIEW, { snbt: evidence.capture.snbt });
    await expect.poll(async () => {
      await frames(pair!.client, 4);
      evidence.preview = await pair!.client.run(MESH);
      return evidence.preview.meshes;
    }, { timeout: 20_000, message: "the real client meshes the voxel preview" }).toBeGreaterThan(0);
    const [x, y, z] = evidence.preview.corner;
    const eye = { x: x + 1.5, y: y + 1.5, z: z + 2.5 };
    await media.screenshot(pair.client, { path: outAbs("e2e", "shots", "chiselsandbits-preview.png"),
      camera: { position: eye, ...cameraLookingAt(eye, { x: x + 0.5, y: y + 0.1, z: z + 0.5 }) } });
    await pair.server.run(STATE, { ...args, mode: "start" });
    await tick.sprint(pair.server, 300);
    evidence.missing = await read();
    expect(evidence.missing.counts).toEqual({});
    expect(evidence.missing.remaining).toBe(4);
    await pair.server.run(STATE, { ...args, mode: "supply" });
    await expect.poll(async () => {
      await tick.sprint(pair!.server, 100);
      evidence.built = await read();
      return evidence.built.counts;
    }, { timeout: 45_000, message: "a resident hauls both actual bit materials and builds the payload" })
      .toEqual({ "minecraft:oak_planks": 3, "minecraft:stone": 4 });
    expect(evidence.built.remaining).toBe(0);
    await world.command(pair.server, "save-all flush");
    await pair.teardown();
    pair = await launch(true);
    await waitForGroundedPlayer(pair.server);
    await expect.poll(async () => {
      await tick.sprint(pair!.server, 20);
      evidence.reloaded = await read();
      return evidence.reloaded.counts;
    }, { timeout: 20_000 }).toEqual({ "minecraft:oak_planks": 3, "minecraft:stone": 4 });
    expect(evidence.reloaded.remaining).toBe(0);
  } catch (error) {
    pair?.failing(error);
    throw error;
  } finally {
    await testInfo.attach("chiselsandbits-evidence", { body: JSON.stringify(evidence, null, 2), contentType: "application/json" });
    await pair?.teardown(testInfo);
  }
});
