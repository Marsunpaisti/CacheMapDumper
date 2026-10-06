package osrs.dev.dumper;

import net.runelite.cache.definitions.MapDefinition;
import net.runelite.cache.definitions.ObjectDefinition;
import net.runelite.cache.region.Location;
import net.runelite.cache.region.Position;
import net.runelite.cache.region.Region;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

import java.io.ByteArrayOutputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.Arrays;
import java.util.List;
import java.util.zip.CRC32;

import static org.junit.jupiter.api.Assertions.*;

class FullCollisionMapTest {
    @ParameterizedTest
    @CsvSource({
            "0, 0, 128, -1,  0,   8", "0, 1,  2,  0,  1,  32",
            "0, 2,   8,  1,  0, 128", "0, 3, 32,  0, -1,   2",
            "1, 0,   1, -1,  1,  16", "1, 1,  4,  1,  1,  64",
            "1, 2,  16,  1, -1,   1", "1, 3, 64, -1, -1,   4",
            "3, 0,   1, -1,  1,  16", "3, 1,  4,  1,  1,  64",
            "3, 2,  16,  1, -1,   1", "3, 3, 64, -1, -1,   4"
    })
    void wallsPreserveOrientationAndReciprocalBits(int shape, int rotation, int origin,
                                                  int dx, int dy, int reciprocal) {
        Region region = region(0);
        region.getLocations().add(location(1, shape, rotation, 30, 30, 0));
        FullCollisionMap map = FullCollisionMap.generate(List.of(region), id -> object(id));

        assertEquals(origin, map.flag(30, 30, 0).orElseThrow());
        assertEquals(reciprocal, map.flag(30 + dx, 30 + dy, 0).orElseThrow());
        assertEquals(0, map.flag(35, 35, 0).orElseThrow());
    }

    @ParameterizedTest
    @CsvSource({
            "0, 130, -1, 0, 8, 0, 1, 32",
            "1, 10, 0, 1, 32, 1, 0, 128",
            "2, 40, 1, 0, 128, 0, -1, 2",
            "3, 160, 0, -1, 2, -1, 0, 8"
    })
    void lWallsBlockBothEdges(int rotation, int origin, int dx1, int dy1, int flag1,
                             int dx2, int dy2, int flag2) {
        Region region = region(0);
        region.getLocations().add(location(1, 2, rotation, 30, 30, 0));
        FullCollisionMap map = FullCollisionMap.generate(List.of(region), id -> object(id));

        assertEquals(origin, map.flag(30, 30, 0).orElseThrow());
        assertEquals(flag1, map.flag(30 + dx1, 30 + dy1, 0).orElseThrow());
        assertEquals(flag2, map.flag(30 + dx2, 30 + dy2, 0).orElseThrow());
    }

    @Test
    void projectileWallsAndReciprocalCollisionCrossRegionBoundaries() {
        Region west = region(0);
        Region east = region(256);
        east.getLocations().add(location(1, 0, 0, 64, 30, 0));
        ObjectDefinition definition = object(1);
        definition.setBlocksProjectile(true);
        FullCollisionMap map = FullCollisionMap.generate(List.of(east, west), id -> definition);

        assertEquals(0x10080, map.flag(64, 30, 0).orElseThrow());
        assertEquals(0x01008, map.flag(63, 30, 0).orElseThrow());
    }

    @Test
    void rotatedObjectFootprintSpansFourRegionsAndAccumulatesFlags() {
        Region southwest = region(0);
        southwest.getLocations().add(location(1, 10, 1, 63, 63, 0));
        southwest.getLocations().add(location(2, 22, 0, 63, 63, 0));
        ObjectDefinition large = object(1);
        large.setSizeX(2);
        large.setSizeY(3);
        large.setBlocksProjectile(true);
        ObjectDefinition decoration = object(2);
        decoration.setInteractType(1);
        FullCollisionMap map = FullCollisionMap.generate(
                List.of(southwest, region(1), region(256), region(257)),
                id -> id == 1 ? large : decoration);

        assertEquals(0x60100, map.flag(63, 63, 0).orElseThrow());
        for (int x = 63; x < 66; x++) {
            assertEquals(0x20100, map.flag(x, 64, 0).orElseThrow());
        }
        assertEquals(0, map.flag(66, 64, 0).orElseThrow());
        assertEquals(0, map.flag(64, 65, 0).orElseThrow());
    }

