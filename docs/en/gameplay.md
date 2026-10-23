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

## Food lists and safety

Colony settings offer a **Food list mode** switch between whitelist and blacklist. **Food rules** opens the existing item picker, with item, tag and query rules. Switching modes keeps the same rules: a whitelist allows only matching food, and a blacklist refuses matching food. An empty whitelist allows nothing; an empty blacklist allows all safe, nourishing food. These rules govern eating, ration deliveries, pack reserves and new settlers' food requirements. Having no usable food does not waive the food requirement.

The default is a whitelist containing `#folkways:resident_food`, which combines:

- `#folkways:resident_food_vanilla`: the original 15 vanilla ration types, including cooked meat, fish, bread and soups.
- `#folkways:resident_food_cookery`: Kaleidoscope Cookery rice, noodles, cooked meat and everyday prepared meals.
- `#folkways:resident_food_farmers_delight`: Farmer's Delight cooked meat, sandwiches, soups, rice, pasta and feast portions.

Mod entries are optional and load safely without those mods installed. Defaults omit raw ingredients, harmful dishes, special-purpose food and feast blocks that must be placed first. Modpacks can extend or replace these tags with a datapack; players can also add other mods' items, tags or queries in the picker. Existing colonies keep their saved food rules in whitelist mode. To adopt the new default list, replace those rules with `#folkways:resident_food`.

The **Always refuse unsafe food** checkbox beside the food list mode is checked by default. While checked, safety checks apply even to whitelisted food. Residents skip food with any chance of a harmful status effect, including effects stored on suspicious stew. Forbidden or harmful food already in their packs is also skipped; allowed meals in later slots are still eaten. Beneficial food effects and returned bowls or bottles keep their normal behavior.

Unchecking it permits unsafe food for eating and ration planning, while still applying the whitelist or blacklist.

With safety enabled, harmful carried food does not count toward nourishment when restocking. Forbidden food never counts. Kaleidoscope Cookery's red and green chilies are also refused because eating them causes direct damage. Datapacks can add other foods with special harmful eating behavior to the `folkways:unsafe_food` item tag.

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
