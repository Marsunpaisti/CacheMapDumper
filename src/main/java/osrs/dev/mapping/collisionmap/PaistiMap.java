package osrs.dev.mapping.collisionmap;

import osrs.dev.mapping.tiledatamap.ITileDataMap;

/**
 * Generic collision map backed by any ITileDataMap implementation.
 * Maps NORTH and EAST direction bits with inverted semantics.
 *
 * Data bit SET = WALKABLE
 * Interface returns pathable=true when bit is NOT set
 */
public class WalkabilityMap implements ICollisionMap {
    static final int NORTH_DATA_BIT_POS = 0;
    static final int EAST_DATA_BIT_POS = 1;

    private final ITileDataMap dataMap;

    public WalkabilityMap(ITileDataMap dataMap) {
        this.dataMap = dataMap;
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