    @ParameterizedTest
    @ValueSource(ints = {9, 10, 11, 12, 17, 21})
    void gameObjectsUseObjectClippingRatherThanFullOrWallBits(int shape) {
        Region region = region(0);
        region.getLocations().add(location(70000, shape, 0, 30, 30, 0));
        FullCollisionMap map = FullCollisionMap.generate(List.of(region), id -> object(id));
        // Cache ids above 65535 are still valid definitions, not a clipping exemption.
        assertEquals(0x100, map.flag(30, 30, 0).orElseThrow());
    }

    @ParameterizedTest
    @ValueSource(ints = {4, 5, 6, 7, 8})
    void wallDecorationsDoNotAddCollision(int shape) {
        Region region = region(0);
        region.getLocations().add(location(1, shape, 0, 30, 30, 0));
        FullCollisionMap map = FullCollisionMap.generate(List.of(region), id -> object(id));
        assertEquals(0, map.flag(30, 30, 0).orElseThrow());
    }

    @ParameterizedTest
    @CsvSource({"0, 0", "1, 262144", "2, 0"})
    void floorDecorationClippingRequiresInteractTypeOne(int interactType, int expected) {
        Region region = region(0);
        region.getLocations().add(location(1, 22, 0, 30, 30, 0));
        ObjectDefinition definition = object(1);
        definition.setInteractType(interactType);
        FullCollisionMap map = FullCollisionMap.generate(List.of(region), id -> definition);
        assertEquals(expected, map.flag(30, 30, 0).orElseThrow());
    }

    @Test
    void hollowAndNonInteractiveObjectsDoNotClip() {
        Region region = region(0);
        region.getLocations().add(location(1, 0, 0, 30, 30, 0));
        region.getLocations().add(location(2, 10, 0, 31, 31, 0));
        ObjectDefinition hollow = object(1);
        hollow.setHollow(true);
        ObjectDefinition nonInteractive = object(2);
        nonInteractive.setInteractType(0);
        FullCollisionMap map = FullCollisionMap.generate(List.of(region), id -> id == 1 ? hollow : nonInteractive);
        assertEquals(0, map.flag(30, 30, 0).orElseThrow());
        assertEquals(0, map.flag(29, 30, 0).orElseThrow());
        assertEquals(0, map.flag(31, 31, 0).orElseThrow());
    }

    @Test
    void doorNamesWithoutPassableActionsStayBlocked() {
        Region region = region(0);
        region.getLocations().add(location(1, 0, 0, 30, 30, 0));
        ObjectDefinition door = object(1);
        door.setName("Door");
        FullCollisionMap map = FullCollisionMap.generate(List.of(region), id -> door);
        assertEquals(128, map.flag(30, 30, 0).orElseThrow());
        assertEquals(8, map.flag(29, 30, 0).orElseThrow());
    }

    @ParameterizedTest
    @CsvSource({"1540, Open, 0", "1540, Close, 0", "20925, Open, 65664", "30364, Open, 65664"})
    void explicitBlockersOverrideDoorActionsAcrossRegionBoundaries(int id, String action, int expected) {
        Region west = region(0);
        Region east = region(256);
        east.getLocations().add(location(id, 0, 0, 64, 30, 0));
        ObjectDefinition door = object(id);
        door.setName("Door");
        door.getOps().setOp(0, action);
        door.setBlocksProjectile(true);
        FullCollisionMap map = FullCollisionMap.generate(List.of(west, east), ignored -> door);

        assertEquals(expected, map.flag(64, 30, 0).orElseThrow());
        assertEquals(expected == 0 ? 0 : 0x1008, map.flag(63, 30, 0).orElseThrow());
    }

