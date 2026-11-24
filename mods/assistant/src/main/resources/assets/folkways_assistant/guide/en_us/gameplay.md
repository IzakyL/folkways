# Gameplay

The Colony Book opens to **Overview**,
where you can see what needs attention. **Workforce** covers residents, **Stock & orders** covers
supplies and orders, and **Facilities** lists what the colony operates. Filter by kind and select a
facility to open its card. **Settings** groups colony-wide settings cards under **Colony**, **Industries**
and **Trade tools**, followed by **Your preferences** and **Advanced**. **Lessons** contains Ponder tutorials.

Sneak and scroll to choose a kind on the book toolbar. Use **Take in** to register individual blocks.
For a zone, left-click opposite corners. For a path, left-click points in order. Right-click to finish
the drawing and open its card. With the book in hand, right-click a held zone, path or block to reopen
its card and change its settings.

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
tasks, supplies and order of work. Fields, pens, apiaries, fisheries and patrol routes
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

If residents stop working, enable **Debug logs** under **Advanced** on the Colony Book's
**Settings** page. While it is on, look at a resident or registered block to show its debug card.
Every ten seconds, your chat shows the current planning duration, idle
residents, task counts, recent blockers and whether the route optimizer used its
fallback. Blockers and task samples describe the last completed schedule; they can
be older than the live resident counts while planning is busy. Only the player
who enabled the option receives these messages. Closing the book keeps it enabled;
disconnecting disables it.

With the same option enabled, task interruptions, work and path failures, planning
exceptions and optimizer fallbacks appear as they happen. Work messages identify
the resident, task, position and target; pickup messages also name the dropped
item and its entity ID. Interruptions name the preempting behavior and the observed
combat target and recent damage source. These are clues, not proof of what triggered
the behavior. Events cover the colony across dimensions. Repeats are grouped for
five seconds and bursts are limited to four messages per two seconds; queued
occurrences retain their counts, with an overflow summary for larger bursts.
Turning the option off discards that reader's pending messages.

Route optimization has a work budget and, in the normal planning mode, a
100-millisecond deadline. If either is exhausted, the scheduler restores and
publishes its initial valid assignment so residents can work while further
improvement waits for another schedule.

## Pastures

The target is the number of grown animals to retain. Residents breed ready adult
pairs while the total is below or at the target. At the target, one additional
birth is allowed; feeding waits while surplus young grow. Only adults above the
retained count are culled, so a newborn does not replace a breeding adult.

For example, retain two cows: two adults breed, their calf grows, then one of
the three adults is culled and the cycle can repeat after the normal breeding
cooldown. A target of zero disables breeding and culls all adults. One animal
has no breeding partner; keep at least two for continued meat production.

## Trades, levels and perks

Residents learn automatically. Each completed second of active work earns one
experience point; walking on hauling and patrol duties also counts while the
resident actually moves. A trade has ten levels, reached at 3, 8, 15, 25, 40, 55,
75, 100, 130 and 160 minutes of cumulative effective work. The resident's detail
window shows their trades, levels and perks.

In the seven trades below, every level draws a rank from that trade's five independent specialities, each
with three ranks. By level five a resident has discovered at least three
different specialities. Levels 3, 6 and 10 also draw from the shared perk pool;
shared ranks carry between trades. Fully ranked perks leave their pool, and once
every shared rank is held, further shared milestones grant nothing. With fifteen
occupational ranks but only ten draws, residents develop different combinations.

