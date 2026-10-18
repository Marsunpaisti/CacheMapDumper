**Note:** This depends on runelites cache tools

## Full collision export

Export 32-bit collision flags from an existing game cache, independently
of the viewer and the legacy two-bit navigation maps:

```powershell
.\gradlew.bat dumpFullCollision
```

Defaults: cache in `~/VitaX/cachedumper/cache/`, OpenRS2 keys in
`~/VitaX/cachedumper/keys/keys.json`, output in `build/full-collision/`.
The task reads existing inputs and does not download a fresh cache. Use an
unused output directory for each dataset:

```powershell
.\gradlew.bat dumpFullCollision --args="--cache C:/caches/osrs/cache --keys C:/caches/osrs/keys.json --output build/collision-example"
```

The result is Titan-format big-endian `region_<id>.regc` files plus `manifest.json`,
ready for a standalone reader in C++, Java, or another language. Each region has
a payload CRC32; the manifest records input hashes, available regions, decode
failures, and per-file SHA-256 checksums. The cache decoder
is pinned to RuneLite 1.13.1. Java 11 and the bundled Gradle wrapper are sufficient.

After `gradlew.bat build`, the same exporter is available in the shaded JAR:

```powershell
java -Xmx4g -cp build/libs/CacheMapDumper-1.0-SNAPSHOT-shaded.jar osrs.dev.dumper.FullCollisionDumper --output build/collision-another
```

This is cache-derived navigation collision, not a live client snapshot. It uses
the existing `Exclusion.java` and door rules: explicit blockers stay blocked,
explicit passable objects (including rockfalls) are skipped, and ordinary
passable doors are skipped. It retains full directional walls, object footprints,
floor flags, and projectile flags for retained objects. Terrain uses cache
blocking bits; legacy floor heuristics and Paisti-area overrides do not apply.
See [the format and collision rules](docs/full-collision-format.md) for native
reader details, region coverage, and limitations.

## Item definitions and equipment stats

Export every item in an existing disk cache, plus the full published RuneLite
item-stats dataset. No running client or XTEA keys are needed:

```powershell
.\gradlew.bat dumpItems
```

Defaults: cache in `~/VitaX/cachedumper/cache/`, output in `build/items/`.
Stats download automatically from
`https://static.runelite.net/item/stats.ids.min.json`. The cache itself is not
downloaded or updated. Choose an unused output directory for subsequent runs:

```powershell
.\gradlew.bat dumpItems --args="--cache C:/caches/osrs/cache --output build/items-example"
```

For a fully offline run, supply a previously saved stats file:

```powershell
.\gradlew.bat dumpItems --args="--item-stats build/items/item-stats.json --output build/items-offline"
```

After `gradlew.bat build`, the standalone entrypoint is also in the shaded JAR:

```powershell
java -Xmx1g -cp build/libs/CacheMapDumper-1.0-SNAPSHOT-shaded.jar osrs.dev.dumper.ItemDefinitionDumper --output build/items-another
```

Each export contains:

- **`item-definitions.json`**: `schemaVersion: 1`, with `raw` and `linked`
  objects keyed by decimal item ID, ordered numerically. Both retain every field
  decoded by RuneLite 1.13.1, including nulls, actions, equipment positions,
  models, recolours, item relationships, and arbitrary params. `raw` means
  decoded before `ItemManager.link()`, not the original binary. `linked` applies
  note, bought-variant, and placeholder transformations; use it for stackability.
  Field names and types are RuneLite's unchanged: `stackable` is an integer,
  with `1` meaning stackable, not a JSON boolean.
- **`item-stats.json`**: the complete source JSON, preserved byte-for-byte,
  keyed by item ID. Includes published weight, equipability, equipment slot,
  attack/defence bonuses, strength/ranged-strength/magic-damage/prayer bonuses,
  attack speed, two-handed status, and GE limits. Unknown fields are retained.
- **`manifest.json`**: decoder version, generation time, cache index/archive
  revisions, item-archive SHA-256, stats source, coverage counts, and output sizes
  and SHA-256 checksums. The archive revision is not the game's revision number.

