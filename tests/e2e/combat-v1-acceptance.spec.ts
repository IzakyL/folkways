import { expect, test, type TestInfo } from "@playwright/test";
import { defineTask, input, javaTask, media, screen, tick, world, type MinecraftClient } from "@izakyl/blockwright-minecraft";
import { clickElement, panelElements, scrollElementIntoView, waitForElement } from "../ldlib2";
import { foundColonyFast, openColonyPanelViaBook, waitForGroundedPlayer, type Vec } from "../shared/colony/colony-founding";
import { setVisualLanguage } from "../shared/capture/ponder";
import { frames } from "../shared/harness/bw-helpers";
import { launchPair, type E2EPair } from "../shared/harness/pair";
import { newRunId } from "../shared/harness/run-id";
import { clickTab } from "../shared/ui/colony-pages";
import { mustCommand } from "../shared/ui/world-ui";

type Scene = { colony: string; player: string; resident: string; victim: string; enemy: string; origin: Vec; source: Vec; standBlock: Vec };
type Person = { id: string; health: number; x: number; z: number; armor: string[]; bow: number; arrows: number; personal: boolean; doing: string };
type Reading = { people: Person[]; totals: number[]; stock: number[]; enabled: boolean; iron: boolean; weapon: string; ammo: number; saved: boolean;
  workTicks: number; releases: number; atWork: boolean; reserved: boolean; command: string; session: boolean; enemyHealth: number; helperHit: boolean; victimHealth: number; debug: string };
type Evidence = Array<{ step: string; value: unknown }>;
type Pixel = { x: number; y: number; width: number; height: number };
type InputState = { combat: boolean; roster: number; selected: string[]; routeRequest: number; accepted: boolean; intent: string; pending: boolean;
  buttons: Array<{ kind: string; x: number; y: number; width: number; height: number }> };

// Server tasks only arrange finite initial stock/work/hostile damage or observe state.
// Configuration, MOVE and RELEASE under test are actual book/overhead pointer inputs.
test("combat defaults clicked in the book equip current and new residents from finite warehouse stock", async ({}, testInfo) => {
  test.setTimeout(420_000);
  await withScene(testInfo, "defaults", async (pair, scene, evidence) => {
    await openCombat(pair, scene);
    const controls = ["defaults_iron", "defaults_weapon_bow", "defaults_ammo_16", "defaults_enabled"];
    for (const control of controls) {
      await clickElement(pair.client, { id: `folkways.combat.${control}` }, { scrollIntoView: true });
      await sync(pair);
      evidence.push({ step: `real book click ${control}`, value: await pair.server.run(READ, scene) });
    }
    const saved = await pair.server.run(READ, scene);
    expect({ enabled: saved.enabled, iron: saved.iron, weapon: saved.weapon, ammo: saved.ammo, saved: saved.saved })
      .toEqual({ enabled: true, iron: true, weapon: "bow", ammo: 16, saved: true });
    expect(saved.totals, "two physical armor sets, two bows and 32 arrows exist before and after UI configuration").toEqual([2, 2, 2, 2, 2, 32]);
    await screen.dismiss(pair.client);
    const first = await wait(pair, scene, evidence, "current resident physically collected its full real outfit", value => outfitted(value.people[0]), 1000);
    expect(first.stock).toEqual([1, 1, 1, 1, 1, 16]);
    expect(first.totals).toEqual([2, 2, 2, 2, 2, 32]);
    await assertClientPerson(pair.client, scene.resident, first.people[0]);
    await capture(pair.client, testInfo, "combat-defaults-current-resident.png");
    const admitted = await pair.server.run(ADMIT, scene);
    evidence.push({ step: "one new actual colony resident admitted after policy was saved", value: admitted });
    const both = await wait(pair, scene, evidence, "new resident inherited the saved colony policy through real warehouse trips", value => value.people.length === 2 && value.people.every(outfitted), 1200);
    expect(both.stock, "the registered finite source is empty only after both people own their individual pieces").toEqual([0, 0, 0, 0, 0, 0]);
    expect(both.totals, "warehouse, actual packs and native armor retain exactly the original stock").toEqual([2, 2, 2, 2, 2, 32]);
    for (const person of both.people) await assertClientPerson(pair.client, person.id, person);
    await openCombat(pair, scene);
    for (const control of controls) expect(await textOf(pair.client, `folkways.combat.${control}`)).toContain("[x]");
    await capture(pair.client, testInfo, "combat-defaults-saved-and-reopened.png");
  });
});

