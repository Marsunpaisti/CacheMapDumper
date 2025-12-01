package osrs.dev.postprocessor;

import osrs.dev.mapping.CacheEfficientCoordIndexer;
import osrs.dev.mapping.collisionmap.ICollisionMap;
import osrs.dev.mapping.tiletypemap.TileTypeMap;

/**
 * Checks whether a boat of a given size can fit centered at a specific tile.
 * <p>
 * For odd sizes (e.g., 3): checks a single NxN area centered at the anchor.
 * For even sizes (e.g., 4): checks all 4 possible center positions and returns
 * true if ANY of them satisfy the fit criteria.
 * <p>
 * Fit criteria:
 * <ul>
 *   <li>All tiles in the NxN area must be water (getTileType > 0)</li>
 *   <li>Edge tiles must be pathable toward the center</li>
 *   <li>Interior tiles must be fully pathable in all 4 directions</li>
 * </ul>
 */
public class BoatFitChecker {
    private static final CacheEfficientCoordIndexer INDEXER =
            CacheEfficientCoordIndexer.SEQUENTIAL_COORD_INDEXER_2_ADDRESSES;

    private final ICollisionMap collisionMap;
    private final TileTypeMap tileTypeMap;
    private final int boatSize;

    // Coordinate bounds
    private final int coordMinX;
    private final int coordMaxX;
    private final int coordMinY;
    private final int coordMaxY;
    private final int coordMinPlane;
    private final int coordMaxPlane;

    public BoatFitChecker(ICollisionMap collisionMap, TileTypeMap tileTypeMap, int boatSize) {
        if (boatSize < 1) {
            throw new IllegalArgumentException("Boat size must be at least 1");
        }
        this.collisionMap = collisionMap;
        this.tileTypeMap = tileTypeMap;
        this.boatSize = boatSize;

        // Get coordinate bounds from the indexer
        this.coordMinX = INDEXER.getMinX();
        this.coordMaxX = INDEXER.getMaxX();
        this.coordMinY = INDEXER.getMinY();
        this.coordMaxY = INDEXER.getMaxY();
        this.coordMinPlane = INDEXER.getMinPlane();
        this.coordMaxPlane = INDEXER.getMaxPlane();
    }

    /**
     * Checks if a boat can be centered at the given coordinate.
     */
    public boolean canFitAt(int x, int y, int plane) {
        if (boatSize % 2 == 1) {
            // Odd size: single check with true center
            int radius = boatSize / 2;
            int minX = x - radius;
            int minY = y - radius;
            return checkBoatArea(minX, minY, plane);
        } else {
            // Even size: check all 4 center variants, return true if ANY passes
            int halfSize = boatSize / 2;

            // SW variant: anchor is SW corner of center 2x2
            if (checkBoatArea(x - (halfSize - 1), y - (halfSize - 1), plane)) {
                return true;
            }
            // SE variant: anchor is SE corner of center 2x2
            if (checkBoatArea(x - halfSize, y - (halfSize - 1), plane)) {
                return true;
            }
            // NW variant: anchor is NW corner of center 2x2
            if (checkBoatArea(x - (halfSize - 1), y - halfSize, plane)) {
                return true;
            }
            // NE variant: anchor is NE corner of center 2x2
            if (checkBoatArea(x - halfSize, y - halfSize, plane)) {
                return true;
            }
            return false;
        }
    }

    /**
     * Checks all tiles in the boat area starting from (minX, minY).
     */
    private boolean checkBoatArea(int minX, int minY, int plane) {
        int maxX = minX + boatSize - 1;
        int maxY = minY + boatSize - 1;

        // Check if boat area is within valid coordinate bounds
        if (minX < coordMinX || maxX > coordMaxX ||
            minY < coordMinY || maxY > coordMaxY ||
            plane < coordMinPlane || plane > coordMaxPlane) {
            return false;
        }

        for (int tileX = minX; tileX <= maxX; tileX++) {
            for (int tileY = minY; tileY <= maxY; tileY++) {
                // Check water
                if (tileTypeMap.getTileType(tileX, tileY, plane) <= 0) {
                    return false;
                }

                // Check pathability based on position relative to boat edges
                if (!checkTilePathability(tileX, tileY, plane, minX, minY, maxX, maxY)) {
                    return false;
                }
            }
        }
        return true;
    }

    /**
     * Checks pathability for a tile based on its position within the boat footprint.
     * Edge tiles only need to be pathable toward the center.
     * Interior tiles need to be fully pathable in all 4 directions.
     */
    private boolean checkTilePathability(int tileX, int tileY, int plane,
                                         int minX, int minY, int maxX, int maxY) {
        boolean isWestEdge = (tileX == minX);
        boolean isEastEdge = (tileX == maxX);
        boolean isSouthEdge = (tileY == minY);
        boolean isNorthEdge = (tileY == maxY);

        boolean isInterior = !isWestEdge && !isEastEdge && !isSouthEdge && !isNorthEdge;

        if (isInterior) {
            // Interior: fully pathable in all directions
            return collisionMap.pathableNorth(tileX, tileY, plane) &&
                   collisionMap.pathableEast(tileX, tileY, plane) &&
                   collisionMap.pathableSouth(tileX, tileY, plane) &&
                   collisionMap.pathableWest(tileX, tileY, plane);
        }

        // Edge/Corner tiles: must be pathable toward center
        // NE corner
        if (isNorthEdge && isEastEdge) {
            return collisionMap.pathableSouth(tileX, tileY, plane) &&
                   collisionMap.pathableWest(tileX, tileY, plane);
        }
        // NW corner
        if (isNorthEdge && isWestEdge) {
            return collisionMap.pathableSouth(tileX, tileY, plane) &&
                   collisionMap.pathableEast(tileX, tileY, plane);
        }
        // SE corner
        if (isSouthEdge && isEastEdge) {
            return collisionMap.pathableNorth(tileX, tileY, plane) &&
                   collisionMap.pathableWest(tileX, tileY, plane);
        }
        // SW corner
        if (isSouthEdge && isWestEdge) {
            return collisionMap.pathableNorth(tileX, tileY, plane) &&
                   collisionMap.pathableEast(tileX, tileY, plane);
        }
        // N edge (not corner)
        if (isNorthEdge) {
            return collisionMap.pathableSouth(tileX, tileY, plane);
        }
        // S edge (not corner)
        if (isSouthEdge) {
            return collisionMap.pathableNorth(tileX, tileY, plane);
        }
        // E edge (not corner)
        if (isEastEdge) {
            return collisionMap.pathableWest(tileX, tileY, plane);
        }
        // W edge (not corner)
        if (isWestEdge) {
            return collisionMap.pathableEast(tileX, tileY, plane);
        }

        return true; // Should not reach here
    }

    public int getBoatSize() {
        return boatSize;
    }
}
