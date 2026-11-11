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
| [GeckoLib](https://modrinth.com/mod/geckolib) 4.7.5+ | Vanilla, *client only* | Bedrock-format resident models |
| [Touhou Little Maid](https://modrinth.com/mod/touhou-little-maid) 1.5+ | Vanilla | Maid models as resident looks |
| [FTB Teams](https://www.curseforge.com/minecraft/mc-mods/ftb-teams-forge) 2101.1+ | Core | Hand a colony to a party |
| [JEI](https://modrinth.com/mod/jei) / [EMI](https://modrinth.com/mod/emi) | Core, *client only* | Drag items into Folkways filters |
| [Just Enough Characters](https://www.curseforge.com/minecraft/mc-mods/just-enough-characters) 4.5+ | Core, *client only* | Pinyin search |
| [Shoulder Surfing Reloaded](https://modrinth.com/mod/shoulder-surfing-reloaded) 5.0.1+ | Core, *client only* | Colony Book aims along its crosshair |
| [AppleSkin](https://modrinth.com/mod/appleskin) 3.0+ | Core, *client only* | Its overlays hide in the View from above |
| [Iris](https://modrinth.com/mod/iris) | Core, *client only* | Zone and path overlays cast no shader shadows |

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

Add a supported gun to a **Loadout profile**, assign residents and keep matching ammunition in
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
on Epic Fight's skeleton when GeckoLib is installed. Use loadouts and patrols as usual. Residents
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