test("a personally armed neighbour supports real hostile damage then obeys pointer MOVE and resumes its original tool work after RELEASE", async ({}, testInfo) => {
  test.setTimeout(420_000);
  await withScene(testInfo, "support", async (pair, scene, evidence) => {
    await screen.dismiss(pair.client);
    const worked = await wait(pair, scene, evidence, "the submitted axe work really runs at its original site", value => value.workTicks >= 8 && value.atWork, 450);
    expect(worked.totals[4]).toBe(0);
    const damaged = await pair.server.run(HURT, scene);
    expect(damaged.after, "the real enemy's native attack actually injures the colleague").toBeLessThan(damaged.before);
    evidence.push({ step: "actual hostile mob attack, with no support-ticket injection", value: damaged });
    const hit = await wait(pair, scene, evidence, "the neighbouring helper physically travels and lands its own native hit", value => value.helperHit && value.enemyHealth < 60 && value.releases > 0, 500);
    expect(Math.hypot(hit.people[0].x - (scene.origin.x + 2.5), hit.people[0].z - (scene.origin.z + 4.5))).toBeGreaterThan(2);
    await assertClientPerson(pair.client, scene.victim, hit.people.find(person => person.id === scene.victim)!);
    await enterCombatTool(pair);
    const entity = await pair.client.run(PROJECT, { ...scene.origin, entity: scene.resident });
    visible(entity);
    await input.click(pair.client, entity, { button: "left" });
    await expect.poll(async () => { await sync(pair); return (await pair.client.run(INPUT_STATE)).selected; }, { timeout: 10_000 }).toEqual([scene.resident]);
    const destination = { x: scene.origin.x + 2, y: scene.origin.y, z: scene.origin.z + 14 };
    const ground = await pair.client.run(PROJECT, { ...destination, entity: "" });
    visible(ground);
    const request = (await pair.client.run(INPUT_STATE)).routeRequest;
    await input.click(pair.client, ground, { button: "right" });
    await expect.poll(async () => {
      await sync(pair); const ack = await pair.client.run(INPUT_STATE);
      evidence.push({ step: "real right-click MOVE acknowledgement", value: ack });
      return ack.routeRequest > request && ack.accepted && ack.intent === "MOVE" && !ack.pending;
    }, { timeout: 10_000 }).toBe(true);
    const replaced = await pair.server.run(READ, scene);
    const moved = await wait(pair, scene, evidence, "the replacement MOVE reaches its real clicked destination", value =>
      Math.hypot(value.people[0].x - destination.x - .5, value.people[0].z - destination.z - .5) < 1.5 && !value.session, 600);
    expect(moved.enemyHealth, "the canceled support lease does not land another hit after MOVE acknowledgement").toBe(replaced.enemyHealth);
    expect(moved.reserved, "completing MOVE keeps real player control until RELEASE").toBe(true);
    await capture(pair.client, testInfo, "combat-support-move-reached.png");
    await pair.server.run(REMOVE_ENEMY, scene);
    const beforeRelease = moved.workTicks;
    const release = (await pair.client.run(INPUT_STATE)).buttons.find(button => button.kind === "RELEASE");
    expect(release, "the combat cards expose a real RELEASE hit rectangle").toBeTruthy();
    await input.click(pair.client, { x: release!.x + release!.width / 2, y: release!.y + release!.height / 2 }, { button: "left" });
    const resumed = await wait(pair, scene, evidence, "after real RELEASE the same axe job resumes at its original worksite", value =>
      !value.reserved && value.workTicks >= beforeRelease + 8 && value.atWork, 650);
    expect(resumed.command).toBe("NONE");
    expect(resumed.totals.slice(0, 5)).toEqual([1, 0, 0, 0, 0]);
    expect(await pair.server.run(TOOL_COUNTS, scene), "the actual sword and work axe both return uniquely after combat and movement").toEqual({ swords: 1, axes: 1 });
    await assertClientPerson(pair.client, scene.resident, resumed.people[0]);
    await screen.dismiss(pair.client);
    await openColonyPanelViaBook(pair.server, pair.client, scene.player, scene.standBlock);
    await clickTab(pair.client, "folkways.tab.residents");
    await clickElement(pair.client, { id: `folkways.resident.name.${scene.resident.slice(0, 8)}` }, { scrollIntoView: true });
    await waitForElement(pair.client, { id: "folkways.window.resident" });
    await clickElement(pair.client, { id: "folkways.equipment.open" }, { scrollIntoView: true });
    expect(await textOf(pair.client, "folkways.equipment.worn.mainhand")).toContain("Acceptance personal sword");
    await capture(pair.client, testInfo, "combat-support-released-equipment-synced.png");
  });
});

