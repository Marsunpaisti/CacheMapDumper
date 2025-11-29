package osrs.dev.mapping.collisionmap;

import lombok.extern.slf4j.Slf4j;
import osrs.dev.mapping.ConfigurableCoordIndexer;
import osrs.dev.mapping.tiledatamap.ITileDataMap;
import osrs.dev.mapping.tiledatamap.roaring.RoaringTileDataMap;

import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.List;
import java.util.zip.GZIPInputStream;

/**
 * Generic collision map backed by any ITileDataMap implementation.
 * Maps NORTH and EAST direction bits with inverted semantics.
 * Data bit SET = WALKABLE
 * Interface returns pathable=true when bit IS set
 */
@Slf4j
public class PaistiMap implements ICollisionMap {
    static final int NORTH_DATA_BIT_POS = 0;
    static final int EAST_DATA_BIT_POS = 1;

    private final ITileDataMap dataMap;

    PaistiMap(ITileDataMap map) throws IOException {
        dataMap = map;
    }

    public static PaistiMap load(String filePath) throws IOException {
        ITileDataMap map;
        try (FileInputStream fis = new FileInputStream(filePath);
             InputStream inputStream = new GZIPInputStream(fis)) {

            map = RoaringTileDataMap.load(
                    inputStream,
                    ConfigurableCoordIndexer.builder()
                            .xBits(14)
                            .yBits(14)
                            .planeBits(2)
                            .build()
            );
        }

        return new PaistiMap(map);
    }

    private static class KeepArea {
        final int minX, minY, maxX, maxY, plane;

        KeepArea(int x1, int y1, int x2, int y2, int plane) {
            this.minX = Math.min(x1, x2);
            this.minY = Math.min(y1, y2);
            this.maxX = Math.max(x1, x2);
            this.maxY = Math.max(y1, y2);
            this.plane = plane;
        }

        boolean contains(int x, int y, int p) {
            return p == plane && x >= minX && x <= maxX && y >= minY && y <= maxY;
        }
    }


    private static final List<KeepArea> keepAreas = List.of(
            new KeepArea(2808, 2802, 2698, 2713, 0), // Ape Atoll
            new KeepArea(1626, 3522, 1710, 3600, 0), // Forthos Ruin
            new KeepArea(2446, 9733, 2526, 9703, 0), // UG Pass bridge area
            new KeepArea(2464, 9670, 2487, 9710, 0), // UG Pass spike puzzle area
            new KeepArea(2505, 3460, 2516, 3465, 0), // Baxtorian falls tile
            new KeepArea(2684, 9030, 2291, 9318, 1),  // Kruk Caves upper floor
            new KeepArea(1629, 3123, 1669, 3093, 0), // Varlamore thieving area
            new KeepArea(1790, 4790, 1835, 4865, 0), // Cabin fever quest boats plane 0
            new KeepArea(1790, 4790, 1835, 4865, 1), // Cabin fever quest boats plane 1
            new KeepArea(1790, 4790, 1835, 4865, 2) // Cabin fever quest boats plane 2
    );

    /**
     * Check if the given coordinates fall within any of the defined Paisti 'keep areas'.
     * I.e. the tile collision should be copied from the Paisti map instead of cache.
     */
    public boolean shouldKeepPaistiData(int x, int y, int plane) {
        for (KeepArea area : keepAreas) {
            if (area.contains(x, y, plane)) {
                return true;
            }
        }
        return false;
    }

    /**
     * Override the tile collision data in the provided CollisionMapWriter
     * if the tile is within the defined Paisti 'keep areas' to take from the paisti map.
     */
    public boolean overrideTileCollisionIfApplicable(CollisionMapWriter collisionMapWriter, int regionX, int regionY, int plane) {
        if (!shouldKeepPaistiData(regionX, regionY, plane)) return false;

        boolean pathableNorth = pathableNorth(regionX, regionY, plane);
        boolean pathableEast = pathableEast(regionX, regionY, plane);
        collisionMapWriter.setPathableNorth(regionX, regionY, plane, pathableNorth);
        collisionMapWriter.setPathableEast(regionX, regionY, plane, pathableEast);
        return true;
    }

    @Override
    public boolean pathableNorth(int x, int y, int plane) {
        return dataMap.isDataBitSet(x, y, plane, NORTH_DATA_BIT_POS);
    }

    @Override
    public boolean pathableEast(int x, int y, int plane) {
        return dataMap.isDataBitSet(x, y, plane, EAST_DATA_BIT_POS);
    }

}
