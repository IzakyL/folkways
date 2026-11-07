import { mkdirSync, writeFileSync } from "node:fs";
import path from "node:path";
import { expect } from "@playwright/test";
import {
  defineTask, javaTask, media, screen, tick, type MinecraftClient, type MinecraftServer,
} from "@izakyl/blockwright-minecraft";
import { frames } from "../harness/bw-helpers";

type Scene = { colony: string; x: number; y: number; z: number };
type Modes = {
  mode: string;
  alpha: Record<string, number>;
  marks: number;
  zones: number;
  ghosts: number;
  aimed: boolean;
  handler: boolean;
  bottom: number;
  transition: number[];
  revision: number;
};

/** Real server holdings, baseline and an unbuilt blueprint; UI input alone changes the mode. */
export async function checkModeLayers(client: MinecraftClient, server: MinecraftServer, scene: Scene) {
  await server.run(PREPARE, scene);
  await tick.sprint(server, 40);
  await expect.poll(async () => {
    await tick.sprint(server, 2);
    await frames(client, 8);
    const state = await client.run(MODE_PROBE);
    return state.zones > 0 && state.ghosts > 0 && state.marks > 1;
  }, { timeout: 10_000, message: "server overlays must reach the client before mode assertions" }).toBe(true);
  const directory = path.resolve("tests/out/mode-layers");
  mkdirSync(directory, { recursive: true });
  const evidence: Record<string, Modes> = {};
  const sequence = [
    ["Build", "build"], ["RTS", "rts"], ["Damage Control", "damage_control"],
    ["Build", "build"], ["RTS", "rts"],
  ];
  for (const [label, mode] of sequence) {
    await screen.clickWidget(client, { label });
    await expect.poll(async () => {
      await tick.sprint(server, 2);
      await frames(client, 8);
      return (await client.run(MODE_PROBE)).alpha[mode];
    }).toBeCloseTo(1, 4);
    const state = await client.run(MODE_PROBE);
    expect(state.mode).toBe(`folkways:${mode}`);
    expect(state.zones).toBeGreaterThan(0);
    expect(state.ghosts).toBeGreaterThan(0);
    expect(state.marks).toBeGreaterThan(1);
    for (const [index, value] of [0.22, 0.61, 1].entries()) {
      expect(state.transition[index]).toBeCloseTo(value, 5);
    }
    for (const other of ["build", "rts", "damage_control"].filter(value => value !== mode)) {
      expect(state.alpha[other]).toBeCloseTo(0.22, 4);
    }
    if (mode !== "rts") {
      expect(state.handler).toBe(false);
      expect(state.bottom).toBe(0);
    }
    if (mode !== "damage_control") {
      expect(state.aimed, "passive damage-control marks cannot own hover or input").toBe(false);
    }
    const widgets = await screen.widgets(client);
    expect(widgets.some(widget => widget.label === "Take in")).toBe(mode === "build");
    await screen.clickWidget(client, { label });
    expect((await client.run(MODE_PROBE)).revision, "the current mode is a no-op").toBe(state.revision);
    if (!evidence[mode]) {
      evidence[mode] = state;
      await media.screenshot(client, { path: path.join(directory, `${mode}.png`) });
    }
  }
  writeFileSync(path.join(directory, "state.json"), JSON.stringify(evidence, null, 2));
}

export const MODE_PROBE = defineTask<void, Modes>({
  name: "folkways.mode-layers.observe",
  side: "client",
  source: javaTask({
    imports: [
      "io.github.izakyl.folkways.front.api.ui.OverheadTools",
      "io.github.izakyl.folkways.front.api.ui.ModeOverlays",
      "io.github.izakyl.folkways.front.client.ColonyHighlightState",
      "net.minecraft.resources.ResourceLocation",
    ],
    body: `
var alpha = new LinkedHashMap<String, Float>();
for (String name : List.of("build", "rts", "damage_control")) {
    alpha.put(name, ModeOverlays.alpha(ResourceLocation.fromNamespaceAndPath("folkways", name)));
}
var aimed = Class.forName("io.github.izakyl.folkways.front.client.ToolOverlay").getDeclaredMethod("aimed");
aimed.setAccessible(true);
var owed = ColonyHighlightState.class.getDeclaredField("OWED");
owed.setAccessible(true);
var ghosts = owed.get(null).getClass().getDeclaredField("shown");
ghosts.setAccessible(true);
var clock = ModeOverlays.class.getDeclaredField("changedAt");
clock.setAccessible(true);
long started = clock.getLong(null);
return Map.of("mode", OverheadTools.mode().toString(), "alpha", alpha,
    "revision", OverheadTools.selectionRevision(),
    "marks", OverheadTools.all().stream().mapToInt(tool -> tool.marks().size()).sum(),
    "zones", ColonyHighlightState.zones().size(), "ghosts", ((List<?>) ghosts.get(owed.get(null))).size(),
    "aimed", ((Optional<?>) aimed.invoke(null)).isPresent(),
    "handler", OverheadTools.selectedHandler().isPresent(),
    "bottom", OverheadTools.selectedHandler().map(OverheadTools.Handler::bottomInset).orElse(0),
    "transition", List.of(ModeOverlays.alpha(OverheadTools.mode(), started),
        ModeOverlays.alpha(OverheadTools.mode(), started + 110),
        ModeOverlays.alpha(OverheadTools.mode(), started + 220)));`,
  }),
});

