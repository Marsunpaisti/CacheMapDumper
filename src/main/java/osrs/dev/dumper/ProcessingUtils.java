package osrs.dev.dumper;

import net.runelite.cache.definitions.ObjectDefinition;
import net.runelite.cache.region.Region;

/**
 * Static utility methods for tile and object processing logic.
 * Shared between collision processing and debug output to ensure consistency.
 */
public final class ProcessingUtils {

    private ProcessingUtils() {} // Utility class

    // ===== BRIDGE/PLANE UTILITIES =====

    /**
     * Check if tile at (localX, localY) is a bridge based on plane 1 tile settings.
     * Bridge flag is bit 1 (value 2) in tile settings.
     */
    public static boolean isBridge(Region region, int localX, int localY) {
        return (region.getTileSetting(1, localX, localY) & 2) != 0;
    }

    /**
     * Get the effective tile Z (plane offset by bridge).
     * If the tile is a bridge, the effective Z is plane + 1.
     */
    public static int getEffectiveTileZ(Region region, int localX, int localY, int plane) {
        return plane + (isBridge(region, localX, localY) ? 1 : 0);
    }

    /**
     * Get effective plane for floor/overlay lookups (capped at plane 3).
     * This handles bridge offset but ensures we don't exceed plane bounds.
     */
    public static int getEffectivePlane(Region region, int localX, int localY, int plane) {
        int tileZ = getEffectiveTileZ(region, localX, localY, plane);
        return plane < 3 ? tileZ : plane;
    }

    // ===== FLOOR UTILITIES =====

    /**
     * Check if tile has no floor (no underlay and no overlay).
     * Tiles with no floor are considered blocked.
     */
    public static boolean hasNoFloor(Region region, int localX, int localY, int effectivePlane) {
        int underlayId = region.getUnderlayId(effectivePlane, localX, localY);
        int overlayId = region.getOverlayId(effectivePlane, localX, localY);
        return underlayId == 0 && overlayId == 0;
    }

    /**
     * Check if floor type causes blocking.
     * Blocking floor types:
     *   1 = water, rooftop wall
     *   3 = bridge wall
     *   5 = house wall/roof
     *   7 = house wall
     */
    public static boolean isBlockingFloorType(int floorType) {
        return floorType == 1 || floorType == 3 || floorType == 5 || floorType == 7;
    }

    /**
     * Check if tile would be blocked due to floor conditions.
     * A tile is blocked if it has no floor OR has a blocking floor type.
     */
    public static boolean isNoFloorOrBlockingFloor(Region region, int localX, int localY, int effectivePlane) {
        boolean noFloor = hasNoFloor(region, localX, localY, effectivePlane);
        if (noFloor) return true;
        int floorType = region.getTileSetting(effectivePlane, localX, localY);
        return isBlockingFloorType(floorType);
    }

    // ===== OBJECT NAME DETECTION =====

    /**
     * Check if object name indicates it's door-like (door, gate, curtain).
     * Returns false if there's an exclusion override for this object.
     */
    public static boolean isDoorLike(ObjectDefinition obj, Boolean exclusion) {
        if (exclusion != null) return false; // Exclusions override door detection
        String name = obj.getName().toLowerCase();

        // Trapdoors have "door" in the name but are not considered doors in the sense that they still block collision
        if (name.contains("trapdoor")) return false;

        return name.contains("door") || name.contains("gate") || name.contains("curtain");
    }

    /**
     * Check if object name indicates it's a trapdoor.
     * Trapdoors always block regardless of door-like actions.
     */
    public static boolean isTrapdoor(ObjectDefinition obj) {
        return obj.getName().toLowerCase().contains("trapdoor");
    }

    // ===== ACTION DETECTION =====

    /**
     * Door-like action strings that indicate an object can be passed through.
     */
    private static final String[] DOOR_LIKE_ACTIONS = {
        "Open", "Close", "Push", "Push-through", "Walk-through",
        "Go-through", "Pass", "Escape", "Quick-exit"
    };

    /**
     * Check if object has a door-like action.
     * @return the matched action string, or null if no match
     */
    public static String getMatchedDoorLikeAction(ObjectDefinition obj) {
        String[] actions = obj.getActions();
        if (actions == null) return null;
        for (String action : actions) {
            if (action == null) continue;
            for (String doorAction : DOOR_LIKE_ACTIONS) {
                if (action.equalsIgnoreCase(doorAction)) {
                    return action;
                }
            }
        }
        return null;
    }

    /**
     * Check if object has any door-like action.
     */
    public static boolean hasDoorLikeAction(ObjectDefinition obj) {
        return getMatchedDoorLikeAction(obj) != null;
    }

    // ===== COMBINED BLOCKING LOGIC =====

