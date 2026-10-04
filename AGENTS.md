
## Your responses

- Offer constructive criticism that identifies flaws and oversights.
- Always ask for more details and liberally search the web as needed to provide a good answer or implementation.
- User should not have to ask you for your opinion explicitly. Always evaluate what the user is asking you to do, and
  voice your concerns before proceeding if you don’t think it's a good idea. If possible, propose a better solution, but
  you can voice concerns even without one. This applies even to direct requests to revert or simplify. Still evaluate
  whether your original approach was better. The user may be missing important context. If there was a solid reasoning
  you suggested that approach, push back with reasoning instead of silently complying.
- 
## Tools

- IMPORTANT: Prefer using the intellij-index MCP tools for code navigation and refactoring.
- Use the ide_diagnostics tool from intellij-index MCP to validate all changes after finishing a batch of edits.

## Project Overview

CacheMapDumper is a Java Swing application that extracts collision map data from Old School RuneScape (OSRS) game cache and provides a visual viewer for navigating the collision data. It depends on RuneLite's cache tools library.

## Build Commands

```bash
# Build the project (produces a shadow/fat JAR)
./gradlew build

# The shadow JAR is output to build/libs/*-shaded.jar
# Main class: osrs.dev.Main
```

## Running the Application

There are two entry points:

1. **Viewer UI** (`osrs.dev.Main`) - Launches the Swing collision map viewer
2. **Dumper CLI** (`osrs.dev.dumper.Dumper`) - Command-line tool to dump collision data
   - `-path <path>` - Output path for generated files
   - `-fresh y/n` - Whether to download fresh cache from OpenRS2
   - `-legacy` - Output in legacy SparseBitSet format (default is RoaringBitmap GZIP)

### Dumper Module (`osrs.dev.dumper`)
- `Dumper` - Extracts collision data using RuneLite's `RegionLoader` and `ObjectManager`
- `OpenRS2` - Fetches latest OSRS cache and XTEA keys from OpenRS2 archive API
- `Exclusion` - Manages object ID exclusions for special cases like unopenable doors

### UI Module (`osrs.dev.ui`)
- `UIFrame` - Main Swing window with navigation controls
- `ViewPort` - Renders collision data to `BufferedImage`
- `Cell` / `Wall` - Rendering primitives

### Default Paths

- Cache directory: `~/VitaX/collision/cache/`
- XTEA keys: `~/VitaX/collision/keys/`
- Output map: `~/VitaX/collision/map.dat`
