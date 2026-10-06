package osrs.dev.dumper;

import net.runelite.cache.definitions.ObjectDefinition;
import net.runelite.cache.region.Location;
import net.runelite.cache.region.Position;
import net.runelite.cache.region.Region;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.util.Collection;
import java.util.Collections;
import java.util.Map;
import java.util.OptionalInt;
import java.util.Set;
import java.util.TreeMap;
import java.util.function.IntFunction;
import java.util.zip.CRC32;

/** Cache collision with navigation object rules and separate wall, object, floor and projectile bits. */
public final class FullCollisionMap {
    public static final int FORMAT_VERSION = 1;
    public static final int FLAGS_PER_REGION = 4 * 64 * 64;
    public static final int BLOCK_OBJECT = 0x100;
    public static final int BLOCK_PROJECTILE_OBJECT = 0x20000;
    public static final int BLOCK_FLOOR_DECORATION = 0x40000;
    public static final int BLOCK_FLOOR = 0x200000;

    // Orientations rotate west, north, east, south. The receiving tile carries
    // the opposite bit; these are raw wall bits, not walkable-direction bits.
    private static final int[] WALL_BITS = {0x80, 0x02, 0x08, 0x20};
    private static final int[] WALL_DX = {-1, 0, 1, 0};
    private static final int[] WALL_DY = {0, 1, 0, -1};
    private static final int[] CORNER_BITS = {0x01, 0x04, 0x10, 0x40};
    private static final int[] CORNER_DX = {-1, 1, 1, -1};
    private static final int[] CORNER_DY = {1, 1, -1, -1};

    private final Map<Integer, int[]> regions = new TreeMap<>();

    private FullCollisionMap() {}

    /** Inputs must have both terrain and locations successfully decoded. */
    public static FullCollisionMap generate(Collection<Region> sourceRegions,
                                            IntFunction<ObjectDefinition> objects) {
        FullCollisionMap map = new FullCollisionMap();
        for (Region region : sourceRegions) {
            int id = region.getRegionID();
            if (id < 0 || id > 0xffff || map.regions.putIfAbsent(id, new int[FLAGS_PER_REGION]) != null) {
                throw new IllegalArgumentException("Invalid or duplicate region: " + id);
            }
        }
        // Allocate every region first: walls and object footprints can write
        // across a mapsquare boundary, regardless of input iteration order.
        for (Region region : sourceRegions) {
            map.addFloors(region);
            for (Location location : region.getLocations()) {
                ObjectDefinition object = objects.apply(location.getId());
                if (object == null) {
                    throw new IllegalArgumentException("Missing object definition " + location.getId()
                            + " in region " + region.getRegionID());
                }
                map.addObject(region, location, object);
            }
        }
        return map;
    }

    public Set<Integer> regionIds() {
        return Collections.unmodifiableSet(regions.keySet());
    }

    public OptionalInt flag(int x, int y, int plane) {
        if (x < 0 || x >= 16384 || y < 0 || y >= 16384 || plane < 0 || plane >= 4) {
            return OptionalInt.empty();
        }
        int[] flags = regions.get(((x >> 6) << 8) | (y >> 6));
        return flags == null ? OptionalInt.empty() : OptionalInt.of(flags[index(x, y, plane)]);
    }

    /** Writes one Titan REGC record, as documented in docs/full-collision-format.md. */
    public void writeRegion(int regionId, OutputStream output) throws IOException {
        int[] flags = regions.get(regionId);
        if (flags == null) {
            throw new IllegalArgumentException("Unavailable region: " + regionId);
        }
        ByteBuffer buffer = ByteBuffer.allocate(36 + FLAGS_PER_REGION * 4).order(ByteOrder.BIG_ENDIAN);
        int regionX = regionId >> 8;
        int regionY = regionId & 255;
        buffer.put("REGC".getBytes(StandardCharsets.US_ASCII));
        // Titan's observed version-1 records have zero fields at offsets 8 and 32.
        buffer.putInt(FORMAT_VERSION).putInt(0).putInt(0).putInt(regionId);
        buffer.putShort((short) regionX).putShort((short) regionY);
        buffer.putInt(regionX * 64).putInt(regionY * 64).putInt(0);
        for (int flag : flags) {
            buffer.putInt(flag);
        }
        CRC32 crc = new CRC32();
        crc.update(buffer.array(), 36, FLAGS_PER_REGION * 4);
        buffer.putInt(12, (int) crc.getValue());
        output.write(buffer.array());
    }

