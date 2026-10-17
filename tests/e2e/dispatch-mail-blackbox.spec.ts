import { expect, test } from "@playwright/test";
import { defineTask, javaTask, screen, tick, world } from "@izakyl/blockwright-minecraft";
import { writeFileSync } from "node:fs";
import path from "node:path";
import { foundColonyFast, setBookGesture, waitForGroundedPlayer, type Vec } from "../shared/colony/colony-founding";
import { runColonyTask } from "../shared/colony/colony-tasks";
import { deliverOrderViaSitePanel, optInContainerViaUI } from "../shared/colony/membership-optin";
import { buildRailLine } from "../shared/rail/rail-fixture";
import { launchPair, type E2EPair } from "../shared/harness/pair";
import { GROUND } from "../shared/harness/stage";
import { REPO_ROOT, ensureOut, reportPath, tracePath } from "../shared/harness/out-paths";
import { seededServer } from "../shared/harness/mod-settings";
import { BUDGET } from "../shared/harness/budgets";
import { commandPos, tryCommand } from "../shared/harness/bw-helpers";
import { newRunId } from "../shared/harness/run-id";

const CONFIG_PATH = "blockwright.toml";
const REPORT_PATH = reportPath("e2e", "dispatch-mail-report");

// Far enough down the line that the parcel plainly goes by train, near enough that a round trip is quick.
const RAIL_LINE = 64;
const RESIDENTS = 4;
const ITEM = "minecraft:cobblestone";
const STOCK = 64;
const ORDERED = 8;
const BETA_ADDRESS = "Beta Post";
const DELIVERY_TICKS = 9_000;
const SAMPLE_TICKS = 10;
const DEPARTURE_TICKS = 2_000;