| Trade                   | Specialities (rank I / II / III defaults)                                                                                                                                                                                                                                                                       |
| ----------------------- | --------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------- |
| Building and excavation | Skilled Builder: work speed +10% / 20% / 35%; Prospector: 4% / 7% / 11% mineral finds; Lucky Digger: Fortune I / II / III on blocks broken with a tool; Quarry Momentum: up to +15% / 30% / 50% consecutive stone mining speed; Tool Expert: 15% / 30% / 45% less wear                                          |
| Farming                 | Skilled Farmer: speed +10% / 20% / 35%; Chain Harvest: 1 / 2 / 4 additional adjacent mature crops; Bountiful: +10% / 20% / 35% food yield; Replant: automatic replanting with 0% / 15% / 30% seed saving; Crop Care: +10% / 20% / 35% growth for tended age-bearing crops                                            |
| Husbandry               | Skilled Herder: speed +10% / 20% / 35%; Resourceful Butcher: +15% / 30% / 50% ordinary carcass yield; Rich Fleece: +0.5 / 1 / 1.5 wool on average; Expert Breeder: 5% / 10% / 20% twin births within the pasture target; Nurturing: once per baby, feeding also shortens remaining growth by 10% / 20% / 30%                              |
| Fishing                 | Skilled Angler: 10% / 20% / 30% shorter waits; Fish Schools: 15% / 25% / 40% chance of 1 / 1 / 2 extra fish; Treasure Hunter: 2% / 4% / 6% extra discoveries; Water Sense: increasingly favors less common local fish; Rod Care: 15% / 30% / 45% less wear                                                                   |
| Hauling                 | Heavy Hauler: capacity +25% / 50% / 100%; Fleet Courier: hauling movement +8% / 16% / 25%; Quick Exchange: 15% / 28% / 40% shorter transfer actions; Along the Way: gather 2 / 3 / 4 compatible loads sharing a source or destination; Long Haul: 15% / 30% / 45% less walking fatigue                          |
| Crafting                | Nimble Hands: manual speed +15% / 30% / 50%; Batch Crafting: 2 / 3 / 4 crafts per operation; Economical: 5% / 10% / 15% ingredient refunds on eligible recipes; Useful Offcuts: 10% / 20% / 30% chance of the configured byproduct; Furnace Expertise: +10% / 20% / 30% cooking speed for the batch loaded by that resident             |
| Patrol                  | Deterrence: ward radius +2 / 4 / 6 blocks and duration +15% / 30% / 50%; Martial Skill: +10% / 20% / 35% damage against hostiles; Stalwart: 5% / 10% / 20% less damage from hostiles; Vigilance: detection +2 / 4 / 6 blocks and shared threats; Reassurance: +2 / 4 / 6 ward safety mood and faster recovery from attacks |

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
  **Crop Care** lasts for a plant's lifetime without stacking; it supports age-bearing
  crops, not saplings.
- **Along the Way** only reorders ready work whose remaining route still fits the
  pack. Shared-destination pickups allow at most eight extra blocks of estimated
  straight-line travel; obstacles can make the actual walk longer.
- **Furnace Expertise** tracks the batches a resident loaded, across saves.
  Removing or replacing those inputs removes the training.

Saves restore stored experience and levels and cap recognized perk ranks. Data packs can tune recipe refunds,
byproducts with `crafting_perks/<recipe path>.json` data files, and fishing discoveries with the
`folkways_vanilla:gameplay/fishing_discoveries` loot table.

## Combat equipment

Same-colony residents, pets, owned entities and member players cannot damage one another. Automatic shots avoid
colony entities and blocks along the predicted path and within the blast area. Player-chosen attacks skip aim
prediction. Explosions follow vanilla or the installed mod's rules.

Open the sentry zone or patrol route's card. Choose **Allowed profiles**
in the multi-select popup and enter one total **Headcount**. Idle residents with any selected
assigned profile fill it automatically; zero headcount disables it. Sentries still need ranged
profiles and safe standing capacity. The card shows staffed/desired counts and shortage reasons;
a blocked route has **Retry route**. Valid incumbents stay, and vacant weapon stations fill
before zones and routes. Residents collect their assigned equipment from registered storage.
The resident card shows their equipment.

On the **Patrol** card under **Settings** → **Industries**, residents without a profile flee by default. The
alternative lets them engage with carried equipment and never fetches gear.
Assigned residents choose ranged weapons at range and melee up close or without
ammo. The RTS combat page displays these assignments.
Combat gear is independent of production tool lists. Offhand equipment remains
outside the existing armor handling.

A profile can require a spyglass as a supply.
Detection depends on the observer and target. Elevation extends distance, while
low effective light at the target shortens it. Required physical gear multiplies
that distance by 2 by default; missing stock gives no magnification. Night vision,
burning or glowing targets bypass darkness, but line of sight is always required.
Without vigilance, defaults are 32 blocks on bright flat ground, 44 with eight
blocks of eye-height advantage, and 88 from that wall with optics (64 on flat
ground). Effective light 0–4 gives 8 on flat ground without optics. The close-range
floor stays 3 and the total cap stays 128. Existing server configs retain their
chosen baseline; set it to 32 explicitly to adopt the new default. Sentry rings
show an upper bound, not a promise to detect everything inside. Ordinary
sentry areas use profile handheld weapons. For artillery, explicitly include and
automatically staff each enrolled weapon.
Keep a walkable route to clear standing space outside the actual cannon hull.
Operators choose a reachable cell in the first ring beside the hull and board on
arrival, even without an enemy; mortars operate from beside the weapon. Blocked travel keeps
the operator claim and retries after the route is cleared.