function outfitted(person: Person | undefined) {
  return !!person && person.armor.join(",") === "minecraft:iron_helmet,minecraft:iron_chestplate,minecraft:iron_leggings,minecraft:iron_boots"
    && person.bow === 1 && person.arrows === 16 && person.personal;
}
async function withScene(info: TestInfo, mode: "defaults" | "support", act: (pair: E2EPair, scene: Scene, evidence: Evidence) => Promise<void>) {
  let pair: E2EPair | undefined, scene: Scene | undefined;
  const evidence: Evidence = [];
  try {
    const run = newRunId();
    pair = await launchPair({ config: "blockwright.toml#combat", server: { instance: `combat-v1-${mode}-server-${run}` },
      client: { instance: `combat-v1-${mode}-client-${run}` }, snapshot: { entityTypes: ["folkways:resident", "minecraft:zombie"] } });
    await setVisualLanguage(pair.client, "en_us");
    await pair.client.run(GUI_SCALE);
    const player = await waitForGroundedPlayer(pair.server);
    const origin = { x: Math.round(player.pos.x) + 4, y: Math.round(player.pos.y), z: Math.round(player.pos.z) + 4 };
    await mustCommand(pair.server, `gamemode survival ${player.name}`);
    await mustCommand(pair.server, "difficulty normal");
    await mustCommand(pair.server, "gamerule doMobSpawning false");
    await mustCommand(pair.server, "gamerule doDaylightCycle false");
    await mustCommand(pair.server, "time set noon");
    await mustCommand(pair.server, `fill ${origin.x - 20} ${origin.y - 1} ${origin.z - 20} ${origin.x + 20} ${origin.y - 1} ${origin.z + 20} minecraft:stone`);
    const cleared = await world.command(pair.server,
      `fill ${origin.x - 20} ${origin.y} ${origin.z - 20} ${origin.x + 20} ${origin.y + 6} ${origin.z + 20} minecraft:air`,
      { allowFailure: true });
    if (!cleared.success && !(cleared.output.length === 1 && cleared.output[0].trim() === "No blocks were filled")) {
      throw new Error(`fixture air clearing failed: ${JSON.stringify(cleared)}`);
    }
    evidence.push({ step: "air clear succeeded or the bounded fixture was already entirely air", value: cleared });
    const founded = await foundColonyFast(pair.server, pair.client, player.name, { origin, residentCount: 1 });
    const setup = await pair.server.run(SETUP, { colony: String(founded.evidence.colonyId), player: player.name, origin, mode });
    scene = { ...setup, origin, standBlock: founded.standBlock };
    evidence.push({ step: "finite server fixture before all user actions", value: await pair.server.run(READ, scene) });
    await act(pair, scene, evidence);
  } catch (error) {
    pair?.failing(error);
    if (pair) {
      try { evidence.push({ step: "failure UI", value: await panelElements(pair.client) }); } catch { /* Primary failure wins. */ }
      try { if (scene) evidence.push({ step: "failure server state", value: await pair.server.run(READ, scene) }); } catch { /* Preserve teardown. */ }
      try { await capture(pair.client, info, "combat-v1-failure.png"); } catch { /* Client may have closed. */ }
    }
    throw error;
  } finally {
    if (pair && scene) { try { await pair.server.run(CLEANUP, scene); } catch { /* Preserve primary evidence. */ } }
    await info.attach(`combat-v1-${mode}-evidence.json`, { body: JSON.stringify(evidence, null, 2), contentType: "application/json" });
    await pair?.teardown(info);
  }
}
async function sync(pair: E2EPair) { await tick.sprint(pair.server, 4); await frames(pair.client, 3); }
async function wait(pair: E2EPair, scene: Scene, evidence: Evidence, step: string, done: (reading: Reading) => boolean, budget: number) {
  for (let elapsed = 0; elapsed < budget; elapsed += 8) {
    await tick.sprint(pair.server, 8); await frames(pair.client, 2);
    const reading = await pair.server.run(READ, scene); evidence.push({ step, value: reading });
    if (done(reading)) return reading;
  }
  throw new Error(`${step}: ${JSON.stringify(evidence.at(-1))}`);
}
async function openCombat(pair: E2EPair, scene: Scene) {
  await openColonyPanelViaBook(pair.server, pair.client, scene.player, scene.standBlock);
  await clickTab(pair.client, "folkways.page.combat");
  await scrollElementIntoView(pair.client, { id: "folkways.combat.defaults_enabled" });
  await waitForElement(pair.client, { id: "folkways.combat.defaults_enabled" });
}
async function textOf(client: MinecraftClient, id: string) {
  await scrollElementIntoView(client, { id }); const elements = await panelElements(client);
  const root = elements.find(element => element.id === id); if (!root) throw new Error(`missing visible ${id}`);
  const words = [root.text ?? ""];
  for (let at = root.index + 1; at < elements.length && elements[at].depth > root.depth; at++) if (elements[at].displayed && elements[at].text) words.push(elements[at].text!);
  return words.join(" ");
}
async function capture(client: MinecraftClient, info: TestInfo, name: string) {
  const path = info.outputPath(name); await media.screenshot(client, { path }); await info.attach(name, { path, contentType: "image/png" });
}
async function assertClientPerson(client: MinecraftClient, id: string, expected: Person) {
  await expect.poll(async () => { await frames(client, 3); return client.run(CLIENT_PERSON, { id }); }, { timeout: 10_000 })
    .toEqual({ present: true, health: expected.health, armor: expected.armor });
}
async function enterCombatTool(pair: E2EPair) {
  await screen.dismiss(pair.client); await frames(pair.client, 3);
  await input.key(pair.client, { keyCode: 86 });
  await screen.waitFor(pair.client, "OverheadScreen", { timeoutMs: 8_000 });
  const tab = (await screen.widgets(pair.client)).find(widget => /^(Combat|战斗|戰鬥)$/.test(widget.label?.trim() ?? ""));
  expect(tab, "the combat profile must expose the actual combat tool").toBeTruthy();
  await screen.clickWidget(pair.client, { label: tab!.label });
  await expect.poll(async () => { await sync(pair); const state = await pair.client.run(INPUT_STATE); return state.combat && state.roster >= 2; }, { timeout: 10_000 }).toBe(true);
}
function visible(point: Pixel) { expect(point.x).toBeGreaterThan(5); expect(point.x).toBeLessThan(point.width - 5); expect(point.y).toBeGreaterThan(40); expect(point.y).toBeLessThan(point.height - 65); }