test("a parcel the colony sends by train waits in its postbox for the train booked for it, and is collected at the far end", async ({}, testInfo) => {
  test.setTimeout(BUDGET.heavy);
  ensureOut("e2e");

  const runId = newRunId();
  let pair: E2EPair | undefined;
  const report: any = { scope: "a network that mails by train: real parcel in the postbox, gated onto the booked train" };
  let server: any;
  let client: any;
  let target: Vec | undefined;
  try {
    pair = await launchPair({
      config: CONFIG_PATH,
      server: { trace: tracePath("e2e", "dispatch-mail-server"), instance: seededServer(`dispatch-mail-server-${runId}`) },
      client: { trace: tracePath("e2e", "dispatch-mail-client"), instance: `dispatch-mail-client-${runId}` },
      snapshot: () => ({ containers: target ? [target] : [], entityTypes: ["folkways:resident"] }),
    });
    ({ server, client } = pair);

    const player = await waitForGroundedPlayer(server);
    await screen.dismiss(client);
    await tryCommand(server, `op ${player.name}`);
    const playerUuid = (await world.players(server)).find((p: any) => p.name === player.name)?.uuid;
    expect(playerUuid, "could not read the player uuid, so assembly has no one to sign it").toBeTruthy();

    const origin: Vec = { x: Math.round(player.pos.x) + 4, y: Math.round(player.pos.y), z: Math.round(player.pos.z) + 4 };
    const founding = await foundColonyFast(server, client, player.name, { origin, residentCount: RESIDENTS });
    const colonyId: string = founding.evidence.colonyId;
    report.founding = { residents: founding.residents, colonyId };
    expect(founding.residents, "residents must settle: a conductor, a keeper and someone to collect")
      .toBeGreaterThanOrEqual(3);
    const backup = await tryCommand(server, `item replace entity ${player.name} inventory.0 from entity ${player.name} weapon.mainhand`);
    expect(backup?.success, "the colony book should back up into inventory.0").toBe(true);

    // Alpha, where the train is built, stands past the car; the double-ended train runs Alpha, Beta, Alpha. The car
    // carries a chest, so the colony counts the train as one that takes goods.
    const railOrigin: Vec = { x: origin.x - 8, y: origin.y, z: origin.z + 4 };
    const rail = await buildRailLine(server, client, player.name, playerUuid, railOrigin, RAIL_LINE, "inventory.0", {
      cyclic: true,
      dwell: { Alpha: 20, Beta: 10 },
      decorate: async (srv, _origin, frame) => {
        await tryCommand(srv, `setblock ${commandPos(frame.at(1, 3, 2))} minecraft:chest`);
      },
    });
    report.rail = { ok: rail.ok, carriage: rail.carriageUuid, stationA: rail.stationA, stationB: rail.stationB, error: rail.error };
    expect(rail.ok, `the line should run under the colony: ${rail.error} ${JSON.stringify(rail.steps).slice(0, 400)}`).toBe(true);

    const gy = railOrigin.y;
    const a = rail.stationA;
    const b = rail.stationB;
    // The network's stock lies in a barrel under the packager, inside a stone block two thick, so no resident can
    // reach it and the only way to the goods is an order sent down the line. The postbox sits on the packager.
    const barrel: Vec = { x: a.x + 6, y: gy, z: a.z + 3 };
    const packager: Vec = { ...barrel, y: gy + 1 };
    const link: Vec = { ...packager, z: packager.z - 1 };
    const sender: Vec = { ...barrel, y: gy + 2 };
    const ticker: Vec = { x: a.x + 9, y: gy, z: barrel.z };
    const seat: Vec = { x: a.x + 10, y: gy, z: barrel.z };
    const receiver: Vec = { x: b.x + 5, y: gy, z: b.z };
    target = { x: b.x + 6, y: gy, z: b.z + 2 };

    await tryCommand(server, `fill ${a.x + 4} ${gy} ${a.z} ${a.x + 11} ${gy + 4} ${a.z + 6} minecraft:air`);
    await tryCommand(server, `fill ${barrel.x - 2} ${gy} ${barrel.z - 2} ${barrel.x + 2} ${gy + 1} ${barrel.z + 2} minecraft:stone`);
    await tryCommand(server, `fill ${b.x + 4} ${gy} ${b.z - 2} ${b.x + 8} ${gy + 3} ${b.z + 4} minecraft:air`);
    for (const [at, block] of [
      [barrel, "minecraft:barrel[facing=up]"],
      [packager, "create:packager[facing=up]"],
      [link, "create:stock_link[face=wall,facing=north]"],
      [sender, "create:white_postbox"],
      [ticker, "create:stock_ticker[facing=east]"],
      [seat, "create:orange_seat"],
      [receiver, "create:white_postbox"],
      [target, "minecraft:chest[facing=south]"],
    ] as Array<[Vec, string]>) {
      await tryCommand(server, `setblock ${commandPos(at)} ${block}`);
    }
    await tryCommand(server, `item replace block ${commandPos(barrel)} container.0 with ${ITEM} ${STOCK}`);
    await tick.sprint(server, 10);

    const wired = await runColonyTask(server, WIRE_MAIL, {
      player: player.name, link, ticker,
      posts: [{ post: sender, station: a, address: "", accepts: false },
        { post: receiver, station: b, address: BETA_ADDRESS, accepts: true }],
    });
    report.wired = wired;
    expect(wired.attached, `both postboxes should attach to their stations: ${JSON.stringify(wired)}`).toEqual([true, true]);
    await tick.sprint(server, 40);

    // Everything is handed over as a player does it: the book in the main hand, pointing, left-clicked on each block.
    await tryCommand(server, `gamemode survival ${player.name}`);
    // The rail hand-over left the book in the offhand: it goes back to the main hand.
    await tryCommand(server, `item replace entity ${player.name} weapon.mainhand from entity ${player.name} inventory.0`);
    await tryCommand(server, `item replace entity ${player.name} weapon.offhand with minecraft:air`);
    report.pointing = await setBookGesture(server, client, player.name, "point");
    const state = () => runColonyTask(server, HANDED_OVER, { colony_id: colonyId, network: wired.network, posts: [sender, receiver] });
    const point = async (at: Vec) => {
      await screen.dismiss(client);
      await GROUND.click({ server, client }, at, { player: player.name, face: "up" });
      await tick.sprint(server, 40);
      return state();
    };
    // The stock link hugs the packager inside the stone; the ticker, the network's other handle, stands in the open.
    report.atTicker = await point(ticker);
    expect(report.atTicker.held, "a left-click on the stock ticker should take the network on").toBe(true);
    expect(report.atTicker.flaw, "the network orders nothing while its postboxes are not the colony's")
      .toBe("folkways.dispatch.flaw.not_carried");
    report.atSender = await point(sender);
    expect(report.atSender.posted, "a left-click on Alpha's postbox should give it to the colony").toEqual([true, false]);
    report.atReceiver = await point(receiver);
    expect(report.atReceiver.posted, "a left-click on Beta's postbox should give it to the colony").toEqual([true, true]);
    expect(report.atReceiver.flaw, "with both postboxes given, the network ships by the colony's own train").toBe("");
    expect(report.atReceiver.held, "the network is still the colony's").toBe(true);

    const args = { colony_id: colonyId, sender, receiver, target, item: ITEM, network: wired.network };
    report.before = await runColonyTask(server, MAIL_STATE, args);
    expect(report.before.stock, "the network's barrel should hold its stock before anything is ordered").toBe(STOCK);

    await optInContainerViaUI(server, client, player.name, target);
    // The order goes in just after the train has left Alpha, so its parcel has to wait in the postbox for the train
    // to come back for it.
    let seenAlpha = false;
    report.departure = undefined;
    for (let ticks = 0; ticks < DEPARTURE_TICKS && report.departure === undefined; ticks += 5) {
      await tick.sprint(server, 5);
      const standing = (await runColonyTask(server, MAIL_STATE, args)).standing;
      if (standing === "Alpha") seenAlpha = true;
      else if (seenAlpha) report.departure = ticks;
    }
    expect(report.departure, "the train should leave Alpha on its timetable").not.toBeUndefined();
    await deliverOrderViaSitePanel(server, client, player.name, target, ITEM, ORDERED);
    await tryCommand(server, `tp ${player.name} ${a.x + 6.5} ${gy} ${a.z + 6.5}`);

    const samples: any[] = [];
    let waited: any;
    for (let ticks = 0; ticks < DELIVERY_TICKS; ticks += SAMPLE_TICKS) {
      await tick.sprint(server, SAMPLE_TICKS);
      const sample = await runColonyTask(server, MAIL_STATE, args);
      samples.push({ t: ticks, ...sample });
      if (!waited && sample.waiting) waited = { t: ticks, ...sample };
      if (sample.delivered >= ORDERED) break;
    }
    const last = samples[samples.length - 1];
    report.waited = waited;
    report.last = last;
    report.samples = samples.filter((_, i) => i % 10 === 0 || i === samples.length - 1);

    expect(waited, `the parcel should lie in Alpha's postbox, booked, at some point: ${JSON.stringify(report.samples.slice(-5))}`)
      .toBeTruthy();
    expect(waited.bookedTrain, "the parcel waits for the line's own train").toBe(waited.train);
    expect(waited.gate, "only the booked train may take it from Alpha; any other train leaves it in the postbox")
      .toEqual({ booked: true, other: false, elsewhere: false });
    expect(last.delivered, `the ${ORDERED} ${ITEM} should reach the chest at Beta: ${JSON.stringify(last)}`).toBe(ORDERED);
    expect(last.bookings, "a parcel gone from its postbox is off the books").toEqual([]);
    expect(last.stock, "the goods came out of the network's barrel").toBe(STOCK - ORDERED);
  } catch (error) {
    pair?.failing(error);
    throw error;
  } finally {
    const body = JSON.stringify(report, null, 2);
    writeFileSync(path.join(REPO_ROOT, REPORT_PATH), body);
    testInfo.attach("dispatch-mail-report", { body, contentType: "application/json" });
    await pair?.teardown(testInfo);
  }
});