For example, a reader looks up `definitions.linked["995"].stackable` and
`stats["4151"].equipment.aspeed`. RuneLite's stats are sparse: omitted numerical
bonus fields within an existing equipment record default to zero, but an absent
item record means unknown stats. IDs are not canonicalized or filled from related
items. Stats are published separately from the cache, so their revisions may
differ; the manifest records overlap without discarding unmatched IDs.

## NPC definitions and animation timings

Export every NPC definition and every animation (sequence) in an existing disk
cache. No running client or XTEA keys are needed:

```powershell
.\gradlew.bat dumpNpcs
.\gradlew.bat dumpNpcs --args="--cache C:/caches/osrs/cache --output build/npcs-example"
```

Defaults: cache in `~/VitaX/cachedumper/cache/`, output in `build/npcs/`. The
output directory must not exist yet. Each export contains:

- **`npc-definitions.json`**: `schemaVersion: 1`, with an `npcs` object keyed by
  decimal NPC ID, ordered numerically, holding every field decoded by RuneLite
  1.13.1 unchanged. `stats` is `[attack, defence, strength, hitpoints, ranged,
  magic]`; `params` holds the server params the cache ships (attack and defence
  bonuses and similar, keyed by param ID).
- **`sequences.json`**: `schemaVersion: 1`, with a `sequences` object keyed by
  animation ID: the fields that time an animation (`frameLengths` in 20 ms
  client cycles, `frameStep`, `maxLoops`, `replyMode`, priorities, and the
  skeletal `animMaya*` fields) plus `lengthClientCycles`, their sum. Death
  animations usually end on a very long frame that holds the last pose until
  the NPC despawns, so read `frameLengths` for the visible length. Frame sounds
  are left out.
- **`manifest.json`**: decoder version, generation time, cache index and
  archive revisions, archive SHA-256s, counts, and output sizes and SHA-256
  checksums.

The exporter stages files and only publishes the directory after successful
completion. Existing output is never overwritten. Definitions remain valid for
the captured cache; new game items or balance changes still require a new export.

## Sprites

Export every sprite in an existing disk cache as a PNG:

```powershell
.\gradlew.bat dumpSprites
.\gradlew.bat dumpSprites --args="--cache C:/caches/osrs/cache --output build/sprites-example"
```

Defaults: cache in `~/VitaX/cachedumper/cache/`, output in `build/sprites/`,
which must not exist yet. Files are named `<sprite id>-<frame>.png` by
RuneLite's `SpriteManager`, which skips empty frames; RuneLite's gameval
`SpriteID` names the IDs. `manifest.json` records the decoder version, the
sprite index revision and the frame count. The RuneLite cache library logs a
"revision mismatch" warning for many sprite archives; the export is still
complete.

## Item icons

Inventory icons aren't stored in the cache: the client renders them from each
item's model. Render every named item's icon the same way, with RuneLite's
`ItemSpriteFactory`:

```powershell
.\gradlew.bat dumpItemIcons
.\gradlew.bat dumpItemIcons --args="--cache C:/caches/osrs/cache --output build/item-icons-example"
```

Defaults: cache in `~/VitaX/cachedumper/cache/`, output in `build/item-icons/`,
which must not exist yet. Files are named `<item id>.png`: 36x32 icons at
quantity 1, with the inventory's black outline and shadow (the arguments
RuneLite's own icon test uses). Stackable items whose icon changes with the
stack size (coins, arrows) show their single-item icon. `manifest.json`
records the decoder version, the model index revision, the icon count and the
ids that failed to render.

## Viewer

**UI Elements**<br />
**Top Slider:** Move speed<br />
**Right Slider:** Zoom

**Controlls**<br />
**Arrow Keys:** Move around<br />
**Mouse Wheel:** Zoom

![image](https://github.com/user-attachments/assets/9c93ee6a-8f05-4a3c-b632-9645ee4338a1)

