# Integrations

English · [简体中文](INTEGRATIONS.zh-CN.md)

Folkways works alongside a number of other mods. This page explains what each one adds to a
colony, how to start using it and what to expect. Install **Core and Vanilla** first; all Folkways
jars must come from the same build. Unless a row says *client only*, install the mod on both the
server and every client.

## At a glance

| Mod | Folkways jar | Adds |
|---|---|---|
| [Create](https://modrinth.com/mod/create) 6.0.7+ | Folkways × Create | Schematic building, trains, elevators, rail laying, ordering through package networks |
| [Create: FireFighting Additions](https://modrinth.com/mod/create-firefighting-additions) 0.2.3-beta+ | Folkways × Create | Extinguishers and hydrant hoses for damage control |
| [Sable](https://modrinth.com/mod/sable) | Core | Residents live and work aboard sub-levels such as Create Aeronautics ships |
| [AstikorCarts Redux](https://modrinth.com/mod/astikorcarts-redux) 1.2.2+ | Folkways × AstikorCarts Redux | Cart lines between stations |
| [Chisels & Bits](https://www.curseforge.com/minecraft/mc-mods/chisels-bits) 21.1.25+ | Folkways Carving | Carved builds and voxel-detail patterns |
| [Kaleidoscope Cookery](https://github.com/KaleidoscopeMods/KaleidoscopeCookery) 1.6.0+ | Folkways × Kaleidoscope Cookery | A working colony kitchen, rice paddies and seed gathering |
| [Kaleidoscope Tavern](https://github.com/KaleidoscopeMods/KaleidoscopeTavern) 1.2.0+ | Folkways × Kaleidoscope Cookery | Vineyards, winemaking and bar counters |
| [Modular Golems](https://modrinth.com/mod/modular-golems) 3.1.6+ | Folkways × Modular Golems | Golems join the colony as workers |
| [TACZ](https://modrinth.com/mod/timeless-and-classics-zero) 1.1.8+ (including 1.2.0) | Folkways Firearms | Residents use guns with real ammunition |
| [Superb Warfare](https://modrinth.com/mod/superb-warfare) 0.8.9.2+ and Kotlin for Forge 5.x | Folkways Firearms | Residents use guns and crew emplacements |
| [Epic Fight](https://modrinth.com/mod/epic-fight) 21.17.1+ | Folkways × Epic Fight | Native Epic Fight melee, reactions and animations |
| [Sophisticated Storage](https://modrinth.com/mod/sophisticated-storage) 1.6.17+ | Core | Residents use main inventory slots, native filters and upgraded stack capacity. Safe crafting and stonecutting upgrades let an enrolled store double as a pack workstation with Vanilla. Anvil upgrades stay usable where available but grant no anvil work; unsafe processing, consumption, voiding and unknown upgrades make the container unusable |
| [Curios](https://modrinth.com/mod/curios) 9.3.1+ / [Accessories](https://modrinth.com/mod/accessories) 1.1.0-beta.54+ | Core | Player-defined indexed slots, native item restrictions and real resident equipment effects; compatible layers present one Accessories inventory |
| [Sophisticated Backpacks](https://modrinth.com/mod/sophisticated-backpacks) 3.26.21+ | Core | Placed backpacks supply main inventory slots and native filters. Crafting, stonecutting and smithing upgrades also supply pack workstations with Vanilla, including effective Inception upgrades. Anvil upgrades stay usable without granting work; dangerous upgrades disable the container. Carried and worn backpacks follow native player eligibility; their real inventories, installed upgrades and jukebox discs support resident cargo and moving audio |
| [Storage Drawers](https://modrinth.com/mod/storagedrawers) 13.11.1+ | Core | Each drawer is an independent store; controllers and controller IO are extra access points. Native locks and filters apply; conversion, generation and voiding refuse only the affected drawer |
| [Functional Storage](https://modrinth.com/mod/functional-storage) 1.5.7+ | Core | Each drawer is an independent store; controllers and controller extensions are extra access points without duplicate stock. Compacting, conversion, generation and voiding refuse only the affected drawer; ordinary item IO stays usable |
| [Create: Storage (ausmez)](https://modrinth.com/mod/create-storage-neo-forge) 1.3.5+, Create 6.0.7+ | Core | Storage boxes and placed backpack main slots; unsafe upgrades refuse. Controllers are put-only proxies over independent simple boxes; automation interfaces and passers refuse |
| [Tom’s Simple Storage](https://modrinth.com/mod/toms-storage) 2.0.8–2.x | Core | Connectors, storage/crafting terminals and cable connectors facing a connector are proxies. Every member must pass normal store admission; filters, inventory proxies and automation refuse |
| [Colossal Chests](https://modrinth.com/mod/colossal-chests) 1.9.1+, CyclopsCore 1.30.1+ | Core | A complete chest’s core, walls and interfaces share one store. Incomplete structures refuse; uncolossal chests are ordinary stores |
| [Mekanism QIO](https://modrinth.com/mod/mekanism) 10.7.0+ | Core | Dashboards access a frequency/drive network within one dimension through `network_access/take` and `put`. Room respects each drive’s item/type limits conservatively. Drive slots and automation grant no item access; QIO crafting is outside this integration |
| [Applied Energistics 2](https://modrinth.com/mod/ae2) 19.0.20-beta–19.x | Core | Admit each ME terminal, crafting terminal or ME chest with the colony book; devices share one network inventory. The `folkways_core:network_access/take`, `folkways_core:network_access/put` and `folkways_core:network_access/craft` block tags set the most a device may do; its **Network devices** page narrows that (see below). Residents order network autocrafting at a device and collect its output; ME interfaces are excluded. Networks can call colony Workshop recipes: residents collect accepted inputs, process them and return the outputs. Enable the network service vocation first (off by default). A per-player terminal button shows or hides colony-only craftable entries without removing them from crafting calculations. Accepted work persists and continues after a network order is canceled. |
| [Refined Storage 2](https://modrinth.com/mod/refined-storage) 2.0.9–2.x | Core | Admit each Grid, Crafting Grid, Pattern Grid or configured item Storage Monitor with the colony book; devices share one network inventory and the same `folkways_core:network_access/take`, `folkways_core:network_access/put` and `folkways_core:network_access/craft` tags as AE2, narrowed by the same **Network devices** page. Residents order autocrafting at a device and collect the output; interfaces and disk interfaces are excluded. Networks can call colony Workshop recipes: residents collect accepted inputs, process them and return the outputs. Enable the network service vocation first (off by default). A per-player terminal button shows or hides colony-only craftable entries without removing them from crafting calculations. Accepted work persists and continues after a network order is canceled. |
| [GeckoLib](https://modrinth.com/mod/geckolib) 4.7.5+ | Vanilla, *client only* | Bedrock-format resident models |
| [Touhou Little Maid](https://modrinth.com/mod/touhou-little-maid) 1.5+ | Vanilla | Maid models as resident looks |
| [FTB Teams](https://www.curseforge.com/minecraft/mc-mods/ftb-teams-forge) 2101.1+ | Core | Hand a colony to a party |
| [JEI](https://modrinth.com/mod/jei) / [EMI](https://modrinth.com/mod/emi) | Core, *client only* | Drag items into Folkways filters |
| [Just Enough Characters](https://www.curseforge.com/minecraft/mc-mods/just-enough-characters) 4.5+ | Core, *client only* | Pinyin search |
| [Shoulder Surfing Reloaded](https://modrinth.com/mod/shoulder-surfing-reloaded) 5.0.1+ | Core, *client only* | Colony Book aims along its crosshair |
| [AppleSkin](https://modrinth.com/mod/appleskin) 3.0+ | Core, *client only* | Its overlays hide in the View from above |
| [Iris](https://modrinth.com/mod/iris) | Core, *client only* | Zone and path overlays cast no shader shadows |

Storage integrations target Minecraft 1.21.1 on NeoForge. Sophisticated Storage and Backpacks also need
Sophisticated Core 1.5.33+; Functional Storage needs Titanium 4.0.52+. Functional Storage declares its
loader version as `1.21.1-1.5.7`, including the Minecraft prefix. AE2, Refined Storage and Tom’s keep
their next-major upper bounds because their terminal/accessor mixins depend on internal classes;
those next majors have no 1.21.1 NeoForge release to verify. The other storage ranges have no upper bound.

The Colony Book's **Pointing**, **Marking out** and **Drawing a line** modes appear below; sneak
and scroll to switch between them. Where a **lesson** is mentioned, find it in the colony panel's
**Lessons** tab.

## Building and transport

### Create

Even without the extension, Core keeps residents from walking into moving contraptions.
With Folkways × Create:

- **Schematics.** Place a schematic with Create's tools while holding the Colony Book in the
  other hand, then choose **Hand Off**. Decide whether to keep or dig out whatever stands in the
  blueprint's empty cells. Builders work through it like any other build order; nothing appears
  instantly, even in creative.
- **Trains.** With the book in the off hand, use a Train Schedule on the driver's seat of a train
  without one. Residents with the **Drive** trade run it, and other residents ride it as
  passengers. The train needs at least two stops, each a single named station, and only
  time-based wait conditions. Use an empty hand on the seat to take the train back.
- **Elevators.** Build a powered Create elevator, then left-click its pulley in Pointing mode.
  Residents walk in, ride and walk out. Capacity comes from the clear floor area, and landings
  must be clear and walkable.
- **Rail laying.** Draw a line and choose the **Railway** path. Residents grade the route,
  tunnel through hills, bridge rivers and lay real, connected Create track. Runs must be straight
  or 45° diagonal, with gentle grades and bends of 90° or less.
- **Ordering through a package network.** In Pointing mode, left-click a Stock Ticker or stock
  link to take its network on, and frogports or postboxes to use them for delivery. A resident
  with the **Dispatch** trade sits beside the Stock Ticker and places orders. The **Dispatch**
  page lists anything that stops the network from being used.

Lessons: Rail, Dispatch, Elevators and Handing a Create schematic to the colony.

### Create: FireFighting Additions

Damage-control workers prefer a hydrant cabinet supplied with a hose, nozzle and water;
otherwise they borrow an extinguisher. They refill extinguishers from water sources in the zone,
fluid tanks or water stored in the colony. Turn on **Borrow and refill firefighting tools** in the
damage-control zone's settings. Only water is used.

### Sable

Residents walk onto and off ships and other sub-levels, keep working aboard while they move, and
registered blocks travel with the ship. Boarding works only while the ship is docked level and
flush against a solid shore.

### AstikorCarts Redux

Draw a line in the colony and choose **Cart line**. Each point is a station, up to 16. Pick the
cart type and whether it runs **Out and back** or as a **Loop**. The colony makes or takes a cart and
sets it at the first station. Hauling residents pull hand carts. Supply carts need a tamed adult horse
from a pasture. The cart stops at every station to load and unload colony deliveries. Routes need wide
gateways and room at corners; jumps, drops and climbs are refused. Carts do not ride trains or elevators.

Lesson: Cart lines: stations and laps.

### Chisels & Bits (Folkways Carving)

Builders make chiselled shapes out of whole blocks, losing a little material to rounding. Bundled
patterns include a torii, rotunda, Gothic cloister, flying-eave pavilion, stone arch bridge,
rampart and curved gallery. Selected areas can be saved as blueprints or templates from the
**Blueprints** tab. Custom patterns can declare `detail = "bits"` to draw at voxel resolution.

> Folkways Carving replaces the older Folkways × Chisels & Bits jar. Remove
> `folkways-chiselsandbits-*.jar` before installing `folkways-carving-*.jar`; keeping both crashes the game.

## Storage and carried equipment

### Store admission

Storage accepts vanilla containers and explicit integrations. Unadapted modded containers must implement vanilla
`Container` and have the block tag `folkways_core:store/allowed` to enroll. The tag `folkways_core:store/refused` overrides every
integration and the allow tag. It includes Create item vaults (residents cannot hand-access them) and Lootr
containers (contents are per player). Already enrolled stores remain enrolled when refused; their frame and
panel show the reason, and residents exclude their stock and stop using them. No goods or registrations are deleted.

### Controllers and access points

Storage Drawers controllers/controller IO, Functional Storage controllers/extensions and Sophisticated Storage
controllers/storage IO represent independent member stores. Enrolling an access point enrolls every member
atomically; a refused or occupied member prevents the operation. Removing the access point removes its members.
Removing one member also un-enrolls the access point and leaves other members enrolled. Newly connected safe
members are enrolled automatically; refused additions are skipped. Residents use either a member or its enrolled
access point, and stock counts only once per member. Previously associated disconnected members remain enrolled
and are also removed when their access point is withdrawn.

### Network routing

Residents can route a delivery through two enrolled access points of the same usable store: put near the source,
take near the destination, then carry on. The network send is instant; the planner chooses it only when its two
carrying legs cost less than carrying directly. Transit reserves shared storage room until collection, obeys the
store's take/storing permissions, item filters and colony withholds, and replans if outside users consume the goods.
AE2 partitions and upgraded cells contribute no promised room; per-cell type limits remain conservative.
RS2 room respects native disk/drive filters. Native insertion simulation still decides whether a transfer can execute.

Deposits received by native AE2 or RS2 crafting jobs count as delivered even when stored stock does not grow.
Accepted colony furnace work resumes after reload without loading its inputs or fuel twice. If its assigned resident
becomes unavailable before collection, another eligible resident takes over. If work cannot continue or its recipe
is removed, reservations are released, the commissioning job is canceled and buffered inputs return to the network;
a full or disconnected network keeps those inputs buffered until return is possible.

### Network devices page

Use the colony book on an enrolled AE2 or RS2 device to open its **Network devices** page, laid out like a store's own
page. The block tags remain the ceiling: a switch a device's kind cannot use is not shown. Everything the tags allow is
on by default and the filter is an empty blacklist, so existing worlds behave as before.

- **Store** and **Take**: whether residents may put goods in or take them out here.
- **Residents order crafts**: whether residents may submit network autocrafting at this device.
- **Network commissions residents**: whether the colony's recipes are offered to the network through this device.
  With every device of a network off, no colony provider attaches to it.
- **Item filter**: a whitelist or blacklist that bounds storing, taking, ordering and the colony recipes offered
  through the device (a recipe is offered when one commissioning device admits all its outputs).
- **Whole network**: how many crafting CPUs the colony's own orders may hold at once. The default is half the
  network's CPUs, so a one-CPU network is left to players; orders beyond it wait or fall back to the colony's
  workshops, and CPUs set to player-only are never used. Refined Storage has no CPUs and no task limit; the colony
  already runs one of its own tasks per network at a time, so there the setting allows that one task (default) or none.

Networks have no saved identity, so the CPU share is written onto every enrolled device of the network and read back
as the smallest value any of them remembers: merged networks take the stricter share, and both halves of a split keep
it. Settings are saved with the colony and checked again on the server when a transfer, order or provider runs.
The colony panel's Network devices page lists every enrolled device by network.

### Equipment profiles and carried equipment

Group and command squads in **RTS** mode: hold the Colony Book, press **V**, then select **RTS**.
Assign their equipment through **Equipment profiles** in the colony panel.

Each resident follows one **Equipment profile**. Named slots mean actual places to wear equipment: head, chest,
legs, feet, and the slots supplied by installed accessory frameworks. The inventory list contains personal supplies,
ammunition and weapons with target counts and refill thresholds. Work borrows the main hand; combat sessions take
weapons from the combined inventory. Hands are not configurable slots. **Ride a horse** requests a pasture horse
and its mandatory saddle through the existing patrol supply rules; horse armour is an optional child setting.
Turning riding off returns the horse equipment through the same patrol rules.

When a resident dies or leaves a razed colony, native accessory and cosmetic slots drop their real items once.
Worn backpacks retain their nested contents in the dropped item. Stack upgrades also retain their full native counts
during resident transfers; planning uses captured slot capacity even if equipment changes afterward.

Personal inventory entries use the existing `PackKeeps` allocation: their actual units are excluded from colony
stock and unloading, and their cells are excluded from cargo space promised by planning. Remaining units are cargo.
Named equipment and inventory supplies use ordinary fetch planning even outside patrol duty. Removing requirements
returns surplus through ordinary hauling. No item is automatically assigned to a different slot.

Curios **9.3.1+** and Accessories **1.1.0-beta.54+** are optional Core integrations. Each reads the player entity type's
actual slot assignments and counts, including data packs and native configuration, after the framework's reload or
client synchronization. Every index becomes its own slot (`curios:ring/0`, `accessories:ring/0`); frameworks stay
separate even when they use the same name. A Curios compatibility layer (`cclayer`, or Accessories Compatibility
Layer) unifies the inventory under Accessories, so residents show only Accessories. The old cclayer replaces the
Curios jar; the newer compatibility layer patches a genuine Curios installation. Slots are looked up through the
framework's own data manager for resident entity types as well, so their real capabilities own the worn items and
run native ticks, attributes and effects. Removed frameworks leave profile entries saved but hidden and unsupplied.

Pickers use native slot predicates. With loaded assigned residents they also filter by those residents' equip
rules. Saving and equipping check again. Curios uses `DynamicStackHandler.isItemValid` (including slot validators,
`ICurio.canEquip` and equip events); Accessories uses `AccessoriesAPI.canInsertIntoSlot` (predicates, equip callbacks
and `Accessory.canEquip`). Without a loaded resident, only the native predicates supporting a null entity run.
Accessory groups share the page's **Accessories ▾** chooser, with framework names retained.

Sophisticated Backpacks **3.26.21+** declares the vanilla chest slot and provides separate native Curios and
Accessories `back` integrations. Players and residents may equip a chest backpack and accessory backpacks
at the same time, subject to each native slot's acceptance/count; profiles impose no extra one-backpack limit.

Worn equipment is drawn on residents as on other players. Armour uses the vanilla armour layer. When the
frameworks are installed, the resident renderer also adds their own player layers: Sophisticated's
`BackpackLayerRenderer` (the chest-slot backpack), Curios' `CuriosLayer` and Accessories' `AccessoriesRenderLayer`.
These layers send each worn item to the renderer registered for it in `CuriosRendererRegistry` or
`AccessoriesRendererRegistry`, including Sophisticated's back-slot backpack renderers. They also honour cosmetic
stacks and the per-slot render toggles. Folkways has no editor for those toggles, so they keep their default (shown).
Inventory backpacks stay hidden, as they are on players. The frameworks sync their containers to tracking clients
themselves, so Folkways sends no extra packets. The resident renderer exposes the drawn humanoid pose, so
`followBodyRotations`-style item renderers follow residents. GeckoLib models (YSM and Touhou Little Maid looks)
place the same layers on their torso bone (`body`, `upperBody` or `UpBody`). The model's main torso cube stands in
for the humanoid body. Gear anchored to the head or limbs uses humanoid proportions from that torso, so it can sit
slightly off on models with long or short limbs. Models without such a bone show no worn gear, just as they show no
chest armour. Epic Fight draws only the layers it has patched versions of (armour and held items), so accessories
and backpacks are not shown while it animates a resident.

Backpack eligibility follows the installed player's rules (verified against Backpacks 3.26.21.2249 and Core
1.5.33.2470). `PlayerInventoryProvider.addPlayerInventoryHandler` prepends handlers: the base priority is armor,
offhand, main; native Curios/Accessories handlers go ahead of those in reverse registration order. Identifier
iteration follows each framework's native registry/container order, then ascending slot index. The open key
(`BackpackOpenPayload.findAndOpenFirstBackpack`) opens the first match; it does not limit active backpacks.
Residents use the same handler priority for inventory merging, pickup eligibility and playlist selection. Their
base inventory corresponds to player `main`, chest to `armor`, offhand to `offhand`, and real accessory capabilities
to the corresponding handler. Bags inside another bag do not become base-inventory roots.

By default `nerfs.onlyWornBackpackTriggersUpgrades=false`: all of those positions participate, including every
ordinary inventory backpack. There is no first-N tick limit. When true, `CommonEventHandler.onItemPickup` uses
`runOnBackpacks(..., true)`, admitting armor and accessories but excluding main/offhand. Tick-based upgrades have
a narrower native check: `BackpackItem.inventoryTick` requires `itemSlot <= -1`. NeoForge `Inventory.tick` passes
positive indices for main, armor and offhand, while Curios `CuriosEventHandler.tick` and Accessories
`AccessoriesEventHandler.onLivingEntityTick` pass -1. Thus in this version only accessory backpacks tick magnet,
feeding and jukebox upgrades with that setting enabled; a chest backpack still participates in pickup events.
`MagnetUpgradeWrapper.tick` and `FeedingUpgradeWrapper.tick` follow that tick gate;
`PickupUpgradeWrapper.pickup` and the magnet wrapper's `pickup` are invoked by pickup events.
Disabled upgrades never grant abilities. Workstation upgrades are not tick-based.

`CommonEventHandler.onWorldTick` checks UUID duplicates every 40 ticks unless `tickDedupeLogicDisabled=true`.
`UUIDDeduplicator.dedupeBackpackWrappers` retains the larger inventory (first on a tie) and removes the duplicate's
UUID; it does not select one active backpack. Residents use that native operation, including player/resident
collisions in the loaded level. Optional `nerfs.tooManyBackpacksSlowness` (default false) applies the configured
effect above `maxNumberOfBackpacks` (default 3), with the native ratio, 80-tick duration and ten-level cap; all bags
remain eligible. Neither count setting is an equipment-profile refusal.

Every eligible bag contributes its own real inventory alongside the resident's original 18 cells
(and existing base-pack perks). This cargo merging is the Folkways addition to player behavior.
Native per-item, per-cell limits and filters govern insertion, removal, load sizing,
route admission and cargo planning. Stack upgrades change cell limits, not the number of resident cells; there is
no kit capacity multiplier or synthetic enlarged inventory.

Upgrade entries are nested beneath their worn backpack, and discs beneath a jukebox upgrade. The nested editor
shows each parent's configured used/total slots. Residents fetch actual stock, check
`IUpgradeItem.canAddUpgradeTo`, and insert through `UpgradeHandler`; discs go through the native jukebox wrapper's
disc inventory. Actual status and abilities read these real inventories. Removing requirements first checks
`canRemoveUpgradeFrom`, including stack-upgrade overflow checks, and then extracts the real item. Refused removals
remain installed. Successful extractions become ordinary cargo; overflow is dropped without discarding an item.
Transfers shrink their source only by the quantity accepted by the destination. Native backpack data and its
storage UUID remain the authoritative save, with no duplicate shadow inventory in Folkways.

Crafting, stonecutting and smithing upgrades provide the bearer with pack workstations. Installed pickup, magnet
and feeding upgrades provide the existing collection and feeding behavior; disabled upgrades grant no ability.
Jukebox playback reads real installed discs and uses the existing moving sound handler, slot order and continuous
playlist restart. Changing profiles, removing the required items, death and unloading stop playback. Sound state
is not saved. Processing, voiding, compacting, inception, tool swapping, refill, restock, deposit and unknown
upgrades remain unavailable as configurable child upgrades.

Focused native fixtures use `-PaccessoryProfile=curios` or `accessories` with `-PgameTestMod=core`.
`-PaccessoryCoexistence=true` adds genuine Accessories beside Curios; `-PaccessoryCompat=true` adds the old cclayer
beside Accessories. These select separate source sets and do not load the base Core suite.

### Kaleidoscope Cookery

Register appliances as **Kitchen stations** and keep the **Kitchen** page in view. Order the
finished dish you want; residents with the **Craft** trade plan every step in between, from
milling flour to cooking. Residents also farm flooded rice plots, gather grass seeds, compost
surplus and share kitchen tools and farm clothing, returning each after the task. Heat sources must be
lit and sheltered. Stir-fries burn if left too long.

Lessons: thirteen lessons in the **Kitchen** folder.

### Kaleidoscope Tavern

Requires the Cookery extension. Residents raise pergolas on grape plots, then press, ferment and
bottle wine. Register bar counters inside a dining area and choose **Bar drinks**; residents
stock them and diners drink there. Barrels need a way onto their top, and the colony needs
buckets and empty bottles in store.

Lesson: Tavern: from the vineyard to a glass of wine.

## Residents and defense

### Modular Golems

In Pointing mode, left-click a golem you own to admit it, then click again to release it.
Golems take trades by kind. Metal golems haul, build and patrol. Dog golems haul and patrol.
Humanoid golems can also craft, drive trains and dispatch. Golems never eat or sleep, and their
skills do not grow. They patch themselves up with repair items from their pack.

Lesson: Golems: put your Modular Golems to work.

### TACZ and Superb Warfare (Folkways Firearms)

Put a supported gun in an **Equipment profile**'s inventory list, assign residents and keep matching ammunition in
colony stores. Then staff patrol routes or sentry posts, or command residents directly. Residents
load only real rounds from their pack, fire at the gun's own pace and do not hurt colony members
or pets. Unloaded TACZ rounds go back to the pack.

- **TACZ:** default `tacz:` guns are supported. Explosive, incendiary and infinite ammunition is not.
  Servers can adjust allowed guns and range in `folkways_firearms-server.toml`.
- **Superb Warfare:** Glock 17, MP5, AK-47, M4, M870 and SVD; emplacements MK-42, BL-132, MLE-1934,
  FH-77BW, mortar and HPJ-11, with plain high-explosive or mortar shells. Point at an emplacement
  and choose **Independent weapon station**, or place it inside a sentry post.

### Epic Fight

Residents armed with melee weapons that Epic Fight recognizes use its native strikes, hit reactions
and stun, plus Epic Fight's idle, walking, eating and sleeping poses. Bedrock models are rendered
on Epic Fight's skeleton when GeckoLib is installed. Use equipment profiles and patrols as usual. Residents
do not dodge, raise shields or fight bare-handed with Epic Fight moves.

### GeckoLib and Touhou Little Maid

GeckoLib lets residents use Bedrock-format models from the **Look pool** tab; without it these
looks fall back to a skin. With Touhou Little Maid, built-in maids and installed model packs
appear in the look pool, one by one or by pack. They need GeckoLib on the client.

## Colony management and interface

- **FTB Teams:** on the **Members** tab, the founder can **Hand to FTB team**. Members are then
  managed through the party, and the party owner holds the founder's rights and can take the
  colony back.
- **JEI / EMI:** drag items from the recipe viewer into Folkways filter and picker slots.
- **Just Enough Characters:** full pinyin, initials and mixed input in item and folder pickers,
  Workforce and Storage, following JEC's own settings.
- **Shoulder Surfing Reloaded:** the Colony Book aims along its third-person crosshair.
- **AppleSkin:** its overlays are hidden with the health and food bars in the View from above.
- **Iris:** with a shader pack, Folkways zone and path overlays do not cast shadows.