    private void addFloors(Region region) {
        for (int plane = 0; plane < 4; plane++) {
            for (int x = 0; x < 64; x++) {
                for (int y = 0; y < 64; y++) {
                    if ((region.getTileSetting(plane, x, y) & 1) != 0) {
                        add(region.getBaseX() + x, region.getBaseY() + y,
                                collisionPlane(region, x, y, plane), BLOCK_FLOOR);
                    }
                }
            }
        }
    }

    private static int collisionPlane(Region region, int localX, int localY, int plane) {
        return plane - ((region.getTileSetting(1, localX, localY) & 2) != 0 ? 1 : 0);
    }

    private void addObject(Region region, Location location, ObjectDefinition object) {
        Position position = location.getPosition();
        int x = position.getX();
        int y = position.getY();
        int type = location.getType();
        int rotation = location.getOrientation();
        if (type < 0 || type > 22 || rotation < 0 || rotation > 3) {
            throw new IllegalArgumentException("Unsupported location shape/orientation for object " + object.getId());
        }
        int plane = collisionPlane(region, x - region.getBaseX(), y - region.getBaseY(), position.getZ());
        if (plane < 0 || !ProcessingUtils.shouldBlock(object, location.getId())) {
            return;
        }
        // Explicit blockers such as GE booths and Pest Control barricades must
        // clip even when the cache marks their definitions hollow or noninteractive.
        boolean explicitlyBlocked = Boolean.FALSE.equals(Exclusion.matches(location.getId()));
        if (!explicitlyBlocked && (object.isHollow() || object.getInteractType() == 0)) {
            return;
        }
        if (type == 22) {
            if (explicitlyBlocked || object.getInteractType() == 1) {
                add(x, y, plane, BLOCK_FLOOR_DECORATION);
            }
        } else if (type <= 3) {
            if (type == 1 || type == 3) {
                addWall(x, y, plane, rotation, CORNER_BITS, CORNER_DX, CORNER_DY, object.isBlocksProjectile());
            } else {
                addWall(x, y, plane, rotation, WALL_BITS, WALL_DX, WALL_DY, object.isBlocksProjectile());
                if (type == 2) {
                    addWall(x, y, plane, (rotation + 1) & 3,
                            WALL_BITS, WALL_DX, WALL_DY, object.isBlocksProjectile());
                }
            }
        } else if (type >= 9) {
            int width = (rotation & 1) == 0 ? object.getSizeX() : object.getSizeY();
            int height = (rotation & 1) == 0 ? object.getSizeY() : object.getSizeX();
            int flags = BLOCK_OBJECT | (object.isBlocksProjectile() ? BLOCK_PROJECTILE_OBJECT : 0);
            for (int dx = 0; dx < width; dx++) {
                for (int dy = 0; dy < height; dy++) {
                    add(x + dx, y + dy, plane, flags);
                }
            }
        }
    }

    private void addWall(int x, int y, int plane, int rotation,
                         int[] bits, int[] dx, int[] dy, boolean blocksProjectile) {
        int originFlags = bits[rotation];
        int neighbourFlags = bits[(rotation + 2) & 3];
        if (blocksProjectile) {
            originFlags |= originFlags << 9;
            neighbourFlags |= neighbourFlags << 9;
        }
        add(x, y, plane, originFlags);
        add(x + dx[rotation], y + dy[rotation], plane, neighbourFlags);
    }

    private void add(int x, int y, int plane, int flags) {
        if (x < 0 || x >= 16384 || y < 0 || y >= 16384 || plane < 0 || plane >= 4) {
            return;
        }
        int[] region = regions.get(((x >> 6) << 8) | (y >> 6));
        if (region != null) {
            region[index(x, y, plane)] |= flags;
        }
    }

    private static int index(int x, int y, int plane) {
        return (plane * 64 + (x & 63)) * 64 + (y & 63);
    }
}
