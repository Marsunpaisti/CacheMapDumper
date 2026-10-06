# Full collision dataset, version 1

`FullCollisionDumper` exports decoded terrain and base object-definition
collision from a local OSRS cache, applying CacheMapDumper's navigation object
rules. It does not require TitanClient or its sources.
The exporter is separate from `Dumper`, so sparse/Roaring navigation maps and
the viewer retain their existing behavior.

## Binary layout

Each `region_<id>.regc` is an uncompressed Titan REGC version-1 record.
All integers are **big-endian**. There is no Java serialization or Roaring dependency.
This replaces the earlier experimental little-endian `collision.bin` format.
Matching Titan's storage layout does not imply matching its collision policy:
the exporter uses CacheMapDumper's door/exclusion rules and retains projectile
collision for objects that contribute blocking.

| Offset | Field | Type | Meaning |
| --- | --- | --- | --- |
| 0 | Magic | 4 bytes | ASCII `REGC` |
| 4 | Version | uint32 | `1` |
| 8 | Unidentified header field | uint32 | `0`, as observed in Titan's export |
| 12 | Payload checksum | uint32 | CRC32 of bytes 36 through 65,571 |
| 16 | Region ID | uint32 | `(regionX << 8) \| regionY` |
| 20 | Region X | uint16 | `regionId >> 8` |
| 22 | Region Y | uint16 | `regionId & 255` |
| 24 | Origin X | uint32 | `regionX * 64` |
| 28 | Origin Y | uint32 | `regionY * 64` |
| 32 | Unidentified header field | uint32 | `0`, as observed in Titan's export |
| 36 | Flags | 16,384 × uint32 | Four planes of 64 × 64 tiles |

Each file occupies **65,572 bytes**; total collision-file size is
`regionCount * 65572`. Readers should reject bad magic, unknown versions,
inconsistent filenames/coordinates, bad CRCs, out-of-range IDs, truncation,
and trailing data. Files are emitted in ascending region-ID order.

For a world tile `(x,y,plane)`:

```text
regionId = ((x >> 6) << 8) | (y >> 6)
flagIndex = (plane * 64 + (x & 63)) * 64 + (y & 63)
byteOffset = 36 + flagIndex * 4
```

Coordinates are in `[0,16383]`, planes in `[0,3]`. An absent region is
**unavailable collision**, not zero flags. Zero in a present region means that
no static clipping bits were generated for that tile. Region origin is
`((regionId >> 8) * 64, (regionId & 255) * 64)`.

## Bit meanings

These use the standard raw cache/client collision layout. Wall names below
describe the wall's position relative to the tile, not a permitted movement
direction. Titan's SDK labels the same numeric bits by different directional
names; consumers must preserve the numbers rather than remap them by name.

| Bit | Meaning |
| --- | --- |
| `0x000001` | Northwest diagonal wall |
| `0x000002` | North wall |
| `0x000004` | Northeast diagonal wall |
| `0x000008` | East wall |
| `0x000010` | Southeast diagonal wall |
| `0x000020` | South wall |
| `0x000040` | Southwest diagonal wall |
| `0x000080` | West wall |
| `0x000100` | Blocking object footprint |
| `0x000200` through `0x010000` | Corresponding projectile wall bits (movement wall bit shifted left by 9) |
| `0x020000` | Projectile-blocking object footprint |
| `0x040000` | Blocking floor decoration |
| `0x200000` | Blocking floor |

The scene-initialization/unloaded flag `0x1000000` is not generated for decoded
regions. Missing regions must be handled using coverage, not by fabricating
zero-filled records or interpreting absent data as walkable.

## Generation rules

- Straight walls (shape 0), diagonal walls (1/3), and L-shaped walls (2) write
  their bits and the reciprocal bits on adjacent tiles. Walls and rotated
  object footprints can cross region boundaries; output is independent of
  input region order.
- Object passability uses `ProcessingUtils.shouldBlock`: explicit pathable
  `Exclusion.java` entries are skipped; explicit blockers override door detection;
  otherwise door-like names with passable actions are skipped. Skipped objects
  contribute neither movement nor projectile bits and never clear collision
  belonging to other objects or terrain.
- Explicit blockers bypass hollow/noninteractive clipping exemptions, including
  GE booths and Pest Control barricades. Their blocking still follows the placed
  shape and rotation. Shapes 9–21 use rotated footprints; shape 22 uses the
  floor-decoration bit. Projectile bits follow the definition.
- For other retained objects, shapes 9–21 clip when `interactType != 0`, and
  shape 22 clips only for `interactType == 1`. Hollow definitions do not clip.
  Wall decorations (4–8) never clip. High object IDs are not automatically excluded.
- Terrain setting bit 1 adds floor blocking. Bridge bit 2 on terrain plane 1
  lowers the collision plane by one for floors and locations at that origin.
  A resulting plane below zero is discarded, not clamped to plane zero.
- No floor-texture, missing-underlay, or Paisti-map heuristics apply.
- Location definitions are the base definitions placed in the cache. No
  account varbits, server-spawned changes, NPCs, players, or instance rotations
  are supplied. A static dump cannot establish live-client collision parity.

The bit layout and placement rules were checked against the client collision
operations in [CollisionMap.java](https://github.com/open-osrs/runelite/blob/master/runescape-client/src/main/java/CollisionMap.java)
and [addObjects](https://github.com/open-osrs/runelite/blob/master/runescape-client/src/main/java/class268.java),
and the RuneLite 1.13.1 cache decoder's terrain/location definitions. These
references establish the underlying static geometry; the navigation object
rules above override clipping eligibility. They do not prove parity with a
particular current native client build.

## Coverage and provenance

`manifest.json` is written after all region files and includes `format: "REGC"`,
`version: 1`, total `collision_bytes`, per-file names/sizes/SHA-256 in `collision_files`,
the sorted available region IDs, terrain regions that failed to decode fully,
the maps-index revision/CRC, and sizes/SHA-256 hashes of the disk-cache files
and OpenRS2 `keys.json`. The maps-index revision is not an OSRS game revision.
Keep the original OpenRS2 cache ID/revision alongside the dataset when known.
`collision_policy: "cachemapdumper-navigation-objects-v1"` distinguishes these
datasets from earlier `static-cache-base-definitions-v1` exports without
navigation object rules. Pin the dumper revision to identify the exclusion list.

Both terrain and locations must decode before a region is included. For named
archives without a supplied key, the exporter attempts an all-zero key to
permit unencrypted locations; a failed attempt is recorded as unavailable.
The exporter scans the full 16-bit region-ID range, including regions beyond
RuneLite RegionLoader's default enumeration limit. Missing object definitions
abort generation rather than silently dropping blockers.

Region coverage proves successful decoding, not that neighboring missing
regions could not contain walls/footprints extending into a decoded region.
Avoid incomplete-coverage boundaries when selecting benchmark routes.
Readers should require a valid manifest and verify its per-file checksums before
using a dataset. The output directory must not already exist, so a failed or
new export cannot overwrite an earlier dataset.

For benchmarks, pin the entire dataset and the transport catalog, and record
the dumper revision. Measure loading/preprocessing separately from search.
Account-dependent transport eligibility can be mocked separately, but doing
so does not change the collision of account-dependent scenery in this dump.
