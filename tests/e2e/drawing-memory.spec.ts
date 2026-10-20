import { expect, test } from "@playwright/test";
import { defineTask, javaTask } from "@izakyl/blockwright-minecraft";
import { launchPair, type E2EPair } from "../shared/harness/pair";
import { ensureOut, tracePath } from "../shared/harness/out-paths";
import { seededServer } from "../shared/harness/mod-settings";
import { BUDGET } from "../shared/harness/budgets";
import { newRunId } from "../shared/harness/run-id";

const MEMORY = defineTask<void, Record<string, boolean>>({
  name: "folkways.drawing-memory.persistence",
  side: "client",
  timeoutMs: 20_000,
  source: javaTask({
    imports: [
      "io.github.izakyl.folkways.FolkwaysClientConfig",
      "io.github.izakyl.folkways.front.ui.screen.RememberedSettings",
      "io.github.izakyl.folkways.front.api.Schema",
      "io.github.izakyl.folkways.plugins.build.draft.client.KnobValues",
      "net.minecraft.resources.ResourceLocation",
      "net.minecraft.nbt.CompoundTag",
      "net.neoforged.fml.loading.FMLPaths",
      "java.nio.file.Files",
    ],
    body: `
var path = FMLPaths.CONFIGDIR.get().resolve("folkways-drawing-settings.properties");
byte[] before = Files.exists(path) ? Files.readAllBytes(path) : null;
boolean enabled = FolkwaysClientConfig.rememberDrawingSettings();
try {
    FolkwaysClientConfig.rememberDrawingSettings(true);
    var a = new CompoundTag(); a.putInt("width", 99);
    var b = new CompoundTag(); b.putInt("width", -9);
    RememberedSettings.save("pattern/folkways:memory_a", a);
    RememberedSettings.save("pattern/folkways:memory_b", b);
    var disk = new java.util.Properties();
    try (var input = Files.newInputStream(path)) { disk.load(input); }
    boolean persisted = disk.getProperty("pattern/folkways:memory_a").equals(a.toString())
        && disk.getProperty("pattern/folkways:memory_b").equals(b.toString());
    boolean isolated = RememberedSettings.load("pattern/folkways:memory_a").orElseThrow().getInt("width") == 99
        && RememberedSettings.load("pattern/folkways:memory_b").orElseThrow().getInt("width") == -9
        && RememberedSettings.load("folkways:memory_a").isEmpty();
    var stone = ResourceLocation.parse("minecraft:stone");
    var dirt = ResourceLocation.parse("minecraft:dirt");
    var schema = new Schema(List.of(
        new Schema.Setting.Count("width", "", stone, 2, 8, 4),
        new Schema.Setting.Flag("flag", "", stone, true),
        new Schema.Setting.Choice("choice", "", stone, List.of(stone, dirt), stone),
        Schema.Setting.Items.oneBlock("material", "", stone, stone)));
    var load = KnobValues.class.getDeclaredMethod("load", Schema.class, CompoundTag.class);
    load.setAccessible(true);
    var saved = a.copy(); saved.putBoolean("flag", false);
    saved.putString("choice", "minecraft:dirt"); saved.put("material", new CompoundTag());
    var restored = (KnobValues) load.invoke(null, schema, saved);
    boolean restoredValues = restored.count("width") == 8 && !restored.flag("flag")
        && restored.choice("choice").equals(dirt) && restored.items("material").isEmpty();
    b.putString("choice", "minecraft:missing_option");
    var fallback = (KnobValues) load.invoke(null, schema, b);
    boolean conformed = fallback.count("width") == 2 && fallback.flag("flag")
        && fallback.choice("choice").equals(stone) && fallback.items("material").isPresent();
    byte[] written = Files.readAllBytes(path);
    FolkwaysClientConfig.rememberDrawingSettings(false);
    boolean disabledRead = RememberedSettings.load("pattern/folkways:memory_a").isEmpty();
    RememberedSettings.save("pattern/folkways:memory_a", b);
    boolean disabledWrite = java.util.Arrays.equals(written, Files.readAllBytes(path));
    FolkwaysClientConfig.rememberDrawingSettings(true);
    boolean preserved = RememberedSettings.load("pattern/folkways:memory_a").orElseThrow().equals(a);
    return Map.of("persisted", persisted, "isolated", isolated, "restored", restoredValues,
        "conformed", conformed, "disabledRead", disabledRead, "disabledWrite", disabledWrite,
        "preserved", preserved);
} finally {
    FolkwaysClientConfig.rememberDrawingSettings(enabled);
    if (before == null) Files.deleteIfExists(path); else Files.write(path, before);
}
`,
  }),
});

test("drawing memory persists separate patterns and conforms saved knobs to the current schema", async ({}, testInfo) => {
  test.setTimeout(BUDGET.quick);
  ensureOut("e2e");
  const runId = newRunId();
  let pair: E2EPair | undefined;
  try {
    pair = await launchPair({
      server: { trace: tracePath("e2e", "drawing-memory-server"), instance: seededServer(`drawing-memory-server-${runId}`) },
      client: { trace: tracePath("e2e", "drawing-memory-client"), instance: `drawing-memory-client-${runId}` },
    });
    const result = await pair.client.run(MEMORY, undefined, { timeoutMs: 20_000 });
    expect(result).toEqual({ persisted: true, isolated: true, restored: true, conformed: true,
      disabledRead: true, disabledWrite: true, preserved: true });
  } catch (error) {
    pair?.failing(error);
    throw error;
  } finally {
    await pair?.teardown(testInfo);
  }
});