## Rations and food lists

Residents carry three days of food by default and restock when their pack falls
below one working day. A day's ration is measured in nutrition rather than item
count, so different foods need different amounts.

The **Food and daily needs** card under **Settings** → **Industries** has a **Food list mode** switch between whitelist and blacklist. **Pocket rations** opens the item picker, with item, tag and query rules. Switching modes keeps the same rules: a whitelist allows only matching food, and a blacklist refuses matching food. An empty whitelist allows nothing; an empty blacklist allows all safe, nourishing food. These rules govern eating from packs, ration deliveries, pack reserves and new settlers' food requirements. Dining-area meals use the area's own **Table food** selection instead. Having no usable food does not waive the food requirement.

The default is a whitelist containing `#folkways_vanilla:resident_food`, which combines:

- `#folkways_vanilla:resident_food_vanilla`: the original 15 vanilla ration types, including cooked meat, fish, bread and soups.
- `#folkways_vanilla:resident_food_cookery`: Kaleidoscope Cookery rice, noodles, cooked meat and everyday prepared meals.
- `#folkways_vanilla:resident_food_farmers_delight`: Farmer's Delight cooked meat, sandwiches, soups, rice, pasta and feast portions.

Mod entries are optional and load safely without those mods installed. Defaults omit raw ingredients, harmful dishes, special-purpose food and feast blocks that must be placed first. Modpacks can extend or replace these tags with a datapack; players can also add other mods' items, tags or queries in the picker. Existing colonies keep their saved food rules in whitelist mode. To adopt the new default list, replace those rules with `#folkways_vanilla:resident_food`.

The **Always refuse unsafe food** checkbox beside the food list mode is checked by default. While checked, safety checks apply even to whitelisted food. Residents skip food with any chance of a harmful status effect, including effects stored on suspicious stew. Forbidden or harmful food already in their packs is also skipped; allowed meals in later slots are still eaten. Beneficial food effects and returned bowls or bottles keep their normal behavior.

Unchecking it permits unsafe food for eating and ration planning, while still applying the whitelist or blacklist.

With safety enabled, harmful carried food does not count toward nourishment when restocking. Forbidden food never counts. Kaleidoscope Cookery's red and green chilies are also refused because eating them causes direct damage. Datapacks can add other foods with special harmful eating behavior to the `folkways_vanilla:unsafe_food` item tag.

## Dining areas

Pick **Dining area** on the book toolbar and draw around the tables and chairs. On its card, choose **Table food** and **Servings per tabletop block**
(1 to 4). Tables must come from a supported mod, such as Kaleidoscope Cookery, or be in the
`#folkways_vanilla:dining/tables` or `#folkways_vanilla:housing/table` block tags;
bottom stairs work as chairs. Dining is disabled when no supported tables are
available. Leave an aisle beside each chair. Connected tabletops on the same
level form one table that residents can serve from any reachable edge.

Residents bring one serving at a time from colony stores and keep food on
display. With several table foods selected, each empty serving slot asks for a
random dish from the menu and keeps that choice until delivery; these requests
can drive production directly. Supported tables without inventories hold one
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
| Excellent bedroom   | 9+                 | Up to 2       | 3+, without production stations                       | +10  |

Furnishing categories are storage, textiles, decoration, tables and lighting; duplicates of
one category do not count again. Crafting tables, furnaces and similar stations
count as production equipment. A resident without a bed receives −12. Room
surveys are bounded to 1,024 floor cells, 32 blocks from the bed and a roof within
12 blocks above; unloaded or oversized rooms wait for inspection. Block edits
refresh nearby surveys, and periodic inspection catches command and machine
changes. Empty signs inside a room or attached to its walls automatically display
the occupants’ names. Player edits are preserved, and owned nameplates are cleared
when the last occupant leaves.

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

