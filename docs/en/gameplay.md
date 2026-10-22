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
