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

## Viewer

**UI Elements**<br />
**Top Slider:** Move speed<br />
**Right Slider:** Zoom

**Controlls**<br />
**Arrow Keys:** Move around<br />
**Mouse Wheel:** Zoom

![image](https://github.com/user-attachments/assets/9c93ee6a-8f05-4a3c-b632-9645ee4338a1)