Register an ordinary composter with **Take in** on the Colony Book toolbar.
The colony keeps a default seed reserve of **64** before composting surplus seeds.

Only surplus seeds are available for composting. Sowing, reserved inputs and
stock orders remain protected. Native composters work with Core and Vanilla;
neither Cookery nor a straw hat is required. With Kaleidoscope Cookery
installed, its planting materials participate in the same reserve system.

The creative supply box also belongs to Vanilla and works without Cookery.

## Beekeeping

Choose the apiary kind on the toolbar and draw around bee nests and beehives. On its card, choose
**Honey Bottle** or **Honeycomb**. Residents with the Herd trade harvest full hives;
honey bottles need glass bottles, while honeycomb needs shears. Bees continue
to pollinate and produce honey through the vanilla mechanics, so provide bees
and nearby flowers.

For hives without effective campfire smoke, the apiary requests campfire
construction on the ground beneath them. Builders use colony supplies to fill
these requests. Harvesting waits until the hive has effective smoke. The apiary
shows how many hives lack smoke. If there is no suitable ground space or the
smoke path is blocked, adjust the layout; relight any existing extinguished
campfires yourself.

## Buildings

Frame ground or draw a line, then choose a pattern. Builders clear the site and
use the colony's stores to construct roads, arch bridges, walls, docks, mines,
railways, arcades and terraced fields. Place whole buildings from the **Blueprints** page. With Create
installed, a Create schematic can also be handed to the colony.

The Assistant can read the marks you had in the Colony Book when you asked. A proposed build shows ghost
blocks while you review the confirmation. Confirming files the order for the colony.

To add your own patterns, structure pieces and blueprints, put them in the `<game>/folkways/` library folder
or a data pack, then run `/reload`.

## Gathering wild food
<!-- guide:groundwork-gathering-wild-food -->

In a groundwork zone, enable **Forage** and leave **Clear** and **Fill** off. Farmers then break the marked food
plants and carry their normal drops to colony storage. Forage is off by default.

Foraging clears land rather than harvesting it: sweet berry bushes, cave vines and other wild plants are removed and
not replanted. Young plants are removed too and drop only what their current stage drops, so a young berry bush can
yield nothing. If **Clear** or **Fill** is on, the zone becomes a single build order and does not forage at all.
Other enabled jobs, such as felling a tree that supports a plant, still run.

Colony-registered blocks are protected. Unregistered plants a player placed look the same as wild ones and can be
gathered. A cave-vine column is cut as one job and is left alone if any segment is protected. Two-block plants are
taken whole from their lower half.

Included: small red and brown mushrooms, pumpkins, melons, and wild plants from Farmer's Delight (wild cabbages,
onions, tomatoes, carrots, potatoes, beetroots and rice), Kaleidoscope Cookery (tea trees, tomato and chili crops),
Pam's HarvestCraft 2 Crops (the six gardens) and Supplementaries (wild flax). Not included: carved pumpkins, huge
mushrooms, ordinary crop fields and leaves. Croptopia plants are not included by default. A data pack can add plants
to the `#folkways_vanilla:forage` block tag.

Groundwork runs once: finished targets are not revisited. Redraw the zone or change its settings to find new targets.

## Fallen logs
<!-- guide:groundwork-fallen-logs -->

A groundwork zone's **Fell trees** setting also clears fallen logs: horizontal logs resting on solid ground, with no
upright log or leaves attached. Moss carpets, vines, mushrooms and ferns on or beside a log are collected with it,
even just outside the zone. Quark hollow logs count as fallen logs.

A connected group of more than 64 logs, or one next to an unloaded area, is left alone. A fallen log touching a
standing tree is left to normal tree felling. Colony-registered blocks and the blocks under them are protected.
Unregistered logs a player laid on the ground can look like fallen logs and may be cleared; raised beams and
upright posts are never treated as fallen logs.

**Clear** demolishes every marked block instead and does not collect the decorations; use **Fell trees** to keep
them. Data packs can add log-like blocks to `#folkways_vanilla:fallen_logs` and small attached plants to
`#folkways_vanilla:fallen_log_decorations`. Redraw an old zone to find fallen logs added since it was drawn.

## Overhead modes

