package osrs.dev.dumper;

import net.runelite.cache.definitions.OverlayDefinition;
import net.runelite.cache.region.Location;
import net.runelite.cache.region.Region;
import osrs.dev.mapping.collisionmap.ICollisionMap;
import osrs.dev.mapping.tiletypemap.TileType;

import java.util.Set;
import java.util.function.BiFunction;
import java.util.function.IntFunction;

public final class TileTypeClassifier {
    // Object IDs that represent walkable floorboards - these should not be marked as water
    private static final Set<Integer> WALKABLE_FLOORBOARD_IDS = Set.of(
            270, 4747, 9453, 9454, 12437, 59923, 59924, 59926, 59927
    );
    private static final int[][] CARDINAL_OFFSETS = {{0, 1}, {1, 0}, {0, -1}, {-1, 0}};

    private final BiFunction<Integer, Integer, Region> regions;
    private final IntFunction<OverlayDefinition> overlays;
    private final ICollisionMap collisionMap;

    /**
     * Collision must include every region and object before shoreline classification begins.
     */
    public TileTypeClassifier(BiFunction<Integer, Integer, Region> regions,
                              IntFunction<OverlayDefinition> overlays, ICollisionMap collisionMap) {
        this.regions = regions;
        this.overlays = overlays;
        this.collisionMap = collisionMap;
    }

    public byte getTileType(int x, int y, int plane) {
        TerrainTile tile = readTerrain(x, y, plane);
        if (tile.type == 0 || !tile.partialOverlay || !tile.hasUnderlay || tile.blockedFloor) {
            return tile.type;
        }

        boolean[] openEdges = {
                collisionMap.pathableNorth(x, y, plane), collisionMap.pathableEast(x, y, plane),
                collisionMap.pathableSouth(x, y, plane), collisionMap.pathableWest(x, y, plane)
        };
        boolean connectsToLand = false;
        for (int direction = 0; direction < CARDINAL_OFFSETS.length; direction++) {
            if (!openEdges[direction]) continue;
            int[] offset = CARDINAL_OFFSETS[direction];
            // Raw terrain prevents filtered water bodies and corrected shorelines from supplying land evidence.
            TerrainTile neighbour = readTerrain(x + offset[0], y + offset[1], plane);
            if (!neighbour.hasFloor || neighbour.blockedFloor || neighbour.type > 0) {
                return tile.type;
            }
            connectsToLand = true;
        }
        return connectsToLand ? 0 : tile.type;
    }

    private TerrainTile readTerrain(int x, int y, int plane) {
        if (x < 0 || y < 0 || x > 16383 || y > 16383 || plane < 0 || plane >= Region.Z) {
            return TerrainTile.UNKNOWN;
        }
        Region region = regions.apply(x, y);
        if (region == null) return TerrainTile.UNKNOWN;
        int localX = x - region.getBaseX();
        int localY = y - region.getBaseY();
        int effectivePlane = ProcessingUtils.getEffectivePlane(region, localX, localY, plane);
        int overlayId = region.getOverlayId(effectivePlane, localX, localY);
        int underlayId = region.getUnderlayId(effectivePlane, localX, localY);
        OverlayDefinition overlay = overlayId > 0 ? overlays.apply(overlayId - 1) : null;
        if (overlayId > 0 && overlay == null) return TerrainTile.UNKNOWN;
        int texture = overlay == null ? -1 : overlay.getTexture();
        boolean blockedFloor = (region.getTileSetting(effectivePlane, localX, localY) & 1) != 0;
        if (texture == TileType.NEVER_TRAVERSABLE_SPRITE_ID) return TerrainTile.UNKNOWN;
        byte type = TileType.SPRITE_ID_TO_TILE_TYPE.getOrDefault(texture, (byte) 0);
        if (type > 0 && hasWalkableFloorboard(region, x, y, effectivePlane)) {
            type = 0;
        }
        return new TerrainTile(type, underlayId > 0, underlayId > 0 || overlayId > 0,
                overlayId > 0 && region.getOverlayPath(effectivePlane, localX, localY) > 0, blockedFloor);
    }

    /**
     * Checks if there is a walkable floorboard object at the given coordinate.
     */
    private boolean hasWalkableFloorboard(Region region, int x, int y, int plane) {
        for (Location location : region.getLocations()) {
            var position = location.getPosition();
            if (position.getX() == x && position.getY() == y && position.getZ() == plane
                    && WALKABLE_FLOORBOARD_IDS.contains(location.getId())) {
                return true;
            }
        }
        return false;
    }

    private static final class TerrainTile {
        private static final TerrainTile UNKNOWN = new TerrainTile((byte) 0, false, false, false, false);

        private final byte type;
        private final boolean hasUnderlay;
        private final boolean hasFloor;
        private final boolean partialOverlay;
        private final boolean blockedFloor;

        private TerrainTile(byte type, boolean hasUnderlay, boolean hasFloor, boolean partialOverlay,
                            boolean blockedFloor) {
            this.type = type;
            this.hasUnderlay = hasUnderlay;
            this.hasFloor = hasFloor;
            this.partialOverlay = partialOverlay;
            this.blockedFloor = blockedFloor;
        }
    }
}