const POS = `
private static BlockPos pos(Object raw) {
  var p = (Map<?, ?>) raw;
  return new BlockPos(((Number) p.get("x")).intValue(), ((Number) p.get("y")).intValue(), ((Number) p.get("z")).intValue());
}
`;

// Links the packager's stock link and the ticker on one network, and sets each postbox to its station.
const WIRE_MAIL = defineTask<Record<string, any>, Record<string, any>>({
  name: "folkways.e2e.dispatch-mail-wire",
  side: "server",
  timeoutMs: 20_000,
  source: javaTask({
    imports: [
      "com.simibubi.create.Create",
      "com.simibubi.create.content.logistics.packagePort.PackagePortBlockEntity",
      "com.simibubi.create.content.logistics.packagePort.PackagePortTarget.TrainStationFrogportTarget",
      "com.simibubi.create.content.logistics.packagerLink.LogisticallyLinkedBehaviour",
      "com.simibubi.create.content.trains.station.StationBlockEntity",
      "com.simibubi.create.foundation.blockEntity.behaviour.BlockEntityBehaviour",
      "net.minecraft.core.BlockPos",
      "net.minecraft.core.GlobalPos",
      "net.minecraft.server.MinecraftServer",
    ],
    body: `
var server = ctx.server(); var level = server.overworld(); var args = Args.of(ctx);
var network = UUID.randomUUID();
var linked = List.of(args.value("link"), args.value("ticker"));
for (int l = 0; l < linked.size(); l++) {
  var link = BlockEntityBehaviour.get(level, pos(linked.get(l)), LogisticallyLinkedBehaviour.TYPE);
  if (link == null) throw new IllegalStateException("No logistics link at " + linked.get(l));
  link.unload();
  link.freqId = network;
  if (l == 0) {
    var global = GlobalPos.of(level.dimension(), link.getPos());
    Create.LOGISTICS.linkLoaded(network, global);
    Create.LOGISTICS.linkAdded(network, global, server.getPlayerList().getPlayerByName(args.string("player")).getUUID());
  }
  LogisticallyLinkedBehaviour.keepAlive(link);
  link.blockEntity.notifyUpdate();
}
var attached = new ArrayList<Object>();
for (var raw : args.list("posts")) {
  var one = (Map<?, ?>) raw;
  var at = pos(one.get("post")); var station = pos(one.get("station"));
  var port = (PackagePortBlockEntity) level.getBlockEntity(at);
  port.addressFilter = String.valueOf(one.get("address"));
  port.acceptsPackages = Boolean.TRUE.equals(one.get("accepts"));
  port.target = new TrainStationFrogportTarget(station.subtract(at));
  port.target.setup(port, level, at);
  port.target.register(port, level, at);
  port.filterChanged();
  port.notifyUpdate();
  var sbe = (StationBlockEntity) level.getBlockEntity(station);
  attached.add(sbe.getStation() != null && sbe.getStation().connectedPorts.containsKey(at));
}
return Sync.stamp(Map.of("ok", true, "network", network.toString(), "attached", attached));
`,
    members: POS,
  }),
});