Hold the Colony Book and press **V** to open the overhead view. Use the toolbar to choose what to
draw or select **RTS** to command residents. Accepted squads and orders continue on the server after
you leave RTS.

### RTS commands

Select residents by left-clicking or dragging; Shift adds or toggles residents. Left-click a vehicle or a
player-defined facility to select it instead. A plain right-click is a smart order: on one of your vehicles the
selected residents board it, on a nonfriendly mob or vehicle they attack it, and on ground they move there.
Ctrl+right-click queues a waypoint; Ctrl+right-click an earlier marker to close a patrol loop. Waypoints of one route share
its movement mode; right-click ground without Ctrl to start a new route.

The nine group buttons sit at the bottom left. Ctrl+1–9 or Ctrl+click binds the selection to a group (binding the
same set again clears it); 1–9 or a click recalls it. Operation failures, such as no selection, synchronizing,
invalid ground or route limits, show in red for about three seconds.

The selection's command card sits at the bottom right. **Move** (M) and **Attack** (G) arm a command: then
left-click ground to move, or a target to attack and ground to attack-move. Shift keeps the command armed for more
clicks; right-click or Esc cancels it. Right-click the Move or Attack button to make it what a plain right-click on
ground does; leaving RTS resets this to Move. **Stop** (H) stops and holds position. Vehicle cards add **Board** (B),
**Disembark** (N), **Land** (L), **Loiter** (O), **Decoy** (C), **Boost** (X) and **Details** (I) where they
apply. A facility card's buttons take R, T, Y, U, F and K in card order, or Q, W, E, R, A, S, D and F with the
edge-scroll camera. J releases the selected residents, vehicles and facilities from RTS control. Rebind keys under
**Controls → Folkways RTS**.

When a facility card has a target command, press it and click a point in the world to run it there. If the card
makes it the default command, right-click the point directly.

## Damage control

Pick **Damage control** on the Colony Book toolbar. Left-click two corners, then right-click to
save the area and open its card. Right-click an existing protected area to reopen its card.

The card contains **Put out fires**, **Restore missing blocks** and **Borrow and refill firefighting tools**. Repairs
start disabled. Use **Reset baseline** while the whole area is loaded, then enable repairs. This button also records the
first baseline. Every damage-control area is surveyed, including old saves that previously disabled an area. The card's
**Release** button deletes the area. To change the footprint, release it and draw a new area with its own
baseline. The overhead action bar only saves or discards the drawing.

Protected areas have green outlines. Orange cells mark fires, yellow cells mark missing blocks,
purple cells mark conflicts and blue cells mark assigned workers. Hover a marker for details.
The area card shows incident counts and provides its settings.

Keep the **Damage control** trade enabled for the residents who should respond. Fires in areas with Put out fires enabled become ordinary work
immediately. Damage control is enabled by default at priority 3 in normal trade work settings, including repairs and refills; saved
player priorities still apply, and personal needs, fleeing danger and deliberate control retain their normal precedence.
Overlapping areas share each fire and its responder. Campfires and decorative or industrial flames are preserved.

Leave a walkable opening and safe standing space near the fire. Spray positions reserve room for the normal arrival
tolerance and check the whole possible sight corridor before travel. Residents use ordinary arrival and then attempt the
addon's native spray. This conservative planning can reject positions near walls even when an exact shot would fit. Each
node keeps its selected method and standing positions, including a hose's cabinet pickup and spray stance. After an
actual paid spray, it waits five server ticks for native effects; if its targeted fire still burns, it smothers that
block directly and completes. Failed travel, unavailable water or a busy cabinet never authorize this fallback. An
unreachable route can fail and retry. Protected areas do not themselves prohibit entry.

A baseline records ordinary vanilla building blocks. Containers, crops, fluids, machines and blocks in `#folkways_vanilla:damage_control_excluded` are excluded. The target is confirmed once through the construction printer and stored with its palette, relative coordinates, revision and Minecraft DataVersion. Existing colonies have no baseline until one is recorded. Unknown blocks are skipped and reported. A zone spanning several structures cannot record a single baseline.

Residents restore only missing cells, using the builder's material requirements and placement adapter. Different blocks are reported and preserved; changing a door's open state does not request repairs. Whole missing doors are placed as one group. A partial group waits rather than resetting a surviving part. Active construction keeps its cells. Fire response takes precedence over repairs. After remodelling, record the baseline again. Baselines do not restore inventories or residents.

