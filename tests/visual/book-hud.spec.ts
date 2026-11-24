import { expect, test } from "@playwright/test";
import { defineTask, input, javaTask, screen } from "@izakyl/blockwright-minecraft";
import { panelElements } from "../ldlib2";
import { foundColonyFast, openColonyPanelViaBook, waitForGroundedPlayer, type Vec } from "../shared/colony/colony-founding";
import { QUIET_TOASTS } from "../shared/capture/ponder";
import { VisualRun } from "../shared/capture/visual-kit";
import { BUDGET } from "../shared/harness/budgets";
import { commandPos, frames, tryCommand } from "../shared/harness/bw-helpers";
import { tracePath } from "../shared/harness/out-paths";
import { launchPair, type E2EPair } from "../shared/harness/pair";
import { newRunId } from "../shared/harness/run-id";
import { waitForPresent } from "../shared/ui/book-cards";
import { clickTab } from "../shared/ui/colony-pages";

const INVITE = defineTask<void, boolean>({
  name: "folkways.visual.members-invitation", side: "server",
  source: javaTask({
    imports: ["io.github.izakyl.folkways.core.colony.api.Colonies",
      "io.github.izakyl.folkways.core.colony.charter.Charter", "io.github.izakyl.folkways.core.colony.charter.Charters"],
    body: `
var player = ctx.server().getPlayerList().getPlayers().getFirst();
var founder = UUID.nameUUIDFromBytes("visual-airfield-founder".getBytes(java.nio.charset.StandardCharsets.UTF_8));
ctx.server().getProfileCache().add(new com.mojang.authlib.GameProfile(founder, "Mira"));
var neighbour = Colonies.mint(ctx.server());
Charters.keep(neighbour, Charter.founded("Harbor colony", founder).inviting(player.getUUID(), founder));
return true;
`,
  }),
});

const OPEN_CAMERA_CONFIG = defineTask<void, boolean>({
  name: "folkways.visual.camera-config", side: "client",
  source: javaTask({
    imports: ["io.github.izakyl.folkways.core.front.client.PersonalCameraConfigScreen",
      "net.minecraft.client.Minecraft", "net.neoforged.fml.ModList"],
    body: `
var mc = Minecraft.getInstance();
var mod = ModList.get().getModContainerById("folkways_core").orElseThrow();
mc.setScreen(PersonalCameraConfigScreen.open(mod, mc.screen));
return true;
`,
  }),
});

// NeoForge's configuration buttons live inside native list entries.
const CONFIG_BUTTON = defineTask<{ label: string }, { x: number; y: number }>({
  name: "folkways.visual.config-button", side: "client",
  source: javaTask({
    imports: ["net.minecraft.client.Minecraft", "net.minecraft.client.gui.components.Button",
      "net.minecraft.client.gui.components.events.ContainerEventHandler",
      "net.minecraft.client.gui.components.events.GuiEventListener"],
    body: `
String label = Args.of(ctx).string("label");
var pending = new ArrayDeque<GuiEventListener>();
pending.add(Minecraft.getInstance().screen);
while (!pending.isEmpty()) {
    var widget = pending.removeFirst();
    if (widget instanceof Button button && button.active && button.visible
            && button.getMessage().getString().equals(label)) {
        return Map.of("x", button.getX() + button.getWidth() / 2, "y", button.getY() + button.getHeight() / 2);
    }
    if (widget instanceof ContainerEventHandler container) {
        pending.addAll(container.children());
    }
}
throw Fail.notFound("No active configuration button " + label);
`,
  }),
});

