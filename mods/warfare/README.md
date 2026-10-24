# Folkways × Superb Warfare

Optional NeoForge 1.21.1 extension, mod ID `folkways_warfare`. Install it with
Folkways Core, Folkways Vanilla, Superb Warfare 0.8.9.2 and Kotlin for Forge 5.12.

Choose **Firearm (Superb Warfare)** on the colony book's combat page. Residents
collect an actual gun and compatible loose rounds from registered colony stores.
Guards also operate supported emplacements inside their guard post's area.

See [gameplay and integration details](../../docs/en/warfare.md) and
[简体中文](../../docs/zh-CN/warfare.md).

```sh
npm run setup
./gradlew --no-daemon build
./gradlew --no-daemon -PwarfareTests=true -PgameTestMod=warfare runGameTestServer
```

The optional Blockwright profile is `blockwright.toml#warfare`. The default game
and GameTest profiles do not load either warfare mod.