    @ParameterizedTest
    @CsvSource({"26679, 10", "26680, 11", "4785, 0", "1593, 0"})
    void explicitPassableObjectsDoNotEraseOverlappingBlockers(int id, int shape) {
        Region region = region(0);
        Location excluded = location(id, shape, 0, 30, 30, 0);
        Location wall = location(1, 0, 1, 30, 30, 0);
        ObjectDefinition definition = object(id);
        // Even clipping definitions with projectile blocking must respect the pathable override.
        definition.setBlocksProjectile(true);
        definition.setSizeX(2);
        for (List<Location> order : List.of(List.of(wall, excluded), List.of(excluded, wall))) {
            region.getLocations().clear();
            region.getLocations().addAll(order);
            FullCollisionMap map = FullCollisionMap.generate(List.of(region),
                    objectId -> objectId == id ? definition : object(objectId));
            assertEquals(2, map.flag(30, 30, 0).orElseThrow());
            assertEquals(32, map.flag(30, 31, 0).orElseThrow());
            assertEquals(0, map.flag(29, 30, 0).orElseThrow());
            assertEquals(0, map.flag(31, 30, 0).orElseThrow());
        }
    }

    @ParameterizedTest
    @CsvSource({"10060, 0, 1, true, 128", "25636, 9, 0, false, 256"})
    void explicitBlockersOverrideCacheClippingExemptions(int id, int shape, int interactType,
                                                       boolean hollow, int expected) {
        Region region = region(0);
        region.getLocations().add(location(id, shape, 0, 30, 30, 0));
        ObjectDefinition definition = object(id);
        definition.setInteractType(interactType);
        definition.setHollow(hollow);
        FullCollisionMap map = FullCollisionMap.generate(List.of(region), ignored -> definition);

        assertEquals(expected, map.flag(30, 30, 0).orElseThrow());
        assertEquals(shape == 0 ? 8 : 0, map.flag(29, 30, 0).orElseThrow());
        assertEquals(interactType, definition.getInteractType());
        assertEquals(hollow, definition.isHollow());
    }

    @Test
    void floorsUseTheBlockingBitAndBridgeCollisionPlane() {
        MapDefinition terrain = terrain();
        terrain.getTiles()[0][10][10].settings = 17;
        terrain.getTiles()[0][11][10].settings = 2;
        terrain.getTiles()[0][12][10].settings = 1;
        terrain.getTiles()[1][12][10].settings = 2;
        terrain.getTiles()[1][13][10].settings = 3;
        terrain.getTiles()[1][14][10].settings = 2;
        terrain.getTiles()[2][14][10].settings = 1;
        Region region = new Region(0);
        region.loadTerrain(terrain);
        FullCollisionMap map = FullCollisionMap.generate(List.of(region), id -> object(id));

        assertEquals(0x200000, map.flag(10, 10, 0).orElseThrow());
        assertEquals(0, map.flag(11, 10, 0).orElseThrow());
        // The plane-zero floor below a bridge has no collision plane; it is not clamped to zero.
        assertEquals(0, map.flag(12, 10, 0).orElseThrow());
        assertEquals(0x200000, map.flag(13, 10, 0).orElseThrow());
        assertEquals(0, map.flag(13, 10, 1).orElseThrow());
        assertEquals(0x200000, map.flag(14, 10, 1).orElseThrow());
    }

    @Test
    void objectsUnderAndOnBridgesUseTheSamePlaneRule() {
        MapDefinition terrain = terrain();
        terrain.getTiles()[1][30][30].settings = 2;
        Region region = new Region(0);
        region.loadTerrain(terrain);
        region.getLocations().add(location(1, 10, 0, 30, 30, 0));
        region.getLocations().add(location(2, 0, 0, 30, 30, 1));
        FullCollisionMap map = FullCollisionMap.generate(List.of(region), id -> object(id));
        assertEquals(128, map.flag(30, 30, 0).orElseThrow());
        assertEquals(8, map.flag(29, 30, 0).orElseThrow());
        assertEquals(0, map.flag(30, 30, 1).orElseThrow());
    }