const IMPORTS = ["net.minecraft.server.MinecraftServer", "net.minecraft.core.BlockPos", "net.minecraft.resources.ResourceLocation", "net.minecraft.nbt.CompoundTag",
  "net.minecraft.world.item.Items", "net.minecraft.world.item.ItemStack", "net.minecraft.world.entity.EquipmentSlot", "net.minecraft.world.entity.EntityType", "net.minecraft.world.entity.ai.attributes.Attributes",
  "net.minecraft.core.component.DataComponents", "net.minecraft.network.chat.Component", "net.minecraft.tags.ItemTags", "net.minecraft.world.phys.Vec3",
  "io.github.izakyl.folkways.core.api.colony.Colonies", "io.github.izakyl.folkways.core.api.colony.Held", "io.github.izakyl.folkways.core.api.terms.WorldPos", "io.github.izakyl.folkways.core.api.terms.WorldSpaces", "io.github.izakyl.folkways.core.api.terms.Containers", "io.github.izakyl.folkways.core.api.terms.Reach",
  "io.github.izakyl.folkways.core.api.vocation.Vocations", "io.github.izakyl.folkways.core.engine.labor.LaborSnapshot", "io.github.izakyl.folkways.core.api.resident.body.PersonalItems", "io.github.izakyl.folkways.core.api.resident.body.Equipment", "io.github.izakyl.folkways.core.api.resident.body.TaskTools",
  "io.github.izakyl.folkways.core.api.work.Node", "io.github.izakyl.folkways.core.api.work.NodeSpec", "io.github.izakyl.folkways.core.api.work.Workload", "io.github.izakyl.folkways.core.api.work.WorkSite", "io.github.izakyl.folkways.core.api.work.Worker", "io.github.izakyl.folkways.core.api.work.Stances", "io.github.izakyl.folkways.core.api.work.Outcome", "io.github.izakyl.folkways.core.api.work.Ending", "io.github.izakyl.folkways.core.api.work.ToolUse", "io.github.izakyl.folkways.core.api.work.ToolNeed", "io.github.izakyl.folkways.core.api.work.Grown",
  "io.github.izakyl.folkways.plugins.person.PersonBody", "io.github.izakyl.folkways.plugins.person.PersonContent", "io.github.izakyl.folkways.plugins.person.ResidentEntity",
  "io.github.izakyl.folkways.plugins.person.equipment.EquipmentPlans", "io.github.izakyl.folkways.plugins.person.equipment.ResidentEquipment",
  "io.github.izakyl.folkways.plugins.combat.command.ResidentCommands", "io.github.izakyl.folkways.plugins.combat.control.CombatSelectionOwnership"];
