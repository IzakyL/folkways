import { expect, test } from "@playwright/test";
import { defineTask, input, javaTask, media, tick } from "@izakyl/blockwright-minecraft";
import { clickElement, panelElements, selectElement, waitForElement } from "../ldlib2";
import { waitForGroundedPlayer } from "../shared/colony/colony-founding";
import { foundBookColony, layFloor, openRtsTool } from "../shared/combat/overhead";
import { PREPARE_RESIDENTS, PROJECT, type Vec } from "../shared/combat/rts-fixtures";
import { BUDGET } from "../shared/harness/budgets";
import { frames } from "../shared/harness/bw-helpers";
import { launchPair, type E2EPair } from "../shared/harness/pair";
import { newRunId } from "../shared/harness/run-id";
import { mustCommand } from "../shared/ui/world-ui";

const FACILITY = "folkways.card.";
const chip = (choice: string, option: string) => ({ id: `${FACILITY}choice.${choice}.${option}` });

test("RTS facility card choices save a parameter and run a routine with the chosen value", async ({}, testInfo) => {
  test.setTimeout(BUDGET.standard);
  let pair: E2EPair | undefined;
  try {
    const run = newRunId();
    pair = await launchPair({
      config: "blockwright.toml#rts",
      server: { instance: `rts-choice-server-${run}` },
      client: { instance: `rts-choice-client-${run}` },
      snapshot: { entityTypes: ["folkways_vanilla:resident"] },
    });
    const { server, client } = pair;
    const player = await waitForGroundedPlayer(server);
    const origin = { x: Math.round(player.pos.x) + 4, y: Math.round(player.pos.y), z: Math.round(player.pos.z) + 4 };
    for (const command of ["difficulty peaceful", "gamerule doMobSpawning false", "gamerule doDaylightCycle false",
      "time set noon"]) {
      await mustCommand(server, command);
    }
    await layFloor(server, { x: origin.x - 16, z: origin.z - 10 }, { x: origin.x + 16, z: origin.z + 24 }, origin.y);
    const { colony } = await foundBookColony(server, client, player.name, origin, 1);
    expect(await server.run(PREPARE_RESIDENTS, { colony, ...origin, count: 1 })).toHaveLength(1);
    const site = { ...origin, z: origin.z + 10, colony };
    await server.run(SET_UP_GEARBOX, site);
    await mustCommand(server, `tp ${player.name} ${origin.x + 0.5} ${origin.y} ${origin.z + 2.5} 0 0`);
    await tick.sprint(server, 10);
    await frames(client, 10);
    await openRtsTool(client);
    await tick.sprint(server, 20);
    await frames(client, 5);

    const at = await client.run(PROJECT, { ...site, entity: "" });
    await input.click(client, { x: at.x, y: at.y }, { button: "left" });
    await waitForElement(client, { id: "folkways.window.folkways_rts_unit" });
    await waitForElement(client, chip("shell", "he"));
    const card = () => panelElements(client);
    const chosen = async (choice: string, option: string) =>
      selectElement(await card(), chip(choice, option))?.classes?.includes("folkways_chosen") ?? false;

    // The default fills the unset parameter, and the card presses it in.
    expect((await server.run(READ_GEARBOX, site)).shell).toBe("he");
    expect(await chosen("shell", "he")).toBe(true);
    expect(await chosen("gear", "low")).toBe(true);
    await shoot(client, testInfo, "choice-scale-2", 2);
    await shoot(client, testInfo, "choice-scale-3", 3);
    await setGuiScale(client, 2);

    await clickElement(client, chip("shell", "shrapnel"));
    await expect.poll(async () => {
      await tick.sprint(server, 10);
      await frames(client, 3);
      return { shell: (await server.run(READ_GEARBOX, site)).shell, chosen: await chosen("shell", "shrapnel") };
    }).toEqual({ shell: "shrapnel", chosen: true });

    // A choice with `run` saves the value, then runs its routine with it; the lever follows the gear.
    await clickElement(client, chip("gear", "high"));
    await expect.poll(async () => {
      await tick.sprint(server, 15);
      await frames(client, 3);
      const { gear, lever } = await server.run(READ_GEARBOX, site);
      return { gear, lever, done: (await card()).some(element => element.text === "Last: shift · done") };
    }, { timeout: 45_000 }).toEqual({ gear: "high", lever: true, done: true });

    // The shell choice is enabled only while the lever is off.
    await expect.poll(async () => {
      await tick.sprint(server, 10);
      await frames(client, 3);
      return selectElement(await card(), chip("shell", "solid"))?.active;
    }).toBe(false);
    await shoot(client, testInfo, "choice-disabled-scale-2", 2);
  } catch (error) {
    pair?.failing(error);
    throw error;
  } finally {
    await pair?.teardown(testInfo);
  }
});

async function shoot(client: E2EPair["client"], testInfo: import("@playwright/test").TestInfo, name: string,
  scale: number) {
  await setGuiScale(client, scale);
  const path = testInfo.outputPath(`${name}.png`);
  await media.screenshot(client, { path, hideGui: false });
  await testInfo.attach(name, { path, contentType: "image/png" });
}

