package osrs.dev.dumper;

import net.runelite.cache.definitions.MapDefinition;
import net.runelite.cache.definitions.OverlayDefinition;
import net.runelite.cache.region.Location;
import net.runelite.cache.region.Position;
import net.runelite.cache.region.Region;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import osrs.dev.mapping.collisionmap.ICollisionMap;
import osrs.dev.mapping.tiletypemap.TileType;

import java.util.HashMap;
import java.util.HashSet;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;

class TileTypeClassifierTest {
    @ParameterizedTest
    @CsvSource({
            "2704, 3209, 442, 62, 3, 0, true",
            "2599, 3337, 6, 63, 6, 2, false",
            "2570, 3259, 6, 63, 9, 1, true"
    })
    void landConnectedShorelinesAreLand(int x, int y, int overlay, int underlay, int path,
                                       int rotation, boolean southIsLand) {
        Fixture fixture = new Fixture();
        // These terrain values and open edges come from independently inspected shoreline samples.
        fixture.tile(x, y, 0, underlay, overlay, path, rotation, 0);
        fixture.land(x - 1, y, 0, underlay);
        fixture.water(x + 1, y, 0, overlay, 0, 1);
        fixture.water(x, y + 1, 0, overlay, 0, 1);
        fixture.collision.allowWest(x, y, 0);
        if (southIsLand) {
            fixture.land(x, y - 1, 0, underlay);
            fixture.collision.allowSouth(x, y, 0);
        } else {
            fixture.water(x, y - 1, 0, overlay, 0, 1);
        }

        assertEquals(0, fixture.classifier().getTileType(x, y, 0));
    }

    @Test
    void fullWaterCoverageRemainsWaterEvenWhenConnectedToLand() {
        Fixture fixture = landConnectedTransition();
        fixture.water(31, 31, 0, 442, 0, 0);

        assertEquals(TileType.WATER, fixture.classifier().getTileType(31, 31, 0));
    }

    @ParameterizedTest
    @ValueSource(ints = {1, 3, 5, 7, 9, 17})
    void blockedFloorTransitionsRemainWater(int settings) {
        Fixture fixture = landConnectedTransition();
        fixture.water(31, 31, 0, 442, 3, settings);

        assertEquals(TileType.WATER, fixture.classifier().getTileType(31, 31, 0));
    }

    @Test
    void partialOverlayWithoutUnderlayIsNotEvidenceOfLand() {
        Fixture fixture = landConnectedTransition();
        fixture.tile(31, 31, 0, 0, 442, 3, 0, 0);

        assertEquals(TileType.WATER, fixture.classifier().getTileType(31, 31, 0));
    }

    @Test
    void landNeighbourBehindBlockedEdgeDoesNotChangeWaterType() {
        Fixture fixture = new Fixture();
        fixture.water(31, 31, 0, 442, 3, 0);
        fixture.land(30, 31, 0, 62);

        assertEquals(TileType.WATER, fixture.classifier().getTileType(31, 31, 0));
    }

    @Test
    void diagonalLandDoesNotSupplyOrthogonalLandEvidence() {
        Fixture fixture = new Fixture();
        fixture.water(31, 31, 0, 442, 3, 0);
        fixture.land(30, 30, 0, 62);

        assertEquals(TileType.WATER, fixture.classifier().getTileType(31, 31, 0));
    }

    @Test
    void connectionToWaterOnlyRemainsWater() {
        Fixture fixture = new Fixture();
        fixture.water(31, 31, 0, 442, 3, 0);
        fixture.water(32, 31, 0, 442, 0, 0);
        fixture.collision.allowEast(31, 31, 0);

        assertEquals(TileType.WATER, fixture.classifier().getTileType(31, 31, 0));
    }

    @Test
    void connectionToBothLandAndWaterRemainsAmbiguous() {
        Fixture fixture = landConnectedTransition();
        // Unblocked water must remain water evidence even if a later small-body filter would remove it.
        fixture.water(32, 31, 0, 6, 0, 0);
        fixture.collision.allowEast(31, 31, 0);

        assertEquals(TileType.WATER, fixture.classifier().getTileType(31, 31, 0));
    }

    @Test
    void adjoiningTransitionsCannotEstablishEachOtherAsLand() {
        Fixture fixture = landConnectedTransition();
        fixture.water(32, 31, 0, 442, 9, 0);
        fixture.collision.allowEast(31, 31, 0);
        TileTypeClassifier classifier = fixture.classifier();

        assertEquals(TileType.WATER, classifier.getTileType(31, 31, 0));
        assertEquals(TileType.WATER, classifier.getTileType(32, 31, 0));
        assertEquals(TileType.WATER, classifier.getTileType(31, 31, 0));
    }