const SETUP = defineTask<{ colony: string; player: string; origin: Vec; mode: string }, Omit<Scene, "origin" | "standBlock">>({
  name: "folkways.combat-v1.fixture", side: "server", source: javaTask({ imports: IMPORTS, body: `
var a=Args.of(ctx);var server=(MinecraftServer)ctx.server();var level=server.overworld();var colony=Colonies.of(server,UUID.fromString(a.string("colony"))).orElseThrow();
var origin=a.requireObject("origin");int x=(int)origin.number("x"),y=(int)origin.number("y"),z=(int)origin.number("z");
var helper=colony.residents().stream().map(r->level.getEntity(r.id())).filter(e->e instanceof ResidentEntity).map(e->(ResidentEntity)e).findFirst().orElseThrow();
for(var resident:List.copyOf(colony.residents())){var entity=level.getEntity(resident.id());if(entity!=null&&entity!=helper)entity.discard();}
helper.stopWeaponSession();helper.pack().clearContent();for(var slot:ResidentEquipment.SLOTS){Equipment.lock(helper,slot,false);helper.setItemSlot(slot,ItemStack.EMPTY);}
helper.setNoAi(false);helper.moveTo(x+2.5,y,z+4.5);helper.pack().insert(new ItemStack(Items.BREAD,16));
var owner=ResourceLocation.fromNamespaceAndPath("folkways_acceptance","stock");var source=WorldPos.of(level,new BlockPos(x+13,y,z+4));
level.setBlockAndUpdate(source.block(level),net.minecraft.world.level.block.Blocks.BARREL.defaultBlockState());var stock=Containers.at(level,source.block(level)).orElseThrow();
colony.stores(owner,level.dimension(),List.of(source));String victimId="";
if(a.string("mode").equals("defaults")){
  int cell=0;for(int n=0;n<2;n++)for(var item:List.of(Items.IRON_HELMET,Items.IRON_CHESTPLATE,Items.IRON_LEGGINGS,Items.IRON_BOOTS,Items.BOW))stock.setItem(cell++,new ItemStack(item));stock.setItem(cell,new ItemStack(Items.ARROW,32));
  ResidentCommands.of(colony).hold(helper,WorldSpaces.at(helper));
}else{
  var sword=new ItemStack(Items.IRON_SWORD);sword.set(DataComponents.CUSTOM_NAME,Component.literal("Acceptance personal sword"));PersonalItems.mark(helper,sword,"mainhand");helper.pack().insert(sword);Equipment.lock(helper,EquipmentSlot.MAINHAND,true);
  var helmet=new ItemStack(Items.IRON_HELMET);PersonalItems.mark(helper,helmet,"head");helper.setItemSlot(EquipmentSlot.HEAD,helmet);
  var axe=new ItemStack(Items.IRON_AXE);axe.setDamageValue(7);helper.pack().insert(axe);
  var victim=PersonBody.RESIDENT.get().create(level);victim.moveTo(x+9.5,y,z+4.5);victim.setNoAi(true);if(!level.addFreshEntity(victim))throw new IllegalStateException("support fixture could not add victim to server level");colony.hold(PersonContent.ID,Held.Entity.of(victim)).orElseThrow(()->new IllegalStateException("support fixture could not hold loaded victim "+victim.id()+" in colony "+colony.id()));victim.joinColony(colony.id());victimId=victim.id().toString();
  // The helper can do ordinary axe work, while the inert colleague cannot claim unrelated tool deliveries.
  // Urges (including fear and leisure) remain enabled for both real residents.
  for(var trade:Vocations.all()){
    helper.licences().trade(trade).ifPresent(licence->helper.licences().allow(licence,trade.id().equals(Vocations.HAULING)||trade.id().equals(ResourceLocation.fromNamespaceAndPath("folkways","building"))));
    victim.licences().trade(trade).ifPresent(licence->victim.licences().allow(licence,false));
  }
  UUID task=UUID.randomUUID();helper.getPersistentData().putUUID("acceptance_job",task);helper.getPersistentData().putString("acceptance_job_phase","submitted");var work=ResourceLocation.fromNamespaceAndPath("folkways_acceptance","axe_work");var at=WorldSpaces.at(helper);
  TaskTools.keep(task,helper.id(),item->item.is(ItemTags.AXES));
  Workload.Continuous load=(world,who)->{var data=helper.getPersistentData();data.putInt("acceptance_ticks",data.getInt("acceptance_ticks")+1);return true;};
  var spec=NodeSpec.of(task,work,new WorkSite.AtBlock(at),Stances.at(at),load).tools(new ToolUse(new ToolNeed.Tagged(ItemTags.AXES),0)).doing(work).done();
  var node=new Node(){public NodeSpec spec(){return spec;}public Optional<UUID> worker(){return Optional.of(helper.id());}public Outcome commit(net.minecraft.server.level.ServerLevel world,Worker who){return Outcome.done();}public void changed(net.minecraft.server.level.ServerLevel world,io.github.izakyl.folkways.core.api.work.NodeState phase){helper.getPersistentData().putString("acceptance_job_phase",phase.toString());}public void ended(net.minecraft.server.level.ServerLevel world,Ending how){helper.getPersistentData().putString("acceptance_job_ended",how.toString());}public io.github.izakyl.folkways.core.api.work.Recourse failing(net.minecraft.server.level.ServerLevel world,io.github.izakyl.folkways.core.api.terms.RefusalKind why){helper.getPersistentData().putString("acceptance_job_refusal",why.toString());return io.github.izakyl.folkways.core.api.work.Recourse.FAIL;}public void released(net.minecraft.server.level.ServerLevel world,Worker who,Ending how){var data=helper.getPersistentData();data.putInt("acceptance_releases",data.getInt("acceptance_releases")+1);}};
  colony.submit(work,level.dimension(),Grown.of(node),(id,how)->helper.getPersistentData().putString("acceptance_job_callback",id+":"+how));
}
return Map.of("colony",colony.id().toString(),"player",a.string("player"),"resident",helper.id().toString(),"victim",victimId,"enemy","","source",Map.of("x",x+13,"y",y,"z",z+4));` }) });

const ADMIT = defineTask<Scene, { resident: string }>({ name: "folkways.combat-v1.admit-real-resident", side: "server", source: javaTask({ imports: IMPORTS, body: `
var a=Args.of(ctx);var server=(MinecraftServer)ctx.server();var level=server.overworld();var colony=Colonies.of(server,UUID.fromString(a.string("colony"))).orElseThrow();var helper=(ResidentEntity)level.getEntity(UUID.fromString(a.string("resident")));
var newcomer=PersonBody.RESIDENT.get().create(level);newcomer.moveTo(helper.getX()+1,helper.getY(),helper.getZ()+2);newcomer.pack().insert(new ItemStack(Items.BREAD,16));if(!level.addFreshEntity(newcomer))throw new IllegalStateException("defaults fixture could not add newcomer to server level");colony.hold(PersonContent.ID,Held.Entity.of(newcomer)).orElseThrow(()->new IllegalStateException("defaults fixture could not hold loaded newcomer "+newcomer.id()+" in colony "+colony.id()));newcomer.joinColony(colony.id());ResidentCommands.of(colony).hold(newcomer,WorldSpaces.at(newcomer));return Map.of("resident",newcomer.id().toString());` }) });

