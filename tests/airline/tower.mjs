import assert from "node:assert/strict";
import { mkdir, readFile, writeFile } from "node:fs/promises";
import path from "node:path";
import { defineTask, input, javaTask, launchGame, media, tick } from "@izakyl/blockwright-minecraft";

const out = path.resolve("build/reports/airline-tower");
await mkdir(out, { recursive: true });
const fixture = defineTask({
  name: "airline.tower.real-fixture", side: "server",
  source: javaTask({
    imports: [
      "io.github.izakyl.folkways.airline.AirlineContent",
      "io.github.izakyl.folkways.airline.Airports",
      "io.github.izakyl.folkways.airline.AirportTowerBlock",
      "io.github.izakyl.folkways.core.colony.api.Colonies",
      "io.github.izakyl.folkways.core.colony.api.Claimed",
      "io.github.izakyl.folkways.core.world.api.WorldPos",
      "io.github.izakyl.folkways.vanilla.person.PersonBody",
      "io.github.izakyl.folkways.vanilla.person.PersonContent",
      "net.minecraft.core.registries.BuiltInRegistries",
      "net.minecraft.core.BlockPos",
      "net.minecraft.core.Direction",
      "net.minecraft.resources.ResourceLocation",
      "net.minecraft.world.level.block.Blocks",
    ],
    body: `
var level = ctx.server().overworld();
level.noSave = true;
var player = ctx.server().getPlayerList().getPlayers().getFirst();
var base = new BlockPos(player.blockPosition().getX(), 100, player.blockPosition().getZ());
var first = base.offset(-20, 24, 30);
var second = base.offset(60, 24, 30);
for (int x = (first.getX() - 388) >> 4; x <= (second.getX() + 388) >> 4; x++) {
    for (int z = (first.getZ() - 388) >> 4; z <= (first.getZ() + 388) >> 4; z++) {
        level.setChunkForced(x, z, true);
        level.getChunk(x, z);
    }
}
for (int d = 0; d < 48; d++) {
    for (int side = -2; side <= 2; side++) {
        level.setBlockAndUpdate(base.offset(d, 0, side),
            (Math.abs(side) == 2
                || (d >= 2 && d < 6 || d > 41 && d <= 45) && side == 0
                || d > 8 && d < 39 && side == 0 && d % 10 < 6
                ? Blocks.WHITE_CONCRETE : Blocks.GRAY_CONCRETE).defaultBlockState());
    }
}
for (int x = -3; x <= 3; x++) {
    for (int z = -3; z <= 3; z++) {
        var material = Math.abs(x) == 3 || Math.abs(z) == 3 ? Blocks.WHITE_CONCRETE
            : Math.abs(z) <= 2 && (Math.abs(x) == 1 || z == 0 && Math.abs(x) <= 1)
                ? Blocks.WHITE_CONCRETE : Blocks.GRAY_CONCRETE;
        level.setBlockAndUpdate(base.offset(20 + x, 0, 140 + z), material.defaultBlockState());
    }
}
var colony = Colonies.mint(level.getServer());
var owner = ResourceLocation.fromNamespaceAndPath("folkways_airline", "airport_tower");
for (var pos : List.of(first, second)) {
    var facing = pos.equals(first) ? Direction.WEST : Direction.EAST;
    level.setBlockAndUpdate(pos, AirlineContent.AIRPORT_TOWER.get().defaultBlockState()
        .setValue(AirportTowerBlock.FACING, facing));
    colony.hold(owner, new Claimed.Block(WorldPos.of(level, pos))).orElseThrow();
    Airports.register(level, pos);
    Airports.rename(level, pos, "Harbor Airfield");
    Airports.request(level, pos);
}
for (int x = -1; x <= 1; x++) {
    for (int z = -1; z <= 1; z++) {
        level.setBlockAndUpdate(first.offset(x, -1, z), Blocks.GLASS.defaultBlockState());
    }
}
var seat = first.west();
level.setBlockAndUpdate(seat, BuiltInRegistries.BLOCK.get(ResourceLocation.parse("create:white_seat"))
    .defaultBlockState());
var resident = PersonBody.RESIDENT.get().create(level);
resident.moveTo(seat.getX() + 0.5, seat.getY(), seat.getZ() + 1.5);
level.addFreshEntity(resident);
colony.hold(PersonContent.ID, Claimed.Entity.of(resident)).orElseThrow();
resident.joinColony(colony.id());
player.teleportTo(level, first.getX() + 0.5, first.getY() + 1, first.getZ() + 3.5, 180, 0);
player.setNoGravity(true);
player.setGameMode(net.minecraft.world.level.GameType.CREATIVE);
return Map.of("x", first.getX(), "y", first.getY(), "z", first.getZ());
`,
  }),
});
const inspect = defineTask({
  name: "airline.tower.inspect", side: "server",
  source: javaTask({
    imports: ["io.github.izakyl.folkways.airline.Airports", "net.minecraft.core.BlockPos"],
    body: `
var args = Args.of(ctx);
var pos = new BlockPos((int) args.integer("x"), (int) args.integer("y"), (int) args.integer("z"));
var level = ctx.server().overworld();
var airport = Airports.at(level, pos).orElseThrow();
return Map.of("scanning", Airports.scanning(level, pos), "runways", airport.runways().size(),
    "towers", airport.towers().size(), "staffed", airport.towers().stream().filter(t -> t.staffed()).count(), "uncovered", airport.coverage().stream().filter(f -> !f.covered()).count(),
    "flaws", airport.flaws().toString(),
    "pad", airport.coverage().stream().filter(f -> f.area().contains(pos.offset(40, -24, 110)))
        .map(f -> Map.of("covered", f.covered(), "reason", f.reason())).toList());
`,
  }),
});
const open = defineTask({
  name: "airline.tower.open", side: "server",
  source: javaTask({ imports: ["com.lowdragmc.lowdraglib2.gui.factory.BlockUIMenuType",
    "net.minecraft.core.BlockPos"], body: `
var args = Args.of(ctx);
var pos = new BlockPos((int) args.integer("x"), (int) args.integer("y"), (int) args.integer("z"));
BlockUIMenuType.openUI(ctx.server().getPlayerList().getPlayers().getFirst(), pos);
return true;
` }),
});
const language = defineTask({
  name: "airline.tower.language", side: "client",
  source: javaTask({ imports: ["net.minecraft.client.Minecraft"], body: `
var args = Args.of(ctx);
var minecraft = (Minecraft) ctx.client();
minecraft.getLanguageManager().setSelected(args.string("locale"));
minecraft.options.languageCode = args.string("locale");
minecraft.reloadResourcePacks();
return true;
` }),
});
// Where a world cell's centre falls on the chart, read from the frame the map last drew with.
const spot = defineTask({
  name: "airline.tower.spot", side: "client",
  source: javaTask({ imports: ["net.minecraft.client.Minecraft",
    "com.lowdragmc.lowdraglib2.gui.holder.ModularUIContainerMenu"], body: `
var args = Args.of(ctx);
var minecraft = (Minecraft) ctx.client();
var screen = (net.minecraft.client.gui.screens.inventory.AbstractContainerScreen<?>) minecraft.screen;
var map = ((ModularUIContainerMenu) screen.getMenu()).getModularUI()
    .getElementById("folkways.airline.airport.map");
java.util.function.Function<String, Float> field = name -> {
    try {
        var f = map.getClass().getDeclaredField(name);
        f.setAccessible(true);
        return f.getFloat(map);
    } catch (ReflectiveOperationException e) {
        throw new IllegalStateException(e);
    }
};
float scale = field.apply("scale");
float x = field.apply("originX") + ((float) args.integer("x") + 0.5f - field.apply("minX")) * scale;
float y = field.apply("originY") + ((float) args.integer("z") + 0.5f - field.apply("minZ")) * scale;
return Map.of("x", (double) x, "y", (double) y, "scale", (double) scale);
` }),
});
const catalog = JSON.parse(await readFile("mods/airline/src/main/resources/assets/folkways_airline/lang/en_us.json", "utf8"));
const pageKeys = Object.keys(catalog).filter(key => key.startsWith("folkways.airline.airport."));
assert(pageKeys.length > 0, "tower translation catalog is populated");
const translations = defineTask({
  name: "airline.tower.translations", side: "client",
  source: javaTask({ imports: ["net.minecraft.client.resources.language.I18n"], body: `
var args = Args.of(ctx);
var missing = new java.util.ArrayList<String>();
for (var key : args.string("keys").split("\\n")) {
    if (!I18n.exists(key) || I18n.get(key).equals(key)) {
        missing.add(key);
    }
}
return missing;
` }),
});
const cleanup = defineTask({
  name: "airline.tower.cleanup", side: "server",
  source: javaTask({ body: `
var args = Args.of(ctx);
var level = ctx.server().overworld();
int x = (int) args.integer("x");
int z = (int) args.integer("z");
for (int cx = (x - 388) >> 4; cx <= (x + 80 + 388) >> 4; cx++) {
    for (int cz = (z - 388) >> 4; cz <= (z + 388) >> 4; cz++) {
        var chunk = level.getChunkSource().getChunkNow(cx, cz);
        if (chunk != null) {
            chunk.setUnsaved(false);
        }
        level.setChunkForced(cx, cz, false);
    }
}
return true;
` }),
});
let game;
let at;
const evidence = {};
try {
  game = await launchGame({ cwd: process.cwd(), config: "blockwright.toml#airline",
    server: { instance: "airline-tower-server", startFrozen: false },
    client: { instance: "airline-tower-client" }, retry: { attempts: 1, when: () => false } });
  at = await game.server.run(fixture);
  for (let attempt = 0; attempt < 100; attempt++) {
    await tick.sprint(game.server, 20);
    evidence.scan = await game.server.run(inspect, at);
    if (!evidence.scan.scanning && evidence.scan.towers === 2 && evidence.scan.staffed === 1) {
      break;
    }
  }
  assert.equal(evidence.scan.runways, 1);
  assert.equal(evidence.scan.towers, 2);
  assert(evidence.scan.uncovered > 0);
  assert.deepEqual(evidence.scan.pad, [{ covered: false, reason: "sector" }]);
  assert.equal(evidence.scan.staffed, 1);
  for (const locale of ["en_us", "zh_cn", "zh_hk", "zh_tw"]) {
    await game.client.run(language, { locale });
    await game.client.waitUntil({ clock: "frame", timeoutMs: 30000,
      check: `return ((net.minecraft.client.Minecraft) ctx.client()).getOverlay() == null
        ? true : Wait.pending("resource reload");` });
    await game.server.run(open, at);
    await game.client.waitUntil({ clock: "frame", timeoutMs: 20000,
      check: `var minecraft = (net.minecraft.client.Minecraft) ctx.client();
        minecraft.getToasts().clear();
        return minecraft.screen != null && minecraft.screen.getClass().getName().contains("Modular")
          ? true : Wait.pending("airport UI");` });
    await tick.sprint(game.server, 30);
    assert.deepEqual(await game.client.run(translations, { keys: pageKeys.join("\n") }), [],
      `${locale}: tower page has no raw translation keys`);
    await media.screenshot(game.client, { path: path.join(out, `tower-${locale}.png`) });
    if (locale === "en_us" || locale === "zh_cn") {
      // Base of the fixture: the runway runs east from it, its 09 end on the base itself; the pad 140 blocks
      // south lies between the two sectors, outside coverage.
      const base = { x: at.x + 20, z: at.z - 30 };
      const targets = { tower: { x: at.x, z: at.z }, runway: { x: base.x + 24, z: base.z },
        end: { x: base.x - 2, z: base.z }, uncovered: { x: base.x + 20, z: base.z + 140 } };
      for (const [name, cell] of Object.entries(targets)) {
        if (locale === "zh_cn" && name !== "tower") {
          continue;
        }
        await input.move(game.client, await game.client.run(spot, cell));
        // A tooltip shows once the pointer rests; give it a moment of rendered frames.
        await new Promise(resolve => setTimeout(resolve, 1000));
        await media.screenshot(game.client, { path: path.join(out, `tower-${locale}-hover-${name}.png`) });
      }
    }
    if (locale === "en_us") {
      // Zoom in on the 09 end with the wheel, then drag the chart right and down.
      const end = await game.client.run(spot, { x: at.x + 18, z: at.z - 30 });
      const cursor = { x: end.x, y: end.y };
      await input.scroll(game.client, { at: cursor, yOffset: 1, count: 4 });
      await new Promise(resolve => setTimeout(resolve, 500));
      await media.screenshot(game.client, { path: path.join(out, `tower-${locale}-zoomed.png`) });
      // The wheel is anchored: the end stays under the cursor.
      evidence.zoom = { cursor: end, end: await game.client.run(spot, { x: at.x + 18, z: at.z - 30 }) };
      assert(evidence.zoom.end.scale > end.scale, "wheel increases chart scale");
      assert(Math.abs(evidence.zoom.end.x - end.x) <= 2 && Math.abs(evidence.zoom.end.y - end.y) <= 2,
        "zoom preserves the world cell under the cursor");
      await input.drag(game.client, { from: cursor, to: { x: end.x + 120, y: end.y + 60 }, steps: 12 });
      await input.move(game.client, { x: 0, y: 0 });
      await new Promise(resolve => setTimeout(resolve, 500));
      await media.screenshot(game.client, { path: path.join(out, `tower-${locale}-panned.png`) });
      evidence.pan = { by: { x: 120, y: 60 }, end: await game.client.run(spot, { x: at.x + 18, z: at.z - 30 }) };
      assert(Math.abs(evidence.pan.end.x - evidence.zoom.end.x - 120) <= 2
        && Math.abs(evidence.pan.end.y - evidence.zoom.end.y - 60) <= 2, "drag pans by the pointer displacement");
    }
  }
} catch (error) {
  evidence.error = String(error?.stack ?? error);
  console.error(error);
  if (game) {
    await media.screenshot(game.client, { path: path.join(out, "tower-failure.png") }).catch(() => {});
  }
  process.exitCode = 1;
} finally {
  if (game && at) {
    await game.server.run(cleanup, at).catch(error => { evidence.cleanupError = String(error); });
  }
  evidence.close = await game?.close();
  await writeFile(path.join(out, "tower-client-evidence.json"), JSON.stringify(evidence, null, 2));
}