    @ParameterizedTest
    @ValueSource(ints = {0, 1, 2, 3})
    void anyOrthogonalLandConnectionCanResolveTransition(int direction) {
        Fixture fixture = new Fixture();
        fixture.water(31, 31, 0, 442, 3, 0);
        int[][] offsets = {{0, 1}, {1, 0}, {0, -1}, {-1, 0}};
        fixture.land(31 + offsets[direction][0], 31 + offsets[direction][1], 0, 62);
        switch (direction) {
            case 0: fixture.collision.allowNorth(31, 31, 0); break;
            case 1: fixture.collision.allowEast(31, 31, 0); break;
            case 2: fixture.collision.allowSouth(31, 31, 0); break;
            case 3: fixture.collision.allowWest(31, 31, 0); break;
            default: throw new IllegalArgumentException();
        }

        assertEquals(0, fixture.classifier().getTileType(31, 31, 0));
    }

    @Test
    void landEvidenceCanCrossRegionBoundary() {
        Fixture fixture = new Fixture();
        fixture.water(64, 31, 0, 442, 3, 0);
        fixture.land(63, 31, 0, 62);
        fixture.collision.allowWest(64, 31, 0);

        assertEquals(0, fixture.classifier().getTileType(64, 31, 0));
    }

    @Test
    void missingRegionDoesNotResolveAmbiguousOpenEdge() {
        Fixture fixture = new Fixture();
        fixture.water(63, 31, 0, 442, 3, 0);
        fixture.land(62, 31, 0, 62);
        fixture.collision.allowWest(63, 31, 0);
        fixture.collision.allowEast(63, 31, 0);

        assertEquals(TileType.WATER, fixture.classifier().getTileType(63, 31, 0));
    }

    @Test
    void missingFloorIsNotLandEvidence() {
        Fixture fixture = landConnectedTransition();
        fixture.collision.allowEast(31, 31, 0);

        assertEquals(TileType.WATER, fixture.classifier().getTileType(31, 31, 0));
    }

    @Test
    void blockedNonWaterNeighbourDoesNotResolveAnOpenEdge() {
        Fixture fixture = landConnectedTransition();
        // Collision overrides can expose an edge without clearing the neighbouring terrain's floor flag.
        fixture.tile(32, 31, 0, 62, 0, 0, 0, 1);
        fixture.collision.allowEast(31, 31, 0);

        assertEquals(TileType.WATER, fixture.classifier().getTileType(31, 31, 0));
    }

    @Test
    void missingOverlayDefinitionIsNotLandEvidence() {
        Fixture fixture = landConnectedTransition();
        fixture.tile(32, 31, 0, 62, 999, 0, 0, 0);
        fixture.collision.allowEast(31, 31, 0);

        assertEquals(TileType.WATER, fixture.classifier().getTileType(31, 31, 0));
    }

    @Test
    void neverTraversableTextureIsNotLandEvidence() {
        Fixture fixture = landConnectedTransition();
        fixture.overlay(500, TileType.NEVER_TRAVERSABLE_SPRITE_ID);
        fixture.tile(32, 31, 0, 62, 500, 0, 0, 0);
        fixture.collision.allowEast(31, 31, 0);

        assertEquals(TileType.WATER, fixture.classifier().getTileType(31, 31, 0));
        assertEquals(0, fixture.classifier().getTileType(32, 31, 0));
    }

    @ParameterizedTest
    @ValueSource(ints = {270, 4747, 9453, 9454, 12437, 59923, 59924, 59926, 59927})
    void walkableFloorboardsRemainLand(int objectId) {
        Fixture fixture = new Fixture();
        fixture.water(31, 31, 0, 442, 0, 1);
        fixture.object(31, 31, 0, objectId);

        assertEquals(0, fixture.classifier().getTileType(31, 31, 0));
    }

    @Test
    void floorboardOnAnotherPlaneDoesNotOverrideWater() {
        Fixture fixture = new Fixture();
        fixture.water(31, 31, 0, 442, 0, 0);
        fixture.object(31, 31, 1, 12437);

        assertEquals(TileType.WATER, fixture.classifier().getTileType(31, 31, 0));
    }

    @Test
    void bridgeUsesRaisedSurfaceInsteadOfWaterUnderneath() {
        Fixture fixture = new Fixture();
        fixture.overlay(42, -1);
        fixture.water(31, 31, 0, 442, 0, 1);
        fixture.tile(31, 31, 1, 0, 42, 0, 0, 2);

        assertEquals(0, fixture.classifier().getTileType(31, 31, 0));
    }

    @Test
    void floorboardExclusionUsesBridgeAdjustedPlane() {
        Fixture fixture = new Fixture();
        fixture.water(31, 31, 1, 442, 0, 2);
        fixture.object(31, 31, 1, 12437);

        assertEquals(0, fixture.classifier().getTileType(31, 31, 0));
    }

    @Test
    void raisedShorelineAndLandNeighbourUseBridgeAdjustedSurfaces() {
        Fixture fixture = new Fixture();
        fixture.water(31, 31, 1, 442, 3, 2);
        fixture.tile(30, 31, 1, 62, 0, 0, 0, 2);
        fixture.collision.allowWest(31, 31, 0);

        assertEquals(0, fixture.classifier().getTileType(31, 31, 0));
    }

