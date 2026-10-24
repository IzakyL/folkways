# TACZ firearms

[Documentation](README.md) · [简体中文](../zh-CN/tacz.md)

Install `folkways-tacz-*.jar` beside Core, Vanilla and the **unofficial NeoForge
1.21.1 TACZ port, exactly 1.1.8-hotfix-r7**. This extension is optional; colonies
without it keep ordinary melee, bow and crossbow combat.

In the Colony Book's combat page, select a squad, then choose a firearm from the
server's available equipment choices. Residents collect the matching physical
gun and caliber from registered storage within 32 blocks. Giving a resident a
gun by hand also infers its ammunition needs. Loaded magazines and chambers count
toward the total. Guns are optional equipment, not mandatory patrol tools.
Replenishment counts only usable physical guns, including when several variants
share the same gun ID. Unusable copies cannot occupy the usable gun's retention allowance.
Existing colonies keep their tool permissions: allow the TACZ gun item in the
colony's tool settings before requesting a stored gun if it was not permitted.

The server writes `folkways-tacz-server.toml` in its server configuration directory:

| Setting               | Default                         | Effect                                                                                                   |
| --------------------- | ------------------------------- | -------------------------------------------------------------------------------------------------------- |
| `allowedGuns`         | `[]`                            | All compatible built-in guns; an explicit list restricts IDs and may opt in other conventional gun packs |
| `targetRounds`        | `32`                            | Loose rounds plus magazine and chamber target                                                            |
| `lowWaterRounds`      | `8`                             | Refill at or below this total                                                                            |
| `rangeCap`            | `24`                            | Maximum firing distance; guard engagement remains 12 blocks                                              |
| `gunRangeCaps`        | `tacz:m1911=16`, `tacz:m870=12` | Per-gun caps bounded by `rangeCap`                                                                       |
| `protectColonyAllies` | `true`                          | Prevent colony bullets from hurting same-colony residents and colony pets, including bullets in flight   |

Allied residents, players and colony pets crossing the firing corridor cause the
resident to hold fire. Stray colony rounds stop on blocks without breaking glass
or activating blocks. Explosive, incendiary, fuel, virtual/infinite ammunition,
charge weapons and unknown gun scripts are unsupported. Conventional built-in
reload and bolt scripts are allowed explicitly. Installed attachments and runtime
bullet effects are checked too; explosive or incendiary conversions remain unavailable.
Each script invocation may load only rounds it actually extracted from the pack or
moved out of the magazine, so delayed reload updates cannot manufacture ammunition.
Other resident body kinds are outside this version.

The extension provides aiming arms for player skins and common Geo/maid arm bone
names, plus a held item for those Geo arms. It does not provide TACZ player-only
reload animations or model-pack-specific animation clips.

Run the focused server suite with:

```sh
./gradlew --no-daemon -PtaczTests=true -PgameTestMod=tacz runGameTestServer
```

`-PtaczTests=true` without `gameTestMod` runs the combined suite with TACZ.
Blockwright uses `blockwright.toml#tacz`; its jar and SHA-512 are pinned in the
root configuration. Native draw, reload, bolt and burst timers use milliseconds,
so accelerated tick counts alone are insufficient for their tests.

The real-client smoke test also loads Touhou Little Maid to exercise skin, Geo
and maid appearances:

```sh
npm exec -- playwright test --config tests/e2e/playwright.config.ts tacz-combat.spec.ts --workers=1
```
