import { expect } from "@playwright/test";
import { defineTask, javaTask, type MinecraftClient } from "@izakyl/blockwright-minecraft";
import { readFileSync } from "node:fs";
import { frames } from "../harness/bw-helpers";

export const PONDER_FOLDERS = defineTask<void, { id: string; scenes: string[] }[]>({
  name: "folkways.visual.ponder-folders", side: "client",
  source: javaTask({ imports: ["io.github.izakyl.folkways.front.api.ponder.Scenes"], body: `
return Scenes.declared().stream().map(folder -> Map.of("id", folder.id().toString(),
 "scenes", folder.scenes().stream().map(scene -> scene.id().toString()).toList())).toList();
` }),
});

export const OPEN_PONDER = defineTask<{ id: string }, boolean>({
  name: "folkways.visual.ponder-open", side: "client",
  source: javaTask({ imports: ["io.github.izakyl.folkways.front.client.ponder.PonderHint", "net.minecraft.resources.ResourceLocation", "net.minecraft.client.Minecraft"], body: `
Minecraft.getInstance().setScreen(null);
PonderHint.play(ResourceLocation.parse(Args.of(ctx).string("id"))); return true;
` }),
});

export const READ_PONDER = defineTask<{ seek: number }, { id: string; total: number; time: number; keyframes: number[]; text: string[] }>({
  name: "folkways.visual.ponder-read", side: "client", timeoutMs: 20_000,
  source: javaTask({ imports: ["net.minecraft.client.Minecraft", "net.createmod.ponder.foundation.ui.PonderUI",
    "net.createmod.ponder.foundation.element.TextWindowElement"], body: `
var ui = (PonderUI)((Minecraft)ctx.client()).screen;
int seek = (int)Args.of(ctx).number("seek");
if (seek >= 0) ui.seekToTime(seek);
var scene = ui.getActiveScene();
var keyframes = new ArrayList<Integer>();
for (int i = 0; i < scene.getKeyframeCount(); i++) keyframes.add(scene.getKeyframeTime(i));
var text = new ArrayList<String>();
var getter = TextWindowElement.class.getDeclaredField("textGetter"); getter.setAccessible(true);
scene.forEachVisible(TextWindowElement.class, element -> {
 try { text.add(((java.util.function.Supplier<String>)getter.get(element)).get()); }
 catch (Exception error) { throw new RuntimeException(error); }
});
return Map.of("id", scene.getId().toString(), "total", scene.getTotalTime(), "time", scene.getCurrentTime(),
 "keyframes", keyframes, "text", text);
` }),
});

const SET_LANGUAGE = defineTask<{ locale: string }, string>({
  name: "folkways.visual.language", side: "client", timeoutMs: 20_000,
  source: javaTask({ imports: ["net.minecraft.client.Minecraft"], body: `
var minecraft = (Minecraft)ctx.client(); var locale = Args.of(ctx).string("locale");
minecraft.getTutorial().setStep(net.minecraft.client.tutorial.TutorialSteps.NONE);
minecraft.getLanguageManager().setSelected(locale); minecraft.options.languageCode = locale;
minecraft.reloadResourcePacks(); return locale;
` }),
});
const LANGUAGE_READY = defineTask<void, string>({
  name: "folkways.visual.language-ready", side: "client",
  source: javaTask({ imports: ["net.minecraft.network.chat.Component"], body: `
return Component.translatable("folkways.ponder.housing.header").getString();
` }),
});
export const SET_GUI_SCALE = defineTask<{ scale: number }, boolean>({
  name: "folkways.visual.gui-scale", side: "client",
  source: javaTask({ imports: ["net.minecraft.client.Minecraft"], body: `
var minecraft = (Minecraft)ctx.client();
minecraft.options.guiScale().set((int)Args.of(ctx).number("scale")); minecraft.resizeDisplay(); return true;
` }),
});

export function ponderCatalog(locale: string): Record<string, string> {
  return JSON.parse(readFileSync(`mods/core/src/main/resources/assets/folkways/lang/${locale}.json`, "utf8"));
}

export async function setVisualLanguage(client: MinecraftClient, locale: string) {
  await client.run(SET_LANGUAGE, { locale });
  await expect.poll(() => client.run(LANGUAGE_READY), { timeout: 60_000 })
    .toBe(ponderCatalog(locale)["folkways.ponder.housing.header"]);
  await frames(client, 5);
}

export const QUIET_TOASTS = defineTask<void, boolean>({
  name: "folkways.visual.quiet-toasts", side: "client",
  source: javaTask({ imports: ["net.minecraft.client.Minecraft"], body: `
Minecraft.getInstance().getToasts().clear(); return true;
` }),
});