const HURT = defineTask<Scene, { before: number; after: number }>({ name: "folkways.combat-v1.real-hostile-native-damage", side: "server", source: javaTask({ imports: IMPORTS, body: `
var a=Args.of(ctx);var server=(MinecraftServer)ctx.server();var level=server.overworld();var victim=(ResidentEntity)level.getEntity(UUID.fromString(a.string("victim")));var helper=(ResidentEntity)level.getEntity(UUID.fromString(a.string("resident")));
var enemy=EntityType.ZOMBIE.create(level);enemy.moveTo(victim.getX()+1,victim.getY(),victim.getZ());enemy.setNoAi(true);enemy.setItemSlot(EquipmentSlot.HEAD,new ItemStack(Items.CARVED_PUMPKIN));enemy.getAttribute(Attributes.MAX_HEALTH).setBaseValue(60);enemy.setHealth(60);level.addFreshEntity(enemy);enemy.setTarget(victim);helper.getPersistentData().putUUID("acceptance_enemy",enemy.getUUID());float before=victim.getHealth();if(!enemy.doHurtTarget(victim))throw new IllegalStateException("fixture enemy failed its actual native attack");return Map.of("before",before,"after",victim.getHealth());` }) });

const READ = defineTask<Scene, Reading>({ name: "folkways.combat-v1.observe-physical-state", side: "server", source: javaTask({ imports: IMPORTS, body: `
var a=Args.of(ctx);var server=(MinecraftServer)ctx.server();var level=server.overworld();var colony=Colonies.of(server,UUID.fromString(a.string("colony"))).orElseThrow();var helper=(ResidentEntity)level.getEntity(UUID.fromString(a.string("resident")));
var pos=a.requireObject("source");var stock=Containers.at(level,new BlockPos((int)pos.number("x"),(int)pos.number("y"),(int)pos.number("z"))).orElseThrow();
var items=List.of(Items.IRON_HELMET,Items.IRON_CHESTPLATE,Items.IRON_LEGGINGS,Items.IRON_BOOTS,Items.BOW,Items.ARROW);var totals=new int[6];var stockCounts=new int[6];var people=new ArrayList<Map<String,Object>>();var all=new ArrayList<ItemStack>();for(int cell=0;cell<stock.getContainerSize();cell++){var stack=stock.getItem(cell);all.add(stack);for(int n=0;n<items.size();n++)if(stack.is(items.get(n)))stockCounts[n]+=stack.getCount();}
var residents=colony.residents().stream().map(r->level.getEntity(r.id())).filter(e->e instanceof ResidentEntity).map(e->(ResidentEntity)e).sorted(Comparator.comparingInt(body->body==helper?0:1)).toList();
for(var body:residents){for(int cell=0;cell<body.pack().getContainerSize();cell++)all.add(body.pack().getItem(cell));for(var slot:ResidentEquipment.SLOTS)if(slot!=EquipmentSlot.MAINHAND||body.hasWeaponSession())all.add(body.getItemBySlot(slot));var armor=new ArrayList<String>();boolean personal=true;for(var slot:List.of(EquipmentSlot.HEAD,EquipmentSlot.CHEST,EquipmentSlot.LEGS,EquipmentSlot.FEET)){var actual=body.getItemBySlot(slot);armor.add(net.minecraft.core.registries.BuiltInRegistries.ITEM.getKey(actual.getItem()).toString());personal&=PersonalItems.belongsTo(body,actual);}people.add(Map.of("id",body.id().toString(),"health",body.getHealth(),"x",body.getX(),"z",body.getZ(),"armor",armor,"bow",body.pack().count(Items.BOW),"arrows",body.pack().count(Items.ARROW),"personal",personal,"doing",body.doing().toString()));}
for(var actual:all)for(int n=0;n<items.size();n++)if(actual.is(items.get(n)))totals[n]+=actual.getCount();
var config=EquipmentPlans.of(colony).defaultSnapshot();var saved=colony.kept(ResourceLocation.fromNamespaceAndPath("folkways","equipment_defaults")).getCompound("configuration");var enemy=helper.getPersistentData().hasUUID("acceptance_enemy")?(net.minecraft.world.entity.Mob)level.getEntity(helper.getPersistentData().getUUID("acceptance_enemy")):null;var order=ResidentCommands.of(colony).current(helper.id());
var result=new LinkedHashMap<String,Object>();result.put("people",people);result.put("totals",Arrays.stream(totals).boxed().toList());result.put("stock",Arrays.stream(stockCounts).boxed().toList());result.put("enabled",config.getBoolean("enabled"));result.put("iron",List.of("head","chest","legs","feet").stream().allMatch(config::getBoolean));result.put("weapon",config.getString("weapon"));result.put("ammo",config.getInt("ammo"));result.put("saved",saved.getBoolean("enabled")&&saved.getInt("ammo")==config.getInt("ammo")&&saved.getString("weapon").equals(config.getString("weapon")));
result.put("workTicks",helper.getPersistentData().getInt("acceptance_ticks"));result.put("releases",helper.getPersistentData().getInt("acceptance_releases"));var origin=a.requireObject("origin");result.put("atWork",Math.abs(helper.getX()-origin.number("x")-2.5)<1&&Math.abs(helper.getZ()-origin.number("z")-4.5)<1);result.put("reserved",CombatSelectionOwnership.of(colony).reserved(helper.id()));result.put("command",order.map(o->o.mode().name()).orElse("NONE"));result.put("session",helper.hasWeaponSession());result.put("enemyHealth",enemy==null?-1:enemy.getHealth());result.put("helperHit",enemy!=null&&enemy.getLastDamageSource()!=null&&enemy.getLastDamageSource().getEntity()==helper);result.put("victimHealth",a.string("victim").isEmpty()?20:((ResidentEntity)level.getEntity(UUID.fromString(a.string("victim")))).getHealth());result.put("debug","defaults="+config+",job="+helper.getPersistentData().getString("acceptance_job_phase")+"/"+helper.getPersistentData().getString("acceptance_job_ended")+"/refusal="+helper.getPersistentData().getString("acceptance_job_refusal")+"/callback="+helper.getPersistentData().getString("acceptance_job_callback")+",labor="+LaborSnapshot.of(level,colony.id())+",stalls="+colony.stallsIn(level.dimension())+",packs="+residents.stream().map(body->body.id()+":"+body.pack().contents()+":"+body.doing()).toList());return result;` }) });

