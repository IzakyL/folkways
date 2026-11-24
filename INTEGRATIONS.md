# Integrations

English · [简体中文](INTEGRATIONS.zh-CN.md)

Folkways works alongside a number of other mods. This page explains what each one adds to a
colony, how to start using it and what to expect. Install **Core and Vanilla** first; all Folkways
jars must come from the same build. Unless a row says *client only*, install the mod on both the
server and every client.

## At a glance

| Mod | Folkways jar | Adds |
|---|---|---|
| [Create](https://modrinth.com/mod/create) [6.0.7,7.0) | Core / Vanilla | Schematic building, trains, elevators, rail laying, ordering through package networks, fan stream readings for facilities |
| [Create: FireFighting Additions](https://modrinth.com/mod/create-firefighting-additions) 0.2.3-beta+ | Core / Vanilla | Extinguishers and hydrant hoses for damage control |
| [Create Big Cannons](https://modrinth.com/mod/create-big-cannons) 5.11.7 | Core | Facility controls and readings for cannon mounts, autocannons and drop mortars |
| [Sable](https://modrinth.com/mod/sable) | Core | Residents live and work aboard sub-levels such as Create Aeronautics ships |
| [Let it Walk!](https://www.curseforge.com/minecraft/mc-mods/let-it-walk) [0.1.5,0.2) | Core | Walkers as facilities that residents drive to a picked spot |
| Sable 2.0.6 / Create [6.0.10,6.1) | Folkways Airline (required) | Airport patterns, surveys, towers and moving landing facilities |
| [AstikorCarts Redux](https://modrinth.com/mod/astikorcarts-redux) 1.2.2+ | Core + Vanilla | Cart lines between stations |
| [Chisels & Bits](https://www.curseforge.com/minecraft/mc-mods/chisels-bits) 21.1.33 | Core | Carved builds and voxel-detail patterns |
| [Kaleidoscope Cookery](https://github.com/KaleidoscopeMods/KaleidoscopeCookery) 1.6.0+ | Core + Vanilla | A working colony kitchen, rice paddies and seed gathering |
| [Kaleidoscope Tavern](https://github.com/KaleidoscopeMods/KaleidoscopeTavern) 1.2.0+ | Core + Vanilla | Vineyards, winemaking and bar counters |
| [Modular Golems](https://modrinth.com/mod/modular-golems) 3.1.43 | Core | Golems join the colony as workers |
| [TACZ](https://modrinth.com/mod/timeless-and-classics-zero) 1.1.8+ (including 1.2.0) | Core + Vanilla | Residents use guns with real ammunition |
| [Superb Warfare](https://modrinth.com/mod/superb-warfare) [0.8.9.2,0.8.9.3) and Kotlin for Forge 5.x | Core + Vanilla | Residents use guns and crew emplacements |
| [Epic Fight](https://modrinth.com/mod/epic-fight) 21.17.3.1 | Core | Native Epic Fight melee, reactions and animations |
| [Sophisticated Storage](https://modrinth.com/mod/sophisticated-storage) 1.6.17+ | Core | Its containers as stores; crafting and stonecutting upgrades double as workstations |
| [Curios](https://modrinth.com/mod/curios) 9.3.1+ / [Accessories](https://modrinth.com/mod/accessories) 1.1.0-beta.54+ | Core | Accessory slots in equipment profiles, worn with their real effects |
| [Sophisticated Backpacks](https://modrinth.com/mod/sophisticated-backpacks) 3.26.21+ | Core | Placed backpacks as stores; worn backpacks add cargo room, upgrades and music to residents |
| [Storage Drawers](https://modrinth.com/mod/storagedrawers) 13.11.1+ | Core | Drawers as stores, reached directly or through controllers |
| [Functional Storage](https://modrinth.com/mod/functional-storage) 1.5.7+ | Core | Drawers as stores, reached directly or through controllers |
| [Create: Storage (ausmez)](https://modrinth.com/mod/create-storage-neo-forge) 1.3.5+, Create 6.0.7+ | Core | Storage boxes and placed backpacks as stores; controllers for putting goods away |
| [Tom’s Simple Storage](https://modrinth.com/mod/toms-storage) 2.0.8–2.x | Core / Vanilla | A connected inventory network reached through connectors and terminals; ordering from the colony at a terminal |
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

Core enables Modular Golems, Epic Fight and Chisels & Bits for the verified versions in the table.
Folkways Vanilla adds Epic Fight combat and rendering for human residents when Core enables the integration.
Set `modulargolems`, `epicfight`, `chiselsandbits`, `kaleidoscope_cookery`, `kaleidoscope_tavern` or
`astikorcartsredux` to `false` in `config/folkways_core-integrations.toml`
and restart to disable an integration. Core logs a warning when it detects an unsupported target version.

Sophisticated Storage and Backpacks also need Sophisticated Core 1.5.33+; Functional Storage needs
Titanium 4.0.52+. AE2, Refined Storage and Tom’s Simple Storage are supported up to, but not including,
their next major version; the other storage mods have no upper bound.

Sneak and scroll to turn the Colony Book to a gesture: taking in registers or releases what you left-click,
marking out frames a box and drawing draws a line; right-click a finished shape to choose the area or route it
becomes. Where a **lesson** is mentioned, find it in the colony panel's **Lessons** tab.

## Building and transport

### Create

Core keeps residents from walking into moving contraptions. With Create installed, Core and Vanilla enable the following features.

- **Schematics.** Place a schematic with Create's tools while holding the Colony Book in the
  other hand, then choose **Hand Off**. Decide whether to keep or dig out whatever stands in the
  blueprint's empty cells. Builders work through it like any other build order; nothing appears
  instantly, even in creative.
- **Trains.** With the book in the off hand, use a Train Schedule on the driver's seat of a train
  without one. Residents with the **Drive** trade run it, and other residents ride it as
  passengers. The conductor stands duty in the native cab for the ride. The train needs at least two stops, each a single named station, and only
  time-based wait conditions. Use an empty hand on the seat to take the train back. Facility control
  discovery can show item ports for loaded freight carriages. Each delegated train with a loaded carriage appears
  once in colony facilities, with its loaded seats and freight ports. `move_to` accepts a station name or picks the
  nearest station on the train's graph to the requested coordinates. An idle train can take a native destination,
  and a scheduled train can jump to a matching stop. An off-schedule destination pauses the existing
  schedule so the train can make the requested trip.
- **Elevators.** Build a powered Create elevator, then left-click its pulley with **Take in** picked.
  Residents walk in, ride and walk out. Capacity comes from the clear floor area, and landings
  must be clear and walkable.
- **Rail laying.** Draw a line with **Pattern** picked and choose the **Railway** pattern. Residents grade the route,
  tunnel through hills, bridge rivers and lay real, connected Create track. Runs must be straight
  or 45° diagonal, with gentle grades and bends of 90° or less.
- **Ordering through a package network.** With **Take in** picked, left-click a Stock Ticker or stock
  link to take its network on, and frogports or postboxes to use them for delivery. A resident
  with the **Dispatch** trade staffs the desk beside the Stock Ticker, enabling network orders. The **Dispatch**
  page lists anything that stops the network from being used. Registered stores on the network contribute
  ordinary colony stock. Dispatch reserves goods from their source stores for packaging, and parcels in
  transit are collected by their assigned orders.
- **Cranks and valve handles.** A Hand Crank or Valve Handle can be a facility control. A resident stands at it and
  turns it a set number of uses, forward or backward, so a routine can open a door or swing a bearing that the
  handle drives. A valve handle will not turn while another source drives its network.
- **Control hubs.** Craft an **Anonymous Typewriter** with paper and an iron ingot. Hold it and right-click
  empty-frequency Redstone Link receivers, then place it to bind the converted **Control Terminals**. Put a
  Create seat against the marked face. A facility run can call a resident to that seat to operate the terminals.
  Vacant terminals return to level 0, emitted as strength 15 beside an Adjustable Chain Gearshift or Analog
  Transmission. Breaking the typewriter restores its receivers.
- **Fan streams.** A facility can read what an encased fan's stream does at a depot or belt in its line and how
  many items held there it can still change, so a facility built around a fan can work with it.

Lessons: Rail, Dispatch, Elevators and Handing a Create schematic to the colony.

### Create: FireFighting Additions

Damage-control workers prefer a hydrant cabinet supplied with a hose, nozzle and water;
otherwise they borrow an extinguisher. They refill extinguishers from water sources in the zone,
fluid tanks or water stored in the colony. Turn on **Borrow and refill firefighting tools** in the
damage-control zone's settings. Only water is used.

### Create Big Cannons

Core supports CBC 5.11.7 for NeoForge 1.21.1, with Create 6.0.7 through 6.0.x and
Ritchie’s Projectile Library 2.1.2. Residents work a regular Cannon Mount as a player does by hand. A
horizontal big cannon offers `aim`, a `fire` pulse and a `breech` port on its quick-firing breech; an autocannon
offers `aim`, a `fire` level for its cadence and an `ammo` port for its Autocannon Ammo Container; a drop mortar
offers `aim` and an `ammo` port at its muzzle. Attach a Create Hand Crank to each axis a resident needs to
aim. Residents drive the shafts, and CBC limits the final rotation to the requested angle. Heavy guns may need
reduction gearing to stay within the cranks' stress capacity. Through the breech, residents
load a projectile and then one charge at a time, ramming the column deeper as a Ram Rod does, up to CBC's safe
propellant stress for the cannon. Every quick-firing breech is rated for two Powder Charges unless a datapack raises
it. A load beyond that is refused with its reason. Any big cannon with an open muzzle also offers a `muzzle` port and a
`ram` pulse: a resident pushes the charges and then the projectile into the muzzle and rams each down with Ram Rod
strokes, following CBC's reach, strength and cooldown. A filled Big Cartridge is requested with its power; after the
shot the spent case is ejected when the breech next opens for loading. The aim part reports the cannon's geometry,
bore and native munition figures, and the optional `@std//ballistics` module lays the cannon from them, loads the
listed projectile and charges in order through the breech or muzzle and fires; the predicted flight counts every
charge and a cartridge's power. Sliding and screw breeches and the Cannon Loader are worked through the player's own
levers, clutches and gearshifts, or loaded at the muzzle. A routine that publishes an engagement can
take assignments from colony fire control, which predicts the trajectory and blast to avoid colony entities and blocks.
Player-chosen shots skip this prediction. Same-colony entities and member players cannot damage one another, and blasts
follow CBC's native rules. A GameTest verifies that a resident
aboard a stationary, rotated Sable sub-level can load the cannon and fire it with `@std//ballistics` at a
world-space target. GameTests also cover a three-charge load, a refused overload, a muzzle load, two Big Cartridge
shots in a row, fire control engaging a hostile, an autocannon container and a drop mortar round. A resident can also load and fire a cannon on a stationary walker, drawing
ammunition from an enrolled deck store within the hull extent. Set
`createbigcannons = false` in `config/folkways_core-integrations.toml` and restart to disable the adapter.

### Sable

Residents walk onto and off ships and other sub-levels, keep working aboard while they move, and
registered blocks travel with the ship. Residents can teleport between safe footholds within four blocks
when crossing between structures or between a structure and the shore, even while the structures are moving.
Projected gates on a deck offer walking routes only while their opening conditions hold.

### Let it Walk!

Core supports Let it Walk! 0.1.5 through 0.1.x on Sable 2.0.6 and Create 6.0.10. A walker's hull is
a Sable sub-level, so residents stand, walk about and sit in Create seats on its deck while it walks.
With the colony assistant, name a walker as a facility over its hull: its Pilot Controls expose `destination` and its
Walker Core exposes `park`. A walker can reposition itself between passes when sweeping a picked box. Select the walker in Control mode and right-click the
ground. A resident beside the Pilot Controls drives it within about one block of the destination. The walker
keeps its heading for nearby moves and turns toward distant destinations before walking. Driving needs a turning
core and an unparked walker. The crew must already be aboard, since
residents cannot climb onto a hull standing on its legs, and a walk cannot be redirected until it
ends. The Pilot Controls also expose each action key that has a frequency set: a routine can press it once, as a
pilot's click would, to power whatever the key is linked to. The controls also expose native height, lean and
numbered limb inputs; a limb's reading gives where its end stands, in the world and in the hull's own space. Jumping, flight and the Remote Controller are left to players. Set `let_it_walk = false`
in `config/folkways_core-integrations.toml` and restart to disable the adapter.

### Airports (Folkways Airline)

Airline provides airport drawing patterns, surveys, towers, runway and helipad geometry, taxiways and stands.
Sable supports moving airport frames. Core controls Superb Warfare vehicles using the shared landing-site geometry.
Airline requires Create 6.0.10 through 6.0.x and Sable 2.0.6. A claimed tower is staffed while a resident is on duty in its seat.

Runways and taxiway patterns accept any level heading. Runway numbers follow the landing heading rounded
to ten degrees (north is 36); parallel runways receive L/C/R designators, mirrored at the opposite end.

Runways are delimited by continuous white edge lines and piano-key threshold stripes at both ends, including on
oblique headings. The drawing adds long centreline dashes and paired aiming points. Aprons are continuous gray
pavement: a yellow lead-in ending at a three-block white stop line identifies each stand. Clearance to pavement
boundaries and neighbouring stands determines parking dimensions. Connected pavement is
taxiable without a yellow centreline; routes prefer guide lines and central clearance, and admit aircraft by the
minimum boundary clearance along the route. Unreachable stands or runways are reported. Helipads use a white
border and slender white H (width about 60% of height), with an optional yellow touchdown circle. All facilities
can share continuous pavement; grass gaps are unnecessary.

Airport bookings queue Superb Warfare combat departures (including catapults) and landings in request order. Without Airline, Vanilla keeps a local runway queue. Bookings are not saved.

### AstikorCarts Redux

A loaded cart held by the colony or hitched to a colony resident or animal appears as a facility. Its card moves
the hitched mob to a ground position. The `deposit` routine loads dropped items within three blocks of its
cargo port. Cart lines continue to use their own dispatch and can issue their next navigation order to the puller.

Core exposes each cargo cart's inventory as an item port in facility control discovery. Vanilla's cart lines use
that port to load and unload at stations.

A plow facility also exposes `plowing/<uuid>` to engage its blades and read their state, and `tools/<uuid>`
to load and unload its three native slots. A routine can engage the plow, use its destination part to move
the hitched puller, then disengage it. AstikorCarts applies the installed tools and their durability rules.
Players can toggle plowing with a right-click and open the slots with a sneak-right-click.


Draw a line in the colony and choose **Cart line**. Each point is a station, up to 16. Pick the
cart type and whether it runs **Out and back** or as a **Loop**. The colony makes or takes a cart and
sets it at the first station. Hauling residents pull hand carts. Supply carts need a tamed adult horse
from a pasture. The cart stops at every station to load and unload colony deliveries. Routes need wide
gateways and room at corners; jumps, drops and climbs are refused. Carts do not ride trains or elevators.

Lesson: Cart lines: stations and laps.

### Chisels & Bits

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
  inventory proxies and automation are refused. With Vanilla, a registered storage or crafting terminal shows an
  **Craftable** button. It switches the terminal grid to items the colony can make. Click an item, set the amount
  and press **Order** to return to the terminal. **Order…** opens order management and also lists goods the colony
  can fetch from other stores. The request is an ordinary once order into one of the network's containers, so it
  also appears under **Stock & orders**. The panel lists this network's requests as **Waiting**, **In progress**,
  **Stalled** (with the reason) or **Delivered**, and each pending request can be cancelled there.
- **Colossal Chests:** a complete chest's core, walls and interfaces are one store; unfinished structures
  are refused.
- **Mekanism QIO:** register a QIO Dashboard. It and every drive of its frequency must be in one dimension.
  Drive slots and automation give no item access, and QIO crafting is not used. The
  `folkways_core:network_access/take` and `put` block tags limit what dashboards may do.

### Applied Energistics 2 and Refined Storage 2

Core provides both directions of the AE2 and RS2 integrations, including the **Network service** vocation.

Register ME terminals, crafting terminals or ME chests (AE2), or Grids, Crafting Grids, Pattern Grids and
item Storage Monitors (RS2), with the Colony Book; every AE2/RS2 network device on one network shares
one pool of ordinary colony stock. Interfaces are not stores. Residents can order network autocrafting at
an AE2/RS2 network device. The result is held for the order until the assigned resident collects it.
Containers attached by an AE2 Storage Bus or RS2 External Storage, including members reached
through a storage controller, belong to that network's container facility. An AE2 Storage Bus on an unconfigured
ME Interface counts the inner network's storage in the outer network, and mutually connected ME
networks count as one. RS2 Interfaces expose their export buffers.

To let the network use colony Workshop recipes, enable the **Network service** vocation (off by default):
residents collect the inputs, process them at workshops and return the products. Accepted work survives
reloads and is finished even if the network order is canceled; work that cannot continue returns its inputs
to the network. A per-player terminal button shows or hides these colony-only craftable entries.

Open a registered AE2/RS2 device from **Facilities**, or follow the link on its held storage block’s
card. Its controls govern storing, taking, network crafting orders, commissions and the **Item filter**.
Under **Whole network**, set how many crafting CPUs colony orders may use (default half). Refined
Storage allows or stops the colony’s single task. Modpacks set access limits with the
`folkways_core:network_access/take`, `put` and `craft` block tags. Pending network work appears under
**Stock & orders**.

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

Core provides the native cookware storage ports. Vanilla provides kitchen and farming gameplay.

Register appliances as **Kitchen stations** and keep the **Kitchen** page in view. Order the
finished dish you want; residents with the **Craft** trade plan every step in between, from
milling flour to cooking. Residents also farm flooded rice plots, gather grass seeds, compost
surplus and share kitchen tools and farm clothing, returning each after the task. Heat sources must be
lit and sheltered. Stir-fries burn if left too long.

Lessons: thirteen lessons in the **Kitchen** folder.

### Kaleidoscope Tavern

Requires Kaleidoscope Cookery. Residents raise pergolas on grape plots, then press, ferment and
bottle wine. Register bar counters inside a dining area and choose **Bar drinks**; residents
stock them and diners drink there. Barrels need a way onto their top, and the colony needs
buckets and empty bottles in store.

Lesson: Tavern: from the vineyard to a glass of wine.

## Residents and defense

### Modular Golems

With **Take in** picked, left-click a golem you own to admit it, then click again to release it.
Golems take trades by kind. Metal golems haul, build and patrol. Dog golems haul and patrol.
Humanoid golems can also craft, drive trains and dispatch. Golems never eat or sleep, and their
skills do not grow. They patch themselves up with repair items from their pack.

Lesson: Golems: put your Modular Golems to work.

### TACZ and Superb Warfare (Core and Vanilla)

Superb Warfare support is limited to **[0.8.9.2,0.8.9.3)**, the verified 0.8.9.2 version line.
Core and Vanilla disable the integration when the installed version is outside that range.
Both integrations can be disabled in `folkways_core-integrations.toml`.

Put a supported gun in an **Equipment profile**'s inventory list, assign residents and keep matching ammunition in
colony stores. Then staff patrol routes or sentry posts, or command residents directly. Residents
load only real rounds from their pack and fire at the gun's own pace. Unloaded TACZ rounds go back to the pack.
Same-colony entities and member players cannot damage one another. Automatic fire predicts the path and blast to
avoid colony entities and blocks. Player-chosen shots skip aim prediction, and explosions follow the mod's native rules.

- **TACZ:** default `tacz:` guns are supported. Explosive, incendiary and infinite ammunition is not.
  Servers can adjust allowed guns and range in `folkways_core-tacz-server.toml`.
  The old `protectColonyAllies` setting has been removed. Core always cancels friendly damage between
  entities and member players of the same colony, including damage from TACZ.
- **Superb Warfare:** Glock 17, MP5, AK-47, M4, M870 and SVD; emplacements MK-42, BL-132, MLE-1934,
  FH-77BW, mortar and HPJ-11, with plain high-explosive or mortar shells. Point at an emplacement
  and choose **Independent weapon station**, or place it inside a sentry post. The operator sits in a turret's
  gunner seat; the turret fires from its own container, and when it runs dry the operator puts in a carried shell
  for the gun to reload, so a player can also stock the gun directly. A mortar is worked from beside it: the operator
  drops a carried shell in the tube and the mortar fires it by itself. An assistant facility can also work an
  emplacement: its `aim` part reports the muzzle, aim limits, selected shell, range, blast and native flight, and the
  optional `@std//ballistics` module lays the gun from the gunner's seat, supplies its container and fires.

With Iris shaders enabled, Core uses per-vertex rendering for SimpleBedrockModel triangle meshes to avoid
corrupt vehicle geometry in Iris 1.8's bulk conversion. Vehicle models and shadows remain enabled.

Colony-owned Superb Warfare vehicles register as facilities while loaded. The assistant can call `move_to` for a
crewed mobile hull. In Control mode their command card keeps the airframe's orders, crew controls and profile picker. Resident crew assignments and colony ownership
survive reloads; leaving releases the residents back to their ordinary work. Flight orders and deck-local parking
anchors also survive reloads; interrupted catapult departures stop safely before a new departure order.
Crew can leave a supported hull at rest on pavement or a moving deck. The unit command API exposes seats,
weapons, firing rules, routes and receipts separately from the interface windows. Ground vehicles, helicopters and
fixed-wing aircraft plan velocity and attitude at the native engine boundary, retaining SW collision and energy
failure behavior. Airline, when installed, supplies helipads and runway ends.
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
and it assigns the runway end or helipad automatically. Home is saved with the vehicle's Control state, initially
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

Enable **Vehicle crew** for residents who may board. Automatic boarding recruits residents outside Control mode by
path ETA and fills only the seats required by the bound **Vehicle profile**: the driver and armed seats by default.
Each staffed firing seat selects targets and fires independently under its weapon rules. Selected squads board passenger seats and keep their
original membership after **Disembark passengers**.

The Colony Book's **Vehicle profile** page edits named profiles for each vehicle type: required seats, optional crew
equipment, inventory targets and refill thresholds, energy targets and seat weapon defaults. Stationary vehicles
without a Control order receive physical deliveries from colony stores inside the registered sites' horizontal bounding
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

## Colony assistant

With Folkways Assistant installed on both client and server, use the assistant icon on a colony screen,
or bind **Open colony assistant** in Controls. Pick a provider entry under **Settings**: DeepSeek,
Anthropic, Google Gemini, or an OpenAI-compatible Chat Completions endpoint.
Set an API key for endpoints that require one. You can save several entries and select the one to use.
Then ask things like "Why is Anna idle?" or "What is the colony short of?"

The agent runs on your client. Your questions and the colony data its tools read
go to the provider you configured. The game server processes `run` requests and any
conversation-history text those scripts fetch. Credentials stay on the client. Colony reads check
membership, and changes such as renames or path commissions wait for your confirmation by default.
**Auto-approve: General settings** can approve ordinary colony settings changes. Facility definitions, facility
parameters and colony script writes always require your explicit confirmation.
Image input is on by default for every entry. Successful scripts can capture nearby views on the client, up to
six per run, and send the images to the model. Turn it off for a text-only model; a saved choice is kept.
Settings are kept in `config/folkways-assistant.json` and keys in
`config/jpi-mini/credentials.json`.

Conversations are saved on the client under `folkways/assistant/sessions/`, per world or server and per player:
up to 512 KiB each, the latest 20 per folder, and removed after 90 days unused.

The assistant designates facilities from the controls it discovers and your hints, mostly by combining built-in
routines. When those fall short it writes Starlark routines or trigger conditions, which run on the server
within step, time and memory limits and may only write controls and read. Saved facilities work for every member, with or without the assistant.

For a player-built machine, such as a door raised by a valve handle, a cannon or a walker, the assistant first reads how
the machine is connected: power sources, shafts and gears with their ratios, clutches, actuators and the controls that
reach them. It then tries candidate controls and observes how the mechanism responds before writing the facility.
Trials can test a walk or aim a cannon mounted on a ship without moving the actual machine or firing a shot.
Trials exclude Create rails, stations and parcel logistics. The assistant runs one trial at a time, usually lasting a
few seconds. In the world, the parts the assistant reads are outlined, the control it tries is ringed,
and the observed movement is played back over the machine.

A player-defined facility can offer rides between stops so residents can travel by a custom lift, platform, or ferry.

A facility can declare duty posts at a footing or native seat. A post covers signals and ports in reach
and any additional named controls. Integrations can extend that coverage. Staffed posts perform signal
writes and item transfers between covered ports and the resident's pack, including timed sequences.
Ordinary hauling brings distant stock to the post before the resident loads it. A sequence whose controls
share a stance can also be performed by one resident after arrival.

The assistant reads facility definitions at `facilities/<id>/definition`. Script routines use `{source, with}` and can
load colony libraries from `//scripts/<name>` and standard modules from `@std//<module>`. Cancelling an active script run requires batch approval.
