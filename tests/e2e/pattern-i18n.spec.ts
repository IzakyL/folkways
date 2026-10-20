import { expect, test } from "@playwright/test";
import { defineTask, javaTask } from "@izakyl/blockwright-minecraft";
import { launchPair, type E2EPair } from "../shared/harness/pair";
import { ensureOut, tracePath } from "../shared/harness/out-paths";
import { seededServer } from "../shared/harness/mod-settings";
import { BUDGET } from "../shared/harness/budgets";
import { newRunId } from "../shared/harness/run-id";

function source(english: string, chinese: string, material: string, chineseMaterial: string) {
  return `lang = ${JSON.stringify({
    en_us: { "gui.done": english, "item.minecraft.stone": material },
    zh_cn: { "gui.done": chinese, "item.minecraft.stone": chineseMaterial },
  })}\ndef draw(site):\n    return []\n`;
}

const TRANSLATE = defineTask<{ first: string; second: string }, {
  english: string[]; chinese: string[]; englishAgain: string[];
  globalEnglish: string; globalChinese: string; isolated: boolean; registered: boolean;
}>({
  name: "folkways.pattern-i18n.client",
  side: "client",
  timeoutMs: 20_000,
  source: javaTask({
    imports: [
      "com.mojang.serialization.JsonOps",
      "io.github.izakyl.folkways.front.api.notice.Notice",
      "io.github.izakyl.folkways.plugins.build.draft.Pattern",
      "io.github.izakyl.folkways.plugins.build.draft.PatternText",
      "net.minecraft.client.Minecraft",
      "net.minecraft.client.resources.language.ClientLanguage",
      "net.minecraft.locale.Language",
      "net.minecraft.network.chat.Component",
      "net.minecraft.network.chat.ComponentSerialization",
      "net.minecraft.resources.ResourceLocation",
    ],
    body: `
var mc = Minecraft.getInstance();
var manager = mc.getLanguageManager();
String previousCode = manager.getSelected();
Language previousLanguage = Language.getInstance();
try {
    var args = Args.of(ctx);
    var first = Pattern.parse(ResourceLocation.parse("local:i18n_first"), args.string("first")).orElseThrow();
    var second = Pattern.parse(ResourceLocation.parse("local:i18n_second"), args.string("second")).orElseThrow();
    var components = new java.util.ArrayList<Component>();
    var keys = new java.util.ArrayList<String>();
    for (var pattern : java.util.List.of(first, second)) {
        String key = PatternText.key(pattern.face(), "gui.done");
        String material = PatternText.key(pattern.face(), "item.minecraft.stone");
        keys.add(key);
        var notice = new Notice(key, java.util.List.of(Notice.named(material)));
        var encoded = ComponentSerialization.CODEC.encodeStart(JsonOps.INSTANCE, PatternText.component(notice))
            .getOrThrow();
        components.add(ComponentSerialization.CODEC.parse(JsonOps.INSTANCE, encoded).getOrThrow());
    }
    manager.setSelected("en_us");
    Language.inject(ClientLanguage.loadFrom(mc.getResourceManager(), java.util.List.of("en_us"), false));
    var english = components.stream().map(Component::getString).toList();
    String globalEnglish = Component.translatable("gui.done").getString();
    boolean registered = keys.stream().allMatch(Language.getInstance()::has);
    manager.setSelected("zh_cn");
    Language.inject(ClientLanguage.loadFrom(mc.getResourceManager(), java.util.List.of("en_us", "zh_cn"), false));
    var chinese = components.stream().map(Component::getString).toList();
    String globalChinese = Component.translatable("gui.done").getString();
    manager.setSelected("en_us");
    Language.inject(ClientLanguage.loadFrom(mc.getResourceManager(), java.util.List.of("en_us"), false));
    var englishAgain = components.stream().map(Component::getString).toList();
    return Map.of("english", english, "chinese", chinese, "englishAgain", englishAgain,
        "globalEnglish", globalEnglish, "globalChinese", globalChinese,
        "isolated", !keys.get(0).equals(keys.get(1)), "registered", registered);
} finally {
    manager.setSelected(previousCode);
    Language.inject(previousLanguage);
}
`,
  }),
});

test("pattern notices retain their own translations after component sync and client language changes", async ({}, testInfo) => {
  test.setTimeout(BUDGET.quick);
  ensureOut("e2e");
  const runId = newRunId();
  let pair: E2EPair | undefined;
  try {
    pair = await launchPair({
      config: "blockwright.toml",
      server: { trace: tracePath("e2e", "pattern-i18n-server"), instance: seededServer(`pattern-i18n-server-${runId}`) },
      client: { trace: tracePath("e2e", "pattern-i18n-client"), instance: `pattern-i18n-client-${runId}` },
    });
    const result = await pair.client.run(TRANSLATE, {
      first: source("First needs %s", "甲需要%s", "first stone", "甲石材"),
      second: source("Second needs %s", "乙需要%s", "second stone", "乙石材"),
    });
    expect(result.isolated, "matching local keys must have separate dictionary scopes").toBe(true);
    expect(result.registered, "ClientLanguage.has must recognize pattern dictionary entries").toBe(true);
    expect(result.english).toEqual(["First needs first stone", "Second needs second stone"]);
    expect(result.chinese).toEqual(["甲需要甲石材", "乙需要乙石材"]);
    expect(result.englishAgain, "already decoded components must follow the current client language").toEqual(result.english);
    expect(result.globalEnglish, "local gui.done must leave the global translation intact").toBe("Done");
    expect(result.globalChinese, "the global catalog must still use the selected language").toBe("完成");
  } catch (error) {
    pair?.failing(error);
    throw error;
  } finally {
    await pair?.teardown(testInfo);
  }
});