// What the colony was handed: whether the network is its, what keeps the network from ordering, and which of the
// postboxes it holds.
const HANDED_OVER = defineTask<Record<string, any>, Record<string, any>>({
  name: "folkways.e2e.dispatch-mail-handed-over",
  side: "server",
  timeoutMs: 20_000,
  source: javaTask({
    imports: [
      "io.github.izakyl.folkways.core.api.colony.Held",
      "io.github.izakyl.folkways.front.engine.colony.ColonyGround",
      "io.github.izakyl.folkways.plugins.dispatch.DispatchContent",
      "io.github.izakyl.folkways.plugins.dispatch.DispatchPresence",
      "net.minecraft.core.BlockPos",
      "net.minecraft.server.MinecraftServer",
    ],
    body: `
var server = ctx.server(); var args = Args.of(ctx);
var colony = ColonyGround.of(server, UUID.fromString(args.string("colony_id"))).orElseThrow();
var dispatch = (DispatchPresence) colony.service(DispatchContent.ID, Object.class).orElseThrow();
var network = UUID.fromString(args.string("network"));
var posted = new ArrayList<Object>();
for (var raw : args.list("posts")) {
  var at = pos(raw);
  posted.add(colony.holdings(DispatchContent.PORT).stream()
    .anyMatch(h -> h.what() instanceof Held.Block b && b.cell().cell().equals(at)));
}
return Map.of("ok", true, "held", dispatch.holds(network), "flaw", dispatch.flawOf(network), "posted", posted);
`,
    members: POS,
  }),
});