const TOOL_COUNTS = defineTask<Scene, { swords: number; axes: number }>({ name: "folkways.combat-v1.actual-tool-conservation", side: "server", source: javaTask({ imports: IMPORTS, body: `var a=Args.of(ctx);var level=((MinecraftServer)ctx.server()).overworld();var body=(ResidentEntity)level.getEntity(UUID.fromString(a.string("resident")));return Map.of("swords",body.pack().count(Items.IRON_SWORD)+(body.hasWeaponSession()&&body.getMainHandItem().is(Items.IRON_SWORD)?1:0),"axes",body.pack().count(Items.IRON_AXE));` }) });
const REMOVE_ENEMY = defineTask<Scene, boolean>({ name: "folkways.combat-v1.remove-finished-hostile", side: "server", source: javaTask({ imports: IMPORTS, body: `var a=Args.of(ctx);var level=((MinecraftServer)ctx.server()).overworld();var body=(ResidentEntity)level.getEntity(UUID.fromString(a.string("resident")));if(body.getPersistentData().hasUUID("acceptance_enemy")){var enemy=level.getEntity(body.getPersistentData().getUUID("acceptance_enemy"));if(enemy!=null)enemy.discard();}return true;` }) });
const CLEANUP = defineTask<Scene, boolean>({ name: "folkways.combat-v1.cleanup", side: "server", source: javaTask({ imports: IMPORTS, body: `var a=Args.of(ctx);var server=(MinecraftServer)ctx.server();var level=server.overworld();var colony=Colonies.of(server,UUID.fromString(a.string("colony"))).orElse(null);if(colony!=null){for(var member:List.copyOf(colony.residents())){var entity=level.getEntity(member.id());if(entity instanceof ResidentEntity body){if(body.getPersistentData().hasUUID("acceptance_enemy")){var enemy=level.getEntity(body.getPersistentData().getUUID("acceptance_enemy"));if(enemy!=null)enemy.discard();}body.stopWeaponSession();TaskTools.forget(body.id());body.discard();}}colony.raze();}return true;` }) });
const GUI_SCALE = defineTask<void, boolean>({ name: "folkways.combat-v1.gui-scale", side: "client", source: javaTask({ imports: ["net.minecraft.client.Minecraft"], body: `var mc=Minecraft.getInstance();mc.options.guiScale().set(1);mc.resizeDisplay();return true;` }) });
const CLIENT_PERSON = defineTask<{ id: string }, { present: boolean; health: number; armor: string[] }>({ name: "folkways.combat-v1.observe-client-health-and-armor", side: "client", source: javaTask({ imports: ["net.minecraft.client.Minecraft", "net.minecraft.world.entity.EquipmentSlot", "net.minecraft.core.registries.BuiltInRegistries"], body: `var mc=Minecraft.getInstance();for(var entity:mc.level.entitiesForRendering())if(entity.getUUID().toString().equals(Args.of(ctx).string("id"))&&entity instanceof net.minecraft.world.entity.LivingEntity body)return Map.of("present",true,"health",body.getHealth(),"armor",List.of(EquipmentSlot.HEAD,EquipmentSlot.CHEST,EquipmentSlot.LEGS,EquipmentSlot.FEET).stream().map(slot->BuiltInRegistries.ITEM.getKey(body.getItemBySlot(slot).getItem()).toString()).toList());return Map.of("present",false,"health",-1,"armor",List.of());` }) });
const INPUT_STATE = defineTask<void, InputState>({ name: "folkways.combat-v1.observe-input-ack-and-hit-rectangles", side: "client", source: javaTask({ imports: ["io.github.izakyl.folkways.front.api.ui.OverheadTools", "io.github.izakyl.folkways.plugins.combat.net.CombatPackets"], body: `
var result=new LinkedHashMap<String,Object>();var handler=OverheadTools.selectedHandler().orElse(null);boolean combat=handler!=null&&handler.getClass().getName().endsWith(".CombatController");result.put("combat",combat);result.put("roster",CombatPackets.roster().size());result.put("selected",List.of());result.put("buttons",List.of());var route=CombatPackets.route().orElse(null);result.put("routeRequest",route==null?0L:route.request());result.put("accepted",route!=null&&route.accepted());result.put("intent",route==null?"":route.intent().name());result.put("pending",CombatPackets.routePending());
if(combat){var selected=handler.getClass().getDeclaredField("selected");selected.setAccessible(true);result.put("selected",((Set<?>)selected.get(handler)).stream().map(Object::toString).toList());var cardsField=handler.getClass().getDeclaredField("cards");cardsField.setAccessible(true);var cards=cardsField.get(handler);var hitsField=cards.getClass().getDeclaredField("hits");hitsField.setAccessible(true);var buttons=new ArrayList<Map<String,Object>>();for(var hit:(List<?>)hitsField.get(cards)){var method=hit.getClass().getDeclaredMethod("action");method.setAccessible(true);var action=method.invoke(hit);var kind=action.getClass().getDeclaredMethod("kind");kind.setAccessible(true);var row=new LinkedHashMap<String,Object>();row.put("kind",kind.invoke(action).toString());for(var key:List.of("x","y","width","height")){var accessor=hit.getClass().getDeclaredMethod(key);accessor.setAccessible(true);row.put(key,accessor.invoke(hit));}buttons.add(row);}result.put("buttons",buttons);}return result;` }) });
const PROJECT = defineTask<Vec & { entity: string }, Pixel>({
  name: "folkways.combat-v1.observe-world-projection", side: "client",
  source: javaTask({
    imports: ["java.util.Map", "java.util.Optional", "java.util.UUID", "net.minecraft.client.Minecraft", "net.minecraft.world.phys.Vec3", "net.minecraft.world.phys.AABB"],
    body: `
var a=Args.of(ctx);var mc=Minecraft.getInstance();var type=Class.forName("io.github.izakyl.folkways.war.client.CombatProjection");
var constructor=type.getDeclaredConstructor(Minecraft.class);constructor.setAccessible(true);var projection=constructor.newInstance(mc);
double left,top,right,bottom;
if(!a.string("entity").isEmpty()){
  var id=UUID.fromString(a.string("entity"));net.minecraft.world.entity.Entity found=null;
  for(var entity:mc.level.entitiesForRendering()){if(entity.getUUID().equals(id)){found=entity;break;}}
  if(found==null)throw new IllegalStateException("fixture resident is not client-tracked: "+id);
  var bounds=type.getDeclaredMethod("bounds",AABB.class);bounds.setAccessible(true);var rect=((Optional<?>)bounds.invoke(projection,found.getBoundingBox())).orElseThrow();
  var l=rect.getClass().getDeclaredMethod("left");l.setAccessible(true);left=((Number)l.invoke(rect)).doubleValue();
  var t=rect.getClass().getDeclaredMethod("top");t.setAccessible(true);top=((Number)t.invoke(rect)).doubleValue();
  var r=rect.getClass().getDeclaredMethod("right");r.setAccessible(true);right=((Number)r.invoke(rect)).doubleValue();
  var b=rect.getClass().getDeclaredMethod("bottom");b.setAccessible(true);bottom=((Number)b.invoke(rect)).doubleValue();
}else{
  var point=type.getDeclaredMethod("point",Vec3.class);point.setAccessible(true);
  var pixel=((Optional<?>)point.invoke(projection,new Vec3(a.integer("x")+0.5,a.integer("y")+0.12,a.integer("z")+0.5))).orElseThrow();
  var x=pixel.getClass().getDeclaredMethod("x");x.setAccessible(true);left=right=((Number)x.invoke(pixel)).doubleValue();
  var y=pixel.getClass().getDeclaredMethod("y");y.setAccessible(true);top=bottom=((Number)y.invoke(pixel)).doubleValue();
}
return Map.of("x",(left+right)/2,"y",(top+bottom)/2,"left",left,"top",top,"right",right,"bottom",bottom,
  "width",mc.getWindow().getGuiScaledWidth(),"height",mc.getWindow().getGuiScaledHeight());`,
  }),
});