    @Test
    void bridgeAdjustmentDoesNotOverflowTopPlane() {
        Fixture fixture = new Fixture();
        fixture.tile(31, 31, 1, 62, 0, 0, 0, 2);
        fixture.water(31, 31, 3, 442, 0, 0);

        assertEquals(TileType.WATER, fixture.classifier().getTileType(31, 31, 3));
    }

    @Test
    void unresolvedSpecialWaterRetainsItsOriginalType() {
        Fixture fixture = landConnectedTransition();
        fixture.overlay(500, 136);
        fixture.water(31, 31, 0, 500, 3, 0);
        fixture.water(32, 31, 0, 442, 0, 0);
        fixture.collision.allowEast(31, 31, 0);

        assertEquals(TileType.TEMPOR_STORM_WATER, fixture.classifier().getTileType(31, 31, 0));
    }

    private static Fixture landConnectedTransition() {
        Fixture fixture = new Fixture();
        fixture.water(31, 31, 0, 442, 3, 0);
        fixture.land(30, 31, 0, 62);
        fixture.collision.allowWest(31, 31, 0);
        return fixture;
    }

    private static final class Fixture {
        private final Map<Integer, MapDefinition> terrain = new HashMap<>();
        private final Map<Integer, Region> regions = new HashMap<>();
        private final Map<Integer, OverlayDefinition> overlays = new HashMap<>();
        private final Map<Integer, List<Location>> objects = new HashMap<>();
        private final TestCollisionMap collision = new TestCollisionMap();

        private Fixture() {
            overlay(442, 1);
            overlay(6, 1);
        }

        private void overlay(int id, int texture) {
            OverlayDefinition definition = new OverlayDefinition();
            definition.setId(id - 1);
            definition.setTexture(texture);
            overlays.put(id - 1, definition);
        }

        private void land(int x, int y, int plane, int underlay) {
            tile(x, y, plane, underlay, 0, 0, 0, 0);
        }

        private void water(int x, int y, int plane, int overlay, int path, int settings) {
            tile(x, y, plane, 62, overlay, path, 0, settings);
        }

        private void tile(int x, int y, int plane, int underlay, int overlay, int path,
                          int rotation, int settings) {
            int regionId = regionId(x, y);
            MapDefinition definition = terrain.computeIfAbsent(regionId, ignored -> emptyTerrain());
            MapDefinition.Tile tile = definition.getTiles()[plane][x & 63][y & 63];
            tile.underlayId = (short) underlay;
            tile.overlayId = (short) overlay;
            tile.overlayPath = (byte) path;
            tile.overlayRotation = (byte) rotation;
            tile.settings = (byte) settings;
            regions.remove(regionId);
        }

        private Region region(int x, int y) {
            int regionId = regionId(x, y);
            MapDefinition definition = terrain.get(regionId);
            if (definition == null) return null;
            return regions.computeIfAbsent(regionId, ignored -> {
                Region region = new Region(regionId);
                region.loadTerrain(definition);
                region.getLocations().addAll(objects.getOrDefault(regionId, List.of()));
                return region;
            });
        }

        private void object(int x, int y, int plane, int id) {
            int regionId = regionId(x, y);
            objects.computeIfAbsent(regionId, ignored -> new ArrayList<>())
                    .add(new Location(id, 22, 0, new Position(x, y, plane)));
            regions.remove(regionId);
        }

        private TileTypeClassifier classifier() {
            return new TileTypeClassifier(this::region, overlays::get, collision);
        }

        private static int regionId(int x, int y) {
            return ((x >> 6) << 8) | (y >> 6);
        }

        private static MapDefinition emptyTerrain() {
            MapDefinition definition = new MapDefinition();
            for (int plane = 0; plane < Region.Z; plane++) {
                for (int x = 0; x < Region.X; x++) {
                    for (int y = 0; y < Region.Y; y++) {
                        MapDefinition.Tile tile = new MapDefinition.Tile();
                        tile.height = 0;
                        definition.getTiles()[plane][x][y] = tile;
                    }
                }
            }
            return definition;
        }
    }

    private static final class TestCollisionMap implements ICollisionMap {
        private final Set<String> openEdges = new HashSet<>();

        private void allowWest(int x, int y, int plane) {
            allowEast(x - 1, y, plane);
        }

        private void allowSouth(int x, int y, int plane) {
            allowNorth(x, y - 1, plane);
        }

        private void allowEast(int x, int y, int plane) {
            openEdges.add(key(x, y, plane, "E"));
        }

        private void allowNorth(int x, int y, int plane) {
            openEdges.add(key(x, y, plane, "N"));
        }

        @Override
        public boolean pathableNorth(int x, int y, int plane) {
            return openEdges.contains(key(x, y, plane, "N"));
        }

        @Override
        public boolean pathableEast(int x, int y, int plane) {
            return openEdges.contains(key(x, y, plane, "E"));
        }

        private static String key(int x, int y, int plane, String direction) {
            return x + ":" + y + ":" + plane + ":" + direction;
        }
    }
}
