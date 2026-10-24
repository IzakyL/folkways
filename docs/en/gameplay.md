# Gameplay

[Documentation](README.md) · [简体中文](../zh-CN/gameplay.md)

For installation and the first five steps, see the
[project README](../../README.md#getting-started). The Colony Book's **Lessons**
tab contains Ponder tutorials; holding the ponder key over a page tab opens its
lesson too.

## Colonies and members

Colonies belong to players. The founder invites players from the **Members**
page; an invitation takes effect after the player accepts it. Only the founder
can end the colony. Click the panel's title to switch colonies or found another.
Any Colony Book opens the colony you are acting for, so losing a book does not
lose the colony.

With FTB Teams, a founder can hand a colony to their party. Party membership then
determines who keeps the colony, and the party owner holds the founder's rights.

## Work and storage

Rank each resident's trades on the **Workforce** page. The colony chooses the
tasks, supplies and order of work. Fields, pens, fisheries and patrol routes
describe where work happens; stock orders describe what a container should hold.

Each container can allow or refuse residents taking goods and using it for
ordinary storage. Orders still deliver to a container that refuses ordinary
storage. Blacklists and whitelists support items, tags and search rules through
the shared item picker. These rules apply to residents; players and external
automation retain their normal access.

## Searching

Install [Just Enough Characters (JEC)](https://www.curseforge.com/minecraft/mc-mods/just-enough-characters)
on the client to use Pinyin, initials and mixed Chinese/Pinyin input in item and
folder pickers, Workforce and stock searches. It follows JEC's keyboard and
fuzzy-spelling settings. JEC is optional and is not bundled with Folkways;
literal text searches remain available without it.

With Chinese item names and JEC's Quanpin keyboard, `tieding`, `td` and `tie锭`
find **铁锭** (iron ingot). `@minecraft tieding` limits item results to Minecraft.
Other keyboard schemes use their own spellings. Searches remain case-insensitive.

Item searches keep the existing `@mod`, `#tag`, item ID and wildcard syntax.
These terms match registry identifiers. Select an item found by name to add it
to a filter. A search saved as a filter rule uses IDs and tags; it does not use
translated names or Pinyin. Its meaning therefore does not depend on a player's
language or JEC settings.

## Trades, levels and perks

Residents learn automatically. Each completed second of active work earns one
experience point; walking on hauling and patrol duties also counts while the
resident actually moves. A trade has ten levels, reached at 3, 8, 15, 25, 40, 55,
75, 100, 130 and 160 minutes of cumulative effective work. The resident's detail
window shows their trades, levels and perks.

Every level draws a rank from that trade's five independent specialities, each
with three ranks. By level five a resident has discovered at least three
different specialities. Levels 3, 6 and 10 also draw from the shared perk pool;
shared ranks carry between trades. Fully ranked perks leave their pool, and once
every shared rank is held, further shared milestones grant nothing. With fifteen
occupational ranks but only ten draws, residents develop different combinations.

| Trade                   | Specialities (rank I / II / III defaults)                                                                                                                                                                                                                                                                       |
| ----------------------- | --------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------- |
| Building and excavation | Skilled Builder: work speed +10% / 20% / 35%; Prospector: 4% / 7% / 11% mineral finds; Lucky Digger: Fortune I / II / III on blocks broken with a tool; Quarry Momentum: up to +15% / 30% / 50% consecutive stone mining speed; Tool Expert: 15% / 30% / 45% less wear                                          |
| Farming                 | Skilled Farmer: speed +10% / 20% / 35%; Chain Harvest: 1 / 2 / 4 additional adjacent mature crops; Bountiful: +10% / 20% / 35% food yield; Replant: automatic replanting with 0% / 15% / 30% seed saving; Care: +10% / 20% / 35% growth for tended age-bearing crops                                            |
| Husbandry               | Skilled Herder: speed +10% / 20% / 35%; Butcher: +15% / 30% / 50% ordinary carcass yield; Fleece: +0.5 / 1 / 1.5 wool on average; Breeder: 5% / 10% / 20% twin births within the pasture target; Nurture: once per baby, feeding also shortens remaining growth by 10% / 20% / 30%                              |
| Fishing                 | Skilled Angler: 10% / 20% / 30% shorter waits; School: 15% / 25% / 40% chance of 1 / 1 / 2 extra fish; Treasure: 2% / 4% / 6% extra discoveries; Water Sense: increasingly favors less common local fish; Rod Care: 15% / 30% / 45% less wear                                                                   |
| Hauling                 | Heavy Hauler: capacity +25% / 50% / 100%; Fleet Courier: hauling movement +8% / 16% / 25%; Quick Exchange: 15% / 28% / 40% shorter transfer actions; Along the Way: gather 2 / 3 / 4 compatible loads sharing a source or destination; Long Haul: 15% / 30% / 45% less walking fatigue                          |
| Crafting                | Quick Hands: manual speed +15% / 30% / 50%; Batch: 2 / 3 / 4 crafts per operation; Frugal: 5% / 10% / 15% ingredient refunds on eligible recipes; Byproducts: 10% / 20% / 30% extra configured byproducts; Furnace Experience: +10% / 20% / 30% cooking speed for the batch loaded by that resident             |
| Patrol                  | Deterrence: ward radius +2 / 4 / 6 blocks and duration +15% / 30% / 50%; Martial: +10% / 20% / 35% damage against hostiles; Stalwart: 5% / 10% / 20% less damage from hostiles; Alert: detection +2 / 4 / 6 blocks and shared threats; Reassuring: +2 / 4 / 6 ward safety mood and faster recovery from attacks |

Shared perks grant reach +0.5 / 1 / 1.5 blocks, movement +5% / 10% / 15%, hunger
and fatigue consumption reduced by 10% / 20% / 30%, food, housing and weather
penalties reduced by 15% / 30% / 45%, and chatting recovery increased by
15% / 30% / 50% for both partners. Focused is a single-rank patience perk.

Some specialities have finer rules:

- **Prospector** picks minerals from the mined position's actual biome ore
  features, weighted by placement count, rarity, vein size, height distribution
  and replacement targets. Finds use ordinary ore loot without Fortune or Silk
  Touch; rank III has a 15% chance of a second portion. Placed stone and repeated
  mining at the same position are eligible.
- **Lucky Digger** lends its Fortune to the loot of blocks broken with a tool. A
  tool with higher Fortune keeps its own, the tool itself is never enchanted, and
  prospecting finds are unaffected.
- **Chain Harvest** stays inside the field and respects other active crop jobs.
  **Care** lasts for a plant's lifetime without stacking; it supports age-bearing
  crops, not saplings.
- **Along the Way** only reorders ready work whose remaining route still fits the
  pack. Shared-destination pickups allow at most eight extra blocks of estimated
  straight-line travel; obstacles can make the actual walk longer.
- **Furnace Experience** tracks the batches a resident loaded, across saves.
  Removing or replacing those inputs removes the training.

Older saves keep their levels and independent perks, with excess ranks capped and
experience moved to the new thresholds. Legacy Packmule stays effective for
existing residents but leaves the draw pool. Data packs can tune recipe refunds,
byproducts and fishing discoveries; see [Resident data](custom-content.md#resident-data).

## Rations and food lists

Residents carry three days of food by default and restock when their pack falls
below one working day. A day's ration is measured in nutrition rather than item
count, so different foods need different amounts.

The **Household** section of the **Human residents** page offers a **Food list mode** switch between whitelist and blacklist. **Pocket rations** opens the item picker, with item, tag and query rules. Switching modes keeps the same rules: a whitelist allows only matching food, and a blacklist refuses matching food. An empty whitelist allows nothing; an empty blacklist allows all safe, nourishing food. These rules govern eating, ration deliveries, pack reserves and new settlers' food requirements. Having no usable food does not waive the food requirement.

The default is a whitelist containing `#folkways:resident_food`, which combines:

- `#folkways:resident_food_vanilla`: the original 15 vanilla ration types, including cooked meat, fish, bread and soups.
- `#folkways:resident_food_cookery`: Kaleidoscope Cookery rice, noodles, cooked meat and everyday prepared meals.
- `#folkways:resident_food_farmers_delight`: Farmer's Delight cooked meat, sandwiches, soups, rice, pasta and feast portions.

Mod entries are optional and load safely without those mods installed. Defaults omit raw ingredients, harmful dishes, special-purpose food and feast blocks that must be placed first. Modpacks can extend or replace these tags with a datapack; players can also add other mods' items, tags or queries in the picker. Existing colonies keep their saved food rules in whitelist mode. To adopt the new default list, replace those rules with `#folkways:resident_food`.

The **Always refuse unsafe food** checkbox beside the food list mode is checked by default. While checked, safety checks apply even to whitelisted food. Residents skip food with any chance of a harmful status effect, including effects stored on suspicious stew. Forbidden or harmful food already in their packs is also skipped; allowed meals in later slots are still eaten. Beneficial food effects and returned bowls or bottles keep their normal behavior.

Unchecking it permits unsafe food for eating and ration planning, while still applying the whitelist or blacklist.

With safety enabled, harmful carried food does not count toward nourishment when restocking. Forbidden food never counts. Kaleidoscope Cookery's red and green chilies are also refused because eating them causes direct damage. Datapacks can add other foods with special harmful eating behavior to the `folkways:unsafe_food` item tag.

## Dining areas

With the book marking out, frame a **Dining area** around tables and the chairs
beside them, then choose its **Table food** and **Servings per tabletop block**
(1 to 4). Top slabs work as tables and bottom stairs as chairs. Leave an aisle
beside each chair. Connected tabletops on the same level form one table that
residents can serve from any reachable edge.

Residents bring one serving at a time from colony stores and keep food on
display. With several table foods selected, each empty serving slot asks for a
random dish from the menu and keeps that choice until delivery; these requests
can drive production directly. Plain tables, including top slabs, hold one
placeable food such as cake; supported Kaleidoscope Cookery tables accept edible
foods and use the servings setting. Residents preserve food components, leave
servings placed by players alone, and return unused supplies when an area is
removed.

Hungry residents prefer an available nearby table and return food containers
after eating. Small portions must fit their remaining appetite. Meals worth at
least 10 nutrition may overflow by up to 30% of their effective nutrition, capped
at 6 points, so a large dish need not wait for starvation. Finishing a table meal
grants +6 dining mood for one day. Without a suitable table meal they eat from
their pack. Cancelling a table meal leaves its food untouched and frees the seat.

## Housing and mood

Registered beds survey their room automatically. Doors separate rooms even while
open and need usable approaches on both sides. Every graded room needs an
enclosure, a roof, an entrance and a brightness of at least 8 in usable spaces.
Hold the book to see each bed's card: who sleeps there, its grade and the next
improvement.

| Housing             | Floor area per bed | Beds per room | Furnishing categories                                 | Mood |
| ------------------- | ------------------ | ------------- | ----------------------------------------------------- | ---- |
| Basic bed           | Requirements unmet | Any           | Any                                                   | −6   |
| Suitable dormitory  | 4+                 | Any           | Any                                                   | +2   |
| Comfortable housing | 6+                 | Up to 4       | 2+                                                    | +6   |
| Excellent bedroom   | 9+                 | Up to 2       | 3+, including decoration, without production stations | +10  |

Furnishing categories are storage, textiles, decoration and tables; duplicates of
one category do not count again. Crafting tables, furnaces and similar stations
count as production equipment. A resident without a bed receives −12. Room
surveys are bounded to 1,024 floor cells, 32 blocks from the bed and a roof within
12 blocks above; unloaded or oversized rooms wait for inspection. Block edits
refresh nearby surveys, and periodic inspection catches command and machine
changes.

Human residents have a mood from 0 to 100. Food, housing, rest, recreation,
company, safety and surroundings set its target, and mood moves gradually toward
it. Work speed ranges from 75% at mood 0, through 100% at 60, to 115% at 100. The
resident's detail window lists every contribution; the overhead card keeps the
summary beside fullness and rest.

## Recreation and visiting animals

Idle residents stroll, look around, chat in pairs, relax among flowers, or pet a
colony member's tame cat or wolf. Activities restore recreation as they happen,
with diminishing returns for repetition. Work interrupts ordinary recreation, but
a resident deprived of it may take a short break between jobs. Petting keeps the
owner's sit command and never takes over the pet's navigation.

As a colony grows, cats, wolves and parrots can settle nearby. In each dimension,
three human residents with suitable beds unlock cats, six unlock wolves and ten
unlock parrots. The colony supports one visitor per three housed residents across
all dimensions. Every half Minecraft day there is a 50% chance of one arrival near
suitable housing, while residents are loaded and mob spawning is enabled. Arrivals
need loaded, lit, dry, unobstructed ground away from hostiles. The animals arrive
untamed, can be adopted normally and persist like named animals. Reloading or
unloading chunks does not produce extra visitors. Death or removal frees a place;
a shrinking population pauses arrivals without removing existing animals.

## Composting

Register an ordinary composter with the Colony Book. On the colony's
**Composting settings** page, enable or disable automatic composting, choose a
default seed reserve, and override reserves for individual planting materials.
The default reserve is **64**. These settings belong to the colony, not the book.

Only surplus seeds are available for composting. Sowing, reserved inputs and
stock orders remain protected. Native composters work with Core and Vanilla;
neither Cookery nor a straw hat is required. When the Cookery extension is
installed, its planting materials participate in the same reserve system.

The creative supply box also belongs to Vanilla and works without Cookery.

## Buildings

Frame ground or draw a line, then choose a pattern. Builders clear the site and
use the colony's stores to construct roads, arch bridges, walls, docks, mines,
railways, arcades and terraced fields. Whole structures can also be placed from
the **Blueprints** page; Create schematics require Folkways × Create.

To add your own patterns and structures, see [Custom content](custom-content.md).

## Elevators

With Folkways × Create, residents ride Create elevators. Build and power the
elevator as usual: a glued cabin under an elevator pulley, a moving redstone
contact and matching elevator contacts at each floor, with walkable landings
beside the cabin. With the book pointing, left click the pulley to take it into
the colony. Residents then plan routes between its floors, wait on the landing,
walk into a free standing place and walk out at their stop.

Capacity comes from the clear connected deck, with room left for passing; seats
do not add capacity. A full cabin leaves the next resident waiting for a later
trip. A stopped or overstressed drive cannot carry anyone.

## Kaleidoscope Tavern

With Kaleidoscope Tavern installed alongside Folkways × Kaleidoscope Cookery,
residents grow grapes, make wine and serve it in dining areas.

- **Vineyards.** Select grapevines as a farm's crop and supply four grapevines
  plus trellises: four per corner post and one per plot cell. Residents raise a
  post at each corner and a pergola over the whole plot five blocks up, one
  trellis at a time, plant a vine at the foot of each finished post, and harvest
  the grapes hanging under the roof while the vines regrow. Tree clearing also
  collects attached wild grapevines.
- **Wine.** Register a pressing tub and a barrel with a tap as kitchen stations
  and order wine. Residents press grapes in the tub, fill the barrel from its top,
  wait for its second brewing level, then set out, fill and collect bottles one at
  a time. A cancelled bottling job resumes from the bottle left under the tap.
- **Bar drinks.** Choose a dining area's **Bar drinks** and place bar counters
  and adjacent chairs inside it. Each counter holds two bottles, keeping their
  quality across saves. Residents stock counters from storage or production and
  visit them for a seated drink independently of hunger; diners can also fetch a
  bottle after a table meal. Tables hold only food. Drinking applies the wine's
  own effects, returns the empty bottle and grants +4 mood for one day without
  adding nutrition. Breaking a counter returns its bottles.

## Maid model packs

Touhou Little Maid's built-in and installed add-on models can become resident
looks, individually or by pack. These looks require GeckoLib; without it they
are disabled with a dependency tooltip.

Place ZIP files or pack folders directly under `<gamedir>/tlm_custom_pack/` on
the server and each client. ZIP files do not need extracting. Each client reads
its own installed packs; pack assets are not sent over the network.

After changing packs, run `/reload` on the server and reload client resources
with F3+T, or restart the game. Files in `config/touhou_little_maid/file/` are
download caches and do not count as installed packs.