const PREPARE = defineTask<Scene, boolean>({
  name: "folkways.mode-layers.prepare-overlapping-server-overlays",
  side: "server",
  source: javaTask({
    imports: [
      "io.github.izakyl.folkways.core.colony.api.Colonies",
      "io.github.izakyl.folkways.front.api.Facing",
      "io.github.izakyl.folkways.front.engine.colony.ColonySettings",
      "io.github.izakyl.folkways.front.engine.colony.ZoneDrafts",
      "io.github.izakyl.folkways.plugins.build.Blueprint",
      "io.github.izakyl.folkways.plugins.build.BlueprintBlock",
      "io.github.izakyl.folkways.plugins.build.BlueprintVolume",
      "net.minecraft.core.BlockPos",
      "net.minecraft.resources.ResourceLocation",
      "net.minecraft.server.MinecraftServer",
      "net.minecraft.server.level.ServerLevel",
      "net.minecraft.world.level.block.Blocks",
    ],
    body: `
var args = Args.of(ctx);
var level = ((MinecraftServer) ctx.server()).overworld();
var colony = Colonies.of(level.getServer(), UUID.fromString(args.string("colony"))).orElseThrow();
var origin = new BlockPos((int) args.integer("x"), (int) args.integer("y"), (int) args.integer("z"));
var dc = ResourceLocation.parse("folkways:damage_control");
ZoneDrafts.zone(level, colony, ResourceLocation.parse("folkways:farm"),
    origin.offset(-8, 0, 2), origin.offset(-3, 1, 7), ColonySettings.empty()).orElseThrow();
var protectedArea = ZoneDrafts.zone(level, colony, dc, origin.offset(3, 0, 2), origin.offset(7, 2, 6),
    ColonySettings.empty().with("repair", new ColonySettings.Value.Flag(false))
        .with("extinguish", new ColonySettings.Value.Flag(false))).orElseThrow();
for (int x = 3; x <= 7; x++) {
    level.setBlockAndUpdate(origin.offset(x, 0, 3), Blocks.BRICKS.defaultBlockState());
}
var recorded = Facing.of(colony, dc).orElseThrow().act(dc, "record:" + protectedArea.id(),
    Optional.empty(), colony.view(level));
if (recorded.refusal().isPresent()) {
    throw new IllegalStateException("baseline refused: " + recorded.refusal());
}
level.setBlockAndUpdate(origin.offset(5, 0, 3), Blocks.AIR.defaultBlockState());
level.setBlockAndUpdate(origin.offset(7, 1, 3), Blocks.FIRE.defaultBlockState());
var blocks = new ArrayList<BlueprintBlock>();
for (int x = 0; x < 4; x++) {
    for (int y = 0; y < 3; y++) {
        blocks.add(new BlueprintBlock(new BlockPos(x, y, 0), Blocks.OAK_PLANKS.defaultBlockState()));
    }
}
var blueprint = new Blueprint(UUID.randomUUID(), "Mode layer preview", blocks,
    BlueprintVolume.of(4, 3, 1).orElseThrow(), level.getGameTime());
var build = Facing.of(colony, ResourceLocation.parse("folkways:build")).orElseThrow();
var file = build.getClass().getDeclaredMethod("file", ServerLevel.class, Blueprint.class,
    BlockPos.class, String.class, Optional.class, String.class);
file.setAccessible(true);
var result = (Optional<?>) file.invoke(build, level, blueprint, origin.offset(-2, 0, 2),
    "Mode layer preview", Optional.empty(), "");
if (result.isEmpty()) {
    throw new IllegalStateException("blueprint fixture refused");
}
return true;`,
  }),
});