// Where the order stands: parcels of the colony's in Alpha's postbox and the colony's books on them, whether the gate
// would let the booked train, another train, or the booked train at another station take one, and what has landed.
const MAIL_STATE = defineTask<Record<string, any>, Record<string, any>>({
  name: "folkways.e2e.dispatch-mail-state",
  side: "server",
  timeoutMs: 20_000,
  source: javaTask({
    imports: [
      "com.simibubi.create.Create",
      "com.simibubi.create.content.logistics.box.PackageItem",
      "com.simibubi.create.content.logistics.packagePort.PackagePortBlockEntity",
      "io.github.izakyl.folkways.front.engine.colony.ColonyGround",
      "io.github.izakyl.folkways.plugins.dispatch.DispatchContent",
      "io.github.izakyl.folkways.plugins.dispatch.MailGate",
      "net.minecraft.core.BlockPos",
      "net.minecraft.core.registries.BuiltInRegistries",
      "net.minecraft.resources.ResourceLocation",
      "net.minecraft.server.MinecraftServer",
      "net.minecraft.world.Container",
      "net.minecraft.world.item.ItemStack",
      "net.neoforged.neoforge.capabilities.Capabilities",
    ],
    body: `
var server = ctx.server(); var level = server.overworld(); var args = Args.of(ctx);
var colony = ColonyGround.of(server, UUID.fromString(args.string("colony_id"))).orElseThrow();
var presence = colony.service(DispatchContent.ID, Object.class).orElseThrow();
var field = presence.getClass().getDeclaredField("mail"); field.setAccessible(true);
var mailroom = field.get(presence);
var all = mailroom.getClass().getDeclaredMethod("all"); all.setAccessible(true);
var bookings = new ArrayList<Object>();
for (var one : (List<?>) all.invoke(mailroom)) {
  var record = (Record) one; var parts = new LinkedHashMap<String, Object>();
  for (var part : record.getClass().getRecordComponents()) {
    part.getAccessor().setAccessible(true);
    parts.put(part.getName(), String.valueOf(part.getAccessor().invoke(record)));
  }
  bookings.add(parts);
}
String train = ""; String standing = "";
for (var t : Create.RAILWAYS.sided(level).trains.values()) {
  train = t.id.toString();
  standing = t.getCurrentStation() == null ? "" : t.getCurrentStation().name;
}
var item = BuiltInRegistries.ITEM.get(ResourceLocation.parse(args.string("item")));
var sender = (PackagePortBlockEntity) level.getBlockEntity(pos(args.value("sender")));
var waiting = new ArrayList<Object>(); Map<String, Object> gate = null; String bookedTrain = "";
for (int slot = 0; slot < sender.inventory.getSlots(); slot++) {
  var stack = sender.inventory.getStackInSlot(slot);
  if (!PackageItem.isPackage(stack) || !PackageItem.hasOrderData(stack)) continue;
  int order = PackageItem.getOrderId(stack);
  for (var raw : bookings) {
    var booking = (Map<?, ?>) raw;
    if (!String.valueOf(order).equals(booking.get("order"))) continue;
    var booked = String.valueOf(booking.get("train")).replace("Optional[", "").replace("]", "");
    if (booked.startsWith("Optional")) continue;
    waiting.add(order);
    bookedTrain = booked;
    var station = String.valueOf(booking.get("station"));
    gate = Map.of("booked", MailGate.mayBoard(stack, UUID.fromString(booked), station),
      "other", MailGate.mayBoard(stack, UUID.randomUUID(), station),
      "elsewhere", MailGate.mayBoard(stack, UUID.fromString(booked), station + " (elsewhere)"));
  }
}
var receiver = (PackagePortBlockEntity) level.getBlockEntity(pos(args.value("receiver")));
int atBeta = 0;
for (int slot = 0; slot < receiver.inventory.getSlots(); slot++) if (PackageItem.isPackage(receiver.inventory.getStackInSlot(slot))) atBeta++;
var chest = (Container) level.getBlockEntity(pos(args.value("target")));
int delivered = 0;
for (int i = 0; i < chest.getContainerSize(); i++) if (chest.getItem(i).is(item)) delivered += chest.getItem(i).getCount();
int stock = 0;
var barrel = level.getCapability(Capabilities.ItemHandler.BLOCK, pos(args.value("sender")).below(2), null);
for (int i = 0; barrel != null && i < barrel.getSlots(); i++) if (barrel.getStackInSlot(i).is(item)) stock += barrel.getStackInSlot(i).getCount();
var probe = new LinkedHashMap<String, Object>();
var packagerBe = level.getBlockEntity(pos(args.value("sender")).below());
if (packagerBe instanceof com.simibubi.create.content.logistics.packager.PackagerBlockEntity pk) {
  probe.put("held", pk.heldBox.isEmpty() ? "" : PackageItem.getAddress(pk.heldBox) + "#" + (PackageItem.hasOrderData(pk.heldBox) ? PackageItem.getOrderId(pk.heldBox) : "-"));
  probe.put("queued", pk.queuedExitingPackages.size());
}
var inSender = new ArrayList<Object>();
for (int slot = 0; slot < sender.inventory.getSlots(); slot++) {
  var stack = sender.inventory.getStackInSlot(slot);
  if (!stack.isEmpty()) inSender.add(BuiltInRegistries.ITEM.getKey(stack.getItem()) + "@" + PackageItem.getAddress(stack) + "#" + (PackageItem.hasOrderData(stack) ? PackageItem.getOrderId(stack) : "-"));
}
probe.put("sender", inSender);
var cargo = new ArrayList<Object>();
for (var t : Create.RAILWAYS.sided(level).trains.values()) for (var carriage : t.carriages) {
  var inv = carriage.storage.getAllItems();
  for (int i = 0; inv != null && i < inv.getSlots(); i++) if (PackageItem.isPackage(inv.getStackInSlot(i))) cargo.add(PackageItem.getAddress(inv.getStackInSlot(i)) + "#" + PackageItem.getOrderId(inv.getStackInSlot(i)));
}
probe.put("cargo", cargo);
var cf = presence.getClass().getDeclaredField("collecting"); cf.setAccessible(true);
var nodes = new ArrayList<Object>();
for (var node : ((Map<?, ?>) cf.get(presence)).values()) {
  var parts = new LinkedHashMap<String, Object>();
  for (var name : List.of("at", "order", "placedAt", "arrived", "refused", "address")) {
    var f = node.getClass().getDeclaredField(name); f.setAccessible(true); parts.put(name, String.valueOf(f.get(node)));
  }
  nodes.add(parts);
}
probe.put("collecting", nodes);
var answer = new LinkedHashMap<String, Object>();
answer.put("probe", probe);
answer.put("ok", true); answer.put("train", train); answer.put("standing", standing);
answer.put("bookings", bookings); answer.put("waiting", !waiting.isEmpty()); answer.put("bookedTrain", bookedTrain);
answer.put("gate", gate == null ? Map.of() : gate); answer.put("atBeta", atBeta);
answer.put("delivered", delivered); answer.put("stock", stock);
return answer;
`,
    members: POS,
  }),
});