    @Test
    void unavailableRegionsStayDistinctFromKnownOpenTiles() {
        Region region = region(256);
        region.getLocations().add(location(1, 0, 0, 64, 30, 0));
        FullCollisionMap map = FullCollisionMap.generate(List.of(region), id -> object(id));
        assertEquals(0, map.flag(65, 30, 0).orElseThrow());
        assertTrue(map.flag(63, 30, 0).isEmpty());
        assertTrue(map.flag(-1, 30, 0).isEmpty());
        assertTrue(map.flag(16384, 30, 0).isEmpty());
        assertTrue(map.flag(65, 30, 4).isEmpty());
    }

    @Test
    void missingObjectDefinitionsFailRatherThanProducingOpenTerrain() {
        Region region = region(0);
        region.getLocations().add(location(99, 10, 0, 30, 30, 0));
        assertThrows(IllegalArgumentException.class, () -> FullCollisionMap.generate(List.of(region), id -> null));
    }

    @Test
    void regionFormatMatchesTitanHeaderChecksumAndBigEndianTileOrder() throws Exception {
        Region first = region(0);
        Region last = region(0x1234);
        last.getLocations().add(location(1, 22, 0, 0x12 * 64 + 6, 0x34 * 64 + 8, 3));
        ObjectDefinition definition = object(1);
        definition.setInteractType(1);
        FullCollisionMap map = FullCollisionMap.generate(List.of(last, first), id -> definition);
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        map.writeRegion(0x1234, output);
        byte[] bytes = output.toByteArray();

        assertArrayEquals(new byte[]{'R', 'E', 'G', 'C', 0, 0, 0, 1, 0, 0, 0, 0}, Arrays.copyOf(bytes, 12));
        ByteBuffer data = ByteBuffer.wrap(bytes).order(ByteOrder.BIG_ENDIAN);
        assertEquals(65572, bytes.length);
        CRC32 crc = new CRC32();
        crc.update(bytes, 36, 65536);
        assertEquals(crc.getValue(), Integer.toUnsignedLong(data.getInt(12)));
        assertEquals(0x1234, data.getInt(16));
        assertEquals(0x12, data.getShort(20));
        assertEquals(0x34, data.getShort(22));
        assertEquals(0x480, data.getInt(24));
        assertEquals(0xd00, data.getInt(28));
        assertEquals(0, data.getInt(32));
        int flagOffset = 36 + ((3 * 64 + 6) * 64 + 8) * 4;
        assertEquals(0x40000, data.getInt(flagOffset));
        assertArrayEquals(new byte[]{0, 4, 0, 0}, Arrays.copyOfRange(bytes, flagOffset, flagOffset + 4));
        ByteArrayOutputStream reordered = new ByteArrayOutputStream();
        FullCollisionMap.generate(List.of(first, last), id -> definition).writeRegion(0x1234, reordered);
        assertArrayEquals(bytes, reordered.toByteArray());
        assertThrows(IllegalArgumentException.class, () -> map.writeRegion(1, new ByteArrayOutputStream()));
    }

    private static Region region(int id) {
        Region region = new Region(id);
        region.loadTerrain(terrain());
        return region;
    }

    private static MapDefinition terrain() {
        MapDefinition map = new MapDefinition();
        for (int z = 0; z < 4; z++) {
            for (int x = 0; x < 64; x++) {
                for (int y = 0; y < 64; y++) {
                    map.getTiles()[z][x][y] = new MapDefinition.Tile();
                }
            }
        }
        return map;
    }

    private static ObjectDefinition object(int id) {
        ObjectDefinition definition = new ObjectDefinition();
        definition.setId(id);
        definition.setBlocksProjectile(false);
        return definition;
    }

    private static Location location(int id, int shape, int rotation, int x, int y, int plane) {
        return new Location(id, shape, rotation, new Position(x, y, plane));
    }
}
