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
| Sable / Create | Folkways Airline | Airport patterns, surveys, towers and moving landing facilities |
| [AstikorCarts Redux](https://modrinth.com/mod/astikorcarts-redux) 1.2.2+ | Folkways × AstikorCarts Redux | Cart lines between stations |
| [Chisels & Bits](https://www.curseforge.com/minecraft/mc-mods/chisels-bits) 21.1.25+ | Folkways Carving | Carved builds and voxel-detail patterns |
| [Kaleidoscope Cookery](https://github.com/KaleidoscopeMods/KaleidoscopeCookery) 1.6.0+ | Folkways × Kaleidoscope Cookery | A working colony kitchen, rice paddies and seed gathering |
| [Kaleidoscope Tavern](https://github.com/KaleidoscopeMods/KaleidoscopeTavern) 1.2.0+ | Folkways × Kaleidoscope Cookery | Vineyards, winemaking and bar counters |
| [Modular Golems](https://modrinth.com/mod/modular-golems) 3.1.6+ | Folkways × Modular Golems | Golems join the colony as workers |
| [TACZ](https://modrinth.com/mod/timeless-and-classics-zero) 1.1.8+ (including 1.2.0) | Folkways Firearms | Residents use guns with real ammunition |
| [Superb Warfare](https://modrinth.com/mod/superb-warfare) [0.8.9.2,0.8.9.3) and Kotlin for Forge 5.x | Folkways Firearms | Residents use guns and crew emplacements |
| [Epic Fight](https://modrinth.com/mod/epic-fight) 21.17.1+ | Folkways × Epic Fight | Native Epic Fight melee, reactions and animations |
| [Sophisticated Storage](https://modrinth.com/mod/sophisticated-storage) 1.6.17+ | Core | Its containers as stores; crafting and stonecutting upgrades double as workstations |
| [Curios](https://modrinth.com/mod/curios) 9.3.1+ / [Accessories](https://modrinth.com/mod/accessories) 1.1.0-beta.54+ | Core | Accessory slots in equipment profiles, worn with their real effects |
| [Sophisticated Backpacks](https://modrinth.com/mod/sophisticated-backpacks) 3.26.21+ | Core | Placed backpacks as stores; worn backpacks add cargo room, upgrades and music to residents |
| [Storage Drawers](https://modrinth.com/mod/storagedrawers) 13.11.1+ | Core | Drawers as stores, reached directly or through controllers |
| [Functional Storage](https://modrinth.com/mod/functional-storage) 1.5.7+ | Core | Drawers as stores, reached directly or through controllers |
| [Create: Storage (ausmez)](https://modrinth.com/mod/create-storage-neo-forge) 1.3.5+, Create 6.0.7+ | Core | Storage boxes and placed backpacks as stores; controllers for putting goods away |
| [Tom’s Simple Storage](https://modrinth.com/mod/toms-storage) 2.0.8–2.x | Core | A connected inventory network reached through connectors and terminals |
| [Colossal Chests](https://modrinth.com/mod/colossal-chests) 1.9.1+, CyclopsCore 1.30.1+ | Core | A complete colossal chest as one store |
| [Mekanism QIO](https://modrinth.com/mod/mekanism) 10.7.0+ | Core | A QIO network reached through dashboards |
| [Applied Energistics 2](https://modrinth.com/mod/ae2) 19.0.20-beta–19.x | Core | Terminals as stores, network autocrafting orders, and colony workshops serving the network |
| [Refined Storage 2](https://modrinth.com/mod/refined-storage) 2.0.9–2.x | Core | Grids as stores, network autocrafting orders, and colony workshops serving the network |
| [GeckoLib](https://modrinth.com/mod/geckolib) 4.7.5+ | Vanilla, *client only* | Bedrock-format resident models |
| [Touhou Little Maid](https://modrinth.com/mod/touhou-little-maid) 1.5+ | Vanilla | Maid models as resident looks |
| [FTB Teams](https://www.curseforge.com/minecraft/mc-mods/ftb-teams-forge) 2101.1+ | Core | Hand a colony to a party |
| [JEI](https://modrinth.com/mod/jei) / [EMI](https://modrinth.com/mod/emi) | Core, *client only* | Drag items into Folkways filters |
| [Just Enough Characters](https://www.curseforge.com/minecraft/mc-mods/just-enough-characters) 4.5+ | Core, *client only* | Pinyin search |
| [Shoulder Surfing Reloaded](https://modrinth.com/mod/shoulder-surfing-reloaded) 5.0.1+ | Core, *client only* | Colony Book aims along its crosshair |
| [AppleSkin](https://modrinth.com/mod/appleskin) 3.0+ | Core, *client only* | Its overlays hide in the View from above |
| [Iris](https://modrinth.com/mod/iris) | Core, *client only* | Zone and path overlays cast no shader shadows |

Sophisticated Storage and Backpacks also need Sophisticated Core 1.5.33+; Functional Storage needs
Titanium 4.0.52+. AE2, Refined Storage and Tom’s Simple Storage are supported up to, but not including,
their next major version; the other storage mods have no upper bound.

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

### Airports (Folkways Airline)

Airline provides airport drawing patterns, surveys, towers, runway and helipad geometry, taxiways and stands.
Sable supports moving airport frames. Superb Warfare vehicle control belongs to Firearms and uses Core
landing-site geometry.

Runways are delimited by continuous white edge lines and piano-key threshold stripes at both ends, including on
oblique headings. The drawing adds long centreline dashes and paired aiming points. Aprons are continuous gray
pavement: a yellow lead-in ending at a three-block white stop line identifies each stand. Clearance to pavement
boundaries and neighbouring stands determines parking dimensions. Connected pavement is
taxiable without a yellow centreline; routes prefer guide lines and central clearance, and admit aircraft by the
minimum boundary clearance along the route. Unreachable stands or runways are reported. Helipads use a white
border and slender white H (width about 60% of height), with an optional yellow touchdown circle. All facilities
can share continuous pavement; grass gaps are unnecessary.

Airport bookings queue Superb Warfare combat departures (including catapults) and landings in request order. Without Airline, Firearms keeps a local runway queue. Bookings are not saved.

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

Upgrading from the older Folkways × Chisels & Bits jar: see the note under [Mods](README.md#mods).

## Storage and carried equipment

### Stores from storage mods

Register a supported container with the Colony Book like any chest. Residents obey that mod's own
locks, filters and slot limits. An upgrade or mode that processes, converts, generates, consumes or voids
items makes the container unusable (for drawers, only the affected drawer): its outline and store page
show the reason, residents stop using it, and no goods or registrations are deleted. Other modded
containers with a plain vanilla inventory can be enrolled only if a modpack adds them to the
`folkways_core:store/allowed` block tag; `folkways_core:store/refused` overrides everything and by
default holds Create item vaults and Lootr containers.

Controllers and other access points stand for the stores behind them. Registering one registers every
member at once (nothing is registered if any member is refused or already claimed), removing it removes
them, newly connected members join on their own, and stock is counted once. Residents may also send goods
through a network, putting them in near the source and taking them out near the destination, when that
saves walking.

- **Sophisticated Storage / Backpacks:** placed containers and backpacks are stores; storage controllers and
  storage IO are access points. Crafting and stonecutting upgrades (and smithing on backpacks) work as
  workstations with Vanilla; anvil upgrades are harmless but grant no work.
- **Storage Drawers / Functional Storage:** each drawer is its own store; controllers, controller IO and
  extensions are access points.
- **Create: Storage:** storage boxes and placed backpacks are stores. Its controller only takes goods in
  (by hand insertion); automation interfaces and passers are refused.
- **Tom’s Simple Storage:** connectors, storage and crafting terminals, and cable connectors facing a
  connector are access points. Every connected inventory must be acceptable on its own; filters,
  inventory proxies and automation are refused.
- **Colossal Chests:** a complete chest's core, walls and interfaces are one store; unfinished structures
  are refused.
- **Mekanism QIO:** register a QIO Dashboard. It and every drive of its frequency must be in one dimension.
  Drive slots and automation give no item access, and QIO crafting is not used. The
  `folkways_core:network_access/take` and `put` block tags limit what dashboards may do.

### Applied Energistics 2 and Refined Storage 2

Register ME terminals, crafting terminals or ME chests (AE2), or Grids, Crafting Grids, Pattern Grids and
item Storage Monitors (RS2), with the Colony Book; every device on one network shares one stock. Interfaces
are not stores. Residents can order network autocrafting at a device and collect the result.

To let the network use colony Workshop recipes, enable the **Network service** vocation (off by default):
residents collect the inputs, process them at workshops and return the products. Accepted work survives
reloads and is finished even if the network order is canceled; work that cannot continue returns its inputs
to the network. A per-player terminal button shows or hides these colony-only craftable entries.

Use the book on a registered device to open its **Network devices** page: **Store**, **Take**, **Order**
(network autocrafting) and **Commissions** (offering colony recipes) switches, an **Item filter**, and under
**Whole network** how many crafting CPUs colony orders may hold (default half; Refined Storage, which has no
CPUs, allows or stops the colony's single task). Everything starts enabled. Modpacks set the ceiling with the
`folkways_core:network_access/take`, `put` and `craft` block tags. The colony panel's **Network devices** page
lists every registered device by network.

### Equipment profiles, accessories and backpacks

Each resident follows one **Equipment profile** from the colony panel. Named slots are real places to wear
things: head, chest, legs, feet and any accessory slots. The inventory list holds weapons, ammunition and
supplies, each with a target count and refill threshold. **Ride a horse** has residents fetch a pasture horse
and saddle, with optional horse armour. Residents fetch everything from stores and return surplus; personal
items do not count as colony stock.

- **Curios / Accessories:** residents get the same accessory slots as players, including data-pack changes.
  Pickers offer only items a slot accepts, and worn items give their real effects. With a Curios
  compatibility layer installed, only the Accessories slots appear. Removing a framework hides its entries
  without deleting them.
- **Sophisticated Backpacks:** a resident may wear a backpack in the chest slot and in a `back` accessory
  slot at once. Worn backpacks add their real inventory to the resident's own 18 cells, and stack upgrades
  raise their slot limits. Nest upgrades under a backpack and discs under a jukebox upgrade: crafting,
  stonecutting and smithing upgrades act as workstations, pickup, magnet and feeding work as for players, and
  the jukebox plays while the resident walks. Processing, voiding, compacting and other upgrades cannot be
  configured. Which backpacks are active follows Sophisticated Backpacks' own player rules and settings.
- When a resident dies or leaves a razed colony, worn accessories and backpacks drop once, with their contents.
- Worn gear is drawn on residents as on players, including GeckoLib looks with a torso bone. It is hidden while
  Epic Fight animates a resident.

## Food and drink

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

Superb Warfare support is limited to **[0.8.9.2,0.8.9.3)**, the verified 0.8.9.2 version line.
Later versions require rechecking the native engine and mixin bindings before widening this range.

Put a supported gun in an **Equipment profile**'s inventory list, assign residents and keep matching ammunition in
colony stores. Then staff patrol routes or sentry posts, or command residents directly. Residents
load only real rounds from their pack, fire at the gun's own pace and do not hurt colony members
or pets. Unloaded TACZ rounds go back to the pack.

- **TACZ:** default `tacz:` guns are supported. Explosive, incendiary and infinite ammunition is not.
  Servers can adjust allowed guns and range in `folkways_firearms-server.toml`.
- **Superb Warfare:** Glock 17, MP5, AK-47, M4, M870 and SVD; emplacements MK-42, BL-132, MLE-1934,
  FH-77BW, mortar and HPJ-11, with plain high-explosive or mortar shells. Point at an emplacement
  and choose **Independent weapon station**, or place it inside a sentry post.

With Iris shaders enabled, Firearms uses per-vertex rendering for SimpleBedrockModel triangle meshes to avoid
corrupt vehicle geometry in Iris 1.8's bulk conversion. Vehicle models and shadows remain enabled.

Mobile Superb Warfare vehicles also have server-side RTS units. Resident crew assignments and colony ownership
survive reloads; leaving releases the residents back to their ordinary work. Flight orders and deck-local parking
anchors also survive reloads; interrupted catapult departures stop safely before a new departure order.
Crew can leave a supported hull at rest on pavement or a moving deck. The unit command API exposes seats,
weapons, firing rules, routes and receipts separately from the interface windows. Ground vehicles, helicopters and
fixed-wing aircraft plan velocity and attitude at the native engine boundary, retaining SW collision and energy
failure behavior. Airline, when installed, supplies helipads and runway ends without a Firearms dependency on Airline.
Runway candidates use each aircraft's acceleration to lift speed, touchdown braking distance, OBB span and tracking margin;
approach turns use the separately surveyed airspace corridor.
The command card fits each airframe: ground vehicles **Move**, **Attack** and **Stop**; helicopters also **Land**,
and **Stop** hovers in place; fixed wing **Loiter** over a clicked point and **Land**. There is no take-off command:
a movement or loiter order to a parked aircraft first takes a free, suitable departure surface at its current
airport. Aircraft hold the selected altitude band (**Low**, **Cruise**, **High**; Cruise by default) above the
terrain ahead, climbing early for hills.
Commanded vehicles predict nearby vehicle motion, keep following distance and pass obstacles on their right;
at crossing conflicts the higher entity id yields. Ground vehicles and helicopters reserve separate stopping
positions around a shared destination. Helicopters keep horizontal or vertical clearance during flight and
pad movements; fixed-wing aircraft wait behind ground traffic and separate vertically in flight alongside
the existing runway queue. Player-driven vehicles retain native controls.
**Land** returns to the **Home airport** by default; when several airports are available, choose an airport,
and it assigns the runway end or helipad automatically. Home is saved with the RTS vehicle state, initially
from its parking location (nearest airport as fallback), and updated after landing. A temporarily unavailable home
is retained while another landing site is used; only removal from the airport registry reassigns it.
Fixed-wing arrivals also
reserve a fitting free stand when the airport has stands. Departures use an available catapult whenever its shuttle and controller lever can provide enough
assistance, including on runways long enough for an ordinary takeoff roll.
SW catapult rails are runway accessories, listed under the runway
in the airport page. Place a shuttle on the rails and a lever beside the controller for commanded departure.
The aircraft taxis to the shuttle, attaches, triggers the native controller through redstone and resumes its climb
from the remaining runway. Catapult and ordinary departures share the runway queue. Stationary and translating-platform
catapult takeoff and fixed-wing landing have been verified. Shuttle return after a moving launch remains experimental.
Vehicles share server-authoritative selection and shortcuts **1–9** with squads. Ctrl-click appends waypoints;
clicking an earlier waypoint closes a patrol loop. Accepted routes appear in the world, and the unit window shows
the current destination, attack target and each firing seat's target and firing state. Ammunition is shown per seat
and weapon.

Colony ownership of a Superb Warfare vehicle remains after crew dismount, including automatic idle release.
Use **Release ownership** on its command card to disembark and give it up while grounded. Unowned vehicles appear
and can be claimed only while the player's client tracks them; colony-owned vehicles remain commandable at any
distance while loaded. Locked vehicles cannot be claimed or commanded.

Enable **Vehicle crew** for residents who may board. Automatic boarding recruits residents outside RTS control by
path ETA and fills only the seats required by the bound **Vehicle profile**: the driver and armed seats by default.
Each occupied firing seat selects and fires independently. Selected squads board passenger seats and keep their
original membership after **Disembark passengers**.

The Colony Book's **Vehicle profile** page edits named profiles for each vehicle type: required seats, optional crew
equipment, inventory targets and refill thresholds, energy targets and seat weapon defaults. Stationary vehicles
without an RTS order receive physical deliveries from colony stores inside the registered sites' horizontal bounding
footprint. Charging draws FE from stored batteries and returns the remainder; it does not generate energy.


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