    /**
     * Check if object is considered a passable door.
     * Must have both a door-like name AND a door-like action.
     */
    public static boolean isConsideredDoor(ObjectDefinition obj, Boolean exclusion) {
        return isDoorLike(obj, exclusion) && hasDoorLikeAction(obj);
    }

    /**
     * Compute final shouldBlock decision for an object.
     * @return true if object should block movement
     */
    public static boolean shouldBlock(ObjectDefinition obj, int objectId) {
        Boolean exclusion = Exclusion.matches(objectId);

        // Exclusion override: FALSE = explicit force block, TRUE = explicitly pathable (handled elsewhere)
        if (exclusion != null) {
            return Boolean.FALSE.equals(exclusion);
        }

        // Door logic: doors with door-like actions don't block
        return !isConsideredDoor(obj, exclusion);
    }

    /**
     * Check if an object should be skipped entirely during processing.
     * Objects with exclusion = TRUE are skipped.
     */
    public static boolean isExcludedObject(int objectId) {
        Boolean exclusion = Exclusion.matches(objectId);
        return exclusion != null && Boolean.TRUE.equals(exclusion);
    }

    /**
     * All credit for this goes to Kris (github.com/Z-Kris) for spoonfeeding this logic
     * Checks if an object does not affect collision based on its type and interactType.
     * This is an early-return optimization based on OSRS collision clipping rules:
     * - Objects with ID >= 0xFFFF (65535) do not clip
     * - GroundDecor (type 22) only clips if interactType == 1
     * - All other types only clip if interactType != 0
     *
     * @param objectId the object ID
     * @param type the location type (MapObjectShape ordinal)
     * @param interactType the object's interactType from its definition
     * @return true if the object does NOT affect collision (should be skipped when adding collision info)
     */
    public static boolean isNonClipping(int objectId, int type, int interactType) {
        // Objects with ID >= 0xFFFF don't clip or dont exist - idk
        if (objectId >= 0xFFFF) {
            return true;
        }

        // GroundDecor (type 22) only clips if interactType == 1
        if (type == MapObjectShape.GroundDecor.getType()) {
            return interactType != 1;
        }

        // All other types only clip if interactType != 0
        return interactType == 0;
    }

    /**
     * Get calculated X size after rotation.
     * Orientations 1 and 3 swap X and Y sizes.
     */
    public static int getRotatedSizeX(ObjectDefinition obj, int orientation) {
        return (orientation == 1 || orientation == 3) ? obj.getSizeY() : obj.getSizeX();
    }

    /**
     * Get calculated Y size after rotation.
     * Orientations 1 and 3 swap X and Y sizes.
     */
    public static int getRotatedSizeY(ObjectDefinition obj, int orientation) {
        return (orientation == 1 || orientation == 3) ? obj.getSizeX() : obj.getSizeY();
    }

    /**
     * Check if object type is diagonal wall (9).
     * Diagonal walls apply full blocking.
     */
    public static boolean isDiagonalWall(int type) {
        return type == 9;
    }

    /**
     * Check if object type applies full blocking (9-22 range objects).
     */
    public static boolean isFullBlockingObjectType(int type) {
        return type == 22 || (type >= 9 && type <= 11) || (type >= 12 && type <= 21);
    }

    /**
     * Check if object would apply blocking based on interactType and wallOrDoor.
     * Used for type 10-22 objects.
     */
    public static boolean appliesObjectBlocking(ObjectDefinition obj, int type) {
        return obj.getInteractType() != 0 &&
               (obj.getWallOrDoor() == 1 || (type >= 10 && type <= 21));
    }

    /**
     * Get blocked direction string for wall type based on orientation.
     * For type 0 and 2 walls.
     */
    public static String getWallBlockedDirection(int type, int orientation) {
        if (type == 0 || type == 2) {
            switch (orientation) {
                case 0: return "West";
                case 1: return "North";
                case 2: return "East";
                case 3: return "South";
            }
        }
        return "Unknown";
    }

    /**
     * Get secondary blocked direction for double walls (type 2).
     */
    public static String getDoubleWallSecondaryDirection(int orientation) {
        switch (orientation) {
            case 0: return "North";
            case 1: return "East";
            case 2: return "South";
            case 3: return "West";
        }
        return "Unknown";
    }

    /**
     * Get a human-readable description of an object type.
     */
    public static String getTypeDescription(int type) {
        if (type == 0) return "Wall";
        if (type == 1) return "Diagonal wall corner";
        if (type == 2) return "Double wall (corner)";
        if (type == 3) return "Diagonal wall";
        if (type >= 4 && type <= 8) return "Wall decoration";
        if (type == 9) return "Diagonal interactable";
        if (type == 10 || type == 11) return "Interactable object";
        if (type >= 12 && type <= 21) return "Roof/support";
        if (type == 22) return "Floor decoration";
        return "Unknown type " + type;
    }
}