async function setGuiScale(client: E2EPair["client"], scale: number) {
  await client.run(GUI_SCALE, { scale });
  await frames(client, 5);
}

const GUI_SCALE = defineTask<{ scale: number }, boolean>({
  name: "folkways.rts-facility-choice.gui-scale", side: "client",
  source: javaTask({
    imports: ["net.minecraft.client.Minecraft"],
    body: `var mc = Minecraft.getInstance(); mc.options.guiScale().set((int) Args.of(ctx).number("scale"));
mc.resizeDisplay(); mc.getToasts().clear(); return true;`,
  }),
});

const SERVER_IMPORTS = [
  "net.minecraft.server.MinecraftServer",
  "net.minecraft.core.BlockPos",
  "net.minecraft.world.level.block.Blocks",
  "net.minecraft.world.level.block.LeverBlock",
  "io.github.izakyl.folkways.core.colony.api.Colonies",
  "io.github.izakyl.folkways.core.facility.api.DefinedFacilities",
];

const SCENE = `
var args = Args.of(ctx);
var server = (MinecraftServer) ctx.server();
var level = server.overworld();
var colony = Colonies.of(server, UUID.fromString(args.string("colony"))).orElseThrow();
var facilities = DefinedFacilities.of(colony).orElseThrow();
int x = (int) args.integer("x"), y = (int) args.integer("y"), z = (int) args.integer("z");
var lever = new BlockPos(x, y, z);
String name = "Gearbox";
`;

// A lever facility whose card has two choices: one only saves a parameter, the other also runs `shift`, a routine
// that throws the lever for the high gear.
const SET_UP_GEARBOX = defineTask<Vec & { colony: string }, boolean>({
  name: "folkways.rts-facility-choice.set-up", side: "server",
  source: javaTask({
    imports: [...SERVER_IMPORTS, "net.minecraft.resources.ResourceLocation",
      "io.github.izakyl.folkways.core.facility.api.Routine", "io.github.izakyl.folkways.core.facility.api.Routines",
      "io.github.izakyl.folkways.core.facility.api.control.Value", "io.github.izakyl.folkways.core.plan.api.Refinement",
      "net.minecraft.world.level.block.state.properties.AttachFace"],
    body: SCENE + `
level.setBlockAndUpdate(lever, Blocks.LEVER.defaultBlockState().setValue(LeverBlock.FACE, AttachFace.FLOOR));
var shift = ResourceLocation.parse("folkways_core:e2e_choice_shift");
Routines.register(new Routine() {
  public ResourceLocation id() { return shift; }

  public Optional<Refinement.Change> expand(Call call) {
    if (!(call.arguments().get("gear") instanceof Value.Text gear)) {
      return Optional.empty();
    }
    return Routines.of(Routines.WRITE_ALL).orElseThrow().expand(new Call(call.id(), call.owner(), call.facility(),
      call.site(), call.parameters(), Map.of("lever", Value.of(gear.value().equals("high"))), call.readings(),
      call.rig()));
  }
});
var data = Map.of(
  "name", name,
  "extent", Map.of("elements", List.of(Map.of("space", Map.of("dimension", level.dimension().location().toString()),
    "min", List.of(x, y, z), "max", List.of(x, y, z)))),
  "controls", Map.of("lever", Map.of("part", "lever", "offset", List.of(0, 0, 0))),
  "parameters", Map.of("gear", "low"),
  "routines", Map.of("shift", Map.of("use", shift.toString())),
  "triggers", Map.of("shift", Map.of("kind", "manual", "routine", "shift")),
  "ui", Map.of("card", List.of(
    Map.of("choice", "shell", "label", "Shell", "options", List.of("solid", "he", "shrapnel"),
      "labels", List.of("Solid", "Explosive", "Shrapnel"),
      "icons", List.of("minecraft:iron_ingot", "minecraft:tnt", "minecraft:flint"),
      "default", "he", "enabled_when", Map.of("lever", false)),
    Map.of("choice", "gear", "label", "Gear", "icon", "minecraft:repeater", "options", List.of("low", "high"),
      "labels", List.of("Low", "High"), "run", "shift"),
    Map.of("readout", "lever", "as", "light", "label", "High gear"))));
var problems = facilities.apply(facilities.preview(name, Optional.of(data)));
if (!problems.isEmpty()) {
  throw new IllegalStateException(problems.toString());
}
return true;`,
  }),
});

const READ_GEARBOX = defineTask<Vec & { colony: string }, { shell: string; gear: string; lever: boolean }>({
  name: "folkways.rts-facility-choice.read", side: "server",
  source: javaTask({ imports: SERVER_IMPORTS, body: SCENE + `
var view = facilities.read(name).orElseThrow();
var parameters = (Map<?, ?>) view.data().get("parameters");
return Map.of("shell", parameters.get("shell"), "gear", parameters.get("gear"),
  "lever", level.getBlockState(lever).getValue(LeverBlock.POWERED));
` }),
});