Vanilla workers smother nearby fire and soul fire by hand without placing water. With **Create: FireFighting Additions** installed, they prefer a nearby hydrant cabinet containing a hose, a cone or flat nozzle, and water. The cabinet is shared exclusively with one resident or player. A resident stays within the addon's 32-block connection limit; nozzle range uses the addon's effective settings (normally 50 blocks for cone and 35 for flat, clamped by its speed/friction limit). Once connected, a resident sprays from a safe current position or chooses a reachable position near the cabinet, preferring greater separation from the fire. They do not need to approach an already reachable distant fire. The pinned addon's server extinguishes along straight sampled rays; its visible water droplets follow arcs. Planning follows the server effect and can conservatively reject shots near obstacles. Each spray tick draws up to ten mB from the cabinet, including its final partial amount. Player takeover, removing equipment or the cabinet, interruption, death and unloading release the connection and stop sound immediately. After a spray ends, its hose remains visible for four client ticks and emitted water finishes its flight without consuming more fluid. Residents use the addon's spray particles and hose renderer.

When no cabinet can serve the fire, residents borrow a portable extinguisher from colony stores. Water is consumed
through the addon's spray drain API: five mB per two-tick pulse, at its four-block range, with a clear line of sight.
The selected fire comes from a surveyed zone; the addon's native cone can also suppress nearby fires along its
unobstructed samples. Nearby tracking clients receive the native cone mist or hose droplets in the server's target
direction. Automatic suppression normally finishes in one paid pulse, so its brief mist is much less conspicuous than a
player holding Use. Continuous paid pulses keep producing spray; stopping leaves only already emitted particles to
finish. The addon's particle density and the client's particle settings still apply. Only water is supported.

Refill uses fluid capabilities at colony stores, including water buckets. Workers can also gather source water inside an enabled refill zone, using an empty bucket from colony stores. The source is consumed and the bucket returned after filling. Supply discovery shares the zone scan budget. A bucket is emptied only when its whole contents fit; tanks can transfer smaller quantities. Empty tools fall back to hand smothering until refilled. Tools return to ordinary cargo when their owner stops damage control, dies, unloads, or the colony closes. Only water is used by automated firefighting. Moving-structure firefighting has not been verified in a client.

Server settings under `damageControl` cap a zone at 32,768 cells by default, share 512 scan cells per colony per tick, and allow four simultaneous responders. Residents share the surveyed fire list and a per-tick allowance for target footing searches equal to the responder limit. Each selection considers up to 128 rotating fire addresses; unreachable targets wait a second before retrying. Scan allowance rotates between dimensions even when it is only one cell. A fire survey keeps at most 4,096 pending fire addresses. Nearby cabinets announce themselves on their own ticks; each indexed lookup checks up to 64 cabinets and rotates through larger lists, without enumerating unrelated block entities. Partial repairs also rotate, and disabling a zone lets an enabled overlapping zone take over its repairs. The construction printer also limits a baseline's longest edge to 1,024 blocks.

## Control hubs

With Create installed, use an **Anonymous Typewriter** on Redstone Link receivers with both frequencies
empty. Place the typewriter to bind the converted **Control Terminals** and put a Create seat against its
marked face. A facility run calls a resident to operate the terminals. Vacant terminals return to level 0,
and breaking the typewriter restores the receivers.

## Elevators

With Create installed, residents ride Create elevators. Build and power the
elevator as usual: a glued cabin under an elevator pulley, a moving redstone
contact and matching elevator contacts at each floor, with walkable landings
beside the cabin. With the book pointing, left click the pulley to take it into
the colony. Residents then plan routes between its floors, wait on the landing,
walk into a free standing place and walk out at their stop.

Capacity comes from the clear connected deck, with room left for passing; seats
do not add capacity. A full cabin leaves the next resident waiting for a later
trip. A stopped or overstressed drive cannot carry anyone.

## Kaleidoscope Tavern

With Kaleidoscope Tavern installed alongside Kaleidoscope Cookery,
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
  quality across saves. Dining areas reserve their counters for drink service.
  Residents refill empty slots from colony storage or production and visit counters for a seated drink independently of hunger; diners can also fetch a
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