// Supply the marked bounds directly so the takes depend only on what the box can become and real blocks in it:
// with an offer the box goes straight to it, without one the book asks what the box becomes.
const MARK_AND_USE = defineTask<Vec & { offer: string }, boolean>({
  name: "folkways.visual.mark-and-use", side: "client",
  source: javaTask({
    imports: ["io.github.izakyl.folkways.core.front.client.BookTools",
      "io.github.izakyl.folkways.core.front.client.ZoneSelection", "net.minecraft.core.BlockPos"],
    body: `
var args = Args.of(ctx);
var first = new BlockPos((int) args.integer("x"), (int) args.integer("y"), (int) args.integer("z"));
var offer = args.string("offer");
BookTools.choose("offer:folkways_core:sweep");
var box = ZoneSelection.class.getDeclaredMethod("setBox", BlockPos.class, BlockPos.class);
box.setAccessible(true);
box.invoke(null, first, first.east(4));
return offer.isEmpty() ? BookTools.hand() : BookTools.handTo(offer);
`,
  }),
});

test("book hints, Members invitation, sweep and personal camera settings", async ({}, testInfo) => {
  test.setTimeout(BUDGET.standard);
  const id = newRunId();
  const run = new VisualRun("book-hud");
  let pair: E2EPair | undefined;
  try {
    pair = await launchPair({
      server: { instance: `visual-book-hud-server-${id}`, trace: tracePath("visual", "book-hud-server") },
      client: { instance: `visual-book-hud-client-${id}`, trace: tracePath("visual", "book-hud-client") },
    });
    const { server, client } = pair;
    const player = await waitForGroundedPlayer(server);
    const origin = { x: Math.round(player.pos.x) + 4, y: Math.round(player.pos.y), z: Math.round(player.pos.z) + 4 };
    const { standBlock } = await foundColonyFast(server, client, player.name, { origin, residentCount: 1 });
    const shot = async (name: string, subject: string) => {
      await input.move(client, { x: 0, y: 0 });
      await frames(client, 5);
      await run.shot(client, name, { subject, worldState: {}, hard: true });
    };
    await openColonyPanelViaBook(server, client, player.name, standBlock);
    await screen.dismiss(client);
    await client.run(QUIET_TOASTS);
    await shot("key-hints", "Key hints with the Colony Book in hand");
    await server.run(INVITE);
    await openColonyPanelViaBook(server, client, player.name, standBlock);
    await clickTab(client, "folkways.tab.members");
    await waitForPresent(server, client, "folkways.charter.members.invitations");
    expect((await panelElements(client)).some(element => element.text?.includes("Harbor colony"))).toBe(true);
    await shot("members-invitation", "Members invitation banner");
    await screen.dismiss(client);

    const first = { ...origin, x: origin.x + 2, z: origin.z + 6 };
    for (const [offset, block] of [[0, "smooth_stone"], [1, "composter"], [2, "chest"], [4, "furnace"]] as const) {
      await tryCommand(server, `setblock ${commandPos({ ...first, x: first.x + offset })} minecraft:${block}`);
    }
    await frames(client, 5);
    expect(await client.run(MARK_AND_USE, { ...first, offer: "" })).toBe(true);
    await waitForPresent(server, client, "folkways.book.pick.folkways_core.sweep");
    expect((await panelElements(client)).some(element => element.id === "folkways.book.pick.folkways_vanilla.farm"))
      .toBe(true);
    await shot("shape-picker", "What a marked box becomes, by category");
    await screen.dismiss(client);
    expect(await client.run(MARK_AND_USE, { ...first, offer: "offer:folkways_core:sweep" })).toBe(true);
    await waitForPresent(server, client, "folkways.sweep.take");
    await shot("sweep-offer", "Block sweep with store, station and composter options");
    await screen.dismiss(client);

    await client.run(OPEN_CAMERA_CONFIG);
    await frames(client, 5);
    await input.click(client, await client.run(CONFIG_BUTTON, { label: "Client Settings..." }), { button: "left" });
    await screen.waitFor(client, "PersonalCameraConfigScreen");
    await input.click(client, await client.run(CONFIG_BUTTON, { label: "Personal settings" }), { button: "left" });
    await frames(client, 5);
    await shot("personal-camera-config", "Personal tactical camera control");
  } catch (error) {
    pair?.failing(error);
    throw error;
  } finally {
    await pair?.teardown(testInfo);
  }
});
