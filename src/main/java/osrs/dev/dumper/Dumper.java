package osrs.dev.dumper;

import com.google.common.collect.ImmutableMap;
import com.google.gson.Gson;
import com.google.gson.reflect.TypeToken;
import lombok.Getter;
import lombok.extern.slf4j.Slf4j;
import net.runelite.cache.ObjectManager;
import net.runelite.cache.OverlayManager;
import net.runelite.cache.UnderlayManager;
import net.runelite.cache.definitions.ObjectDefinition;
import net.runelite.cache.definitions.OverlayDefinition;
import net.runelite.cache.definitions.UnderlayDefinition;
import net.runelite.cache.fs.Store;
import net.runelite.cache.region.Location;
import net.runelite.cache.region.Position;
import net.runelite.cache.region.Region;
import net.runelite.cache.region.RegionLoader;
import net.runelite.cache.util.KeyProvider;
import net.runelite.cache.util.XteaKeyManager;
import osrs.dev.dumper.openrs2.OpenRS2;
import osrs.dev.mapping.ConfigurableCoordIndexer;
import osrs.dev.mapping.collisionmap.CollisionMapFactory;
import osrs.dev.mapping.collisionmap.CollisionMapWriter;
import osrs.dev.mapping.collisionmap.ICollisionMap;
import osrs.dev.mapping.collisionmap.PaistiMap;
import osrs.dev.mapping.tiletypemap.TileType;
import osrs.dev.mapping.tiletypemap.TileTypeMapFactory;
import osrs.dev.mapping.tiletypemap.TileTypeMapWriter;
import osrs.dev.util.ConfigManager;
import osrs.dev.util.OptionsParser;
import osrs.dev.util.ProgressBar;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.lang.reflect.Field;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

/**
 * Dumps collision data from the cache.
 */
@Getter
@Slf4j
public class Dumper {
    public static File OUTPUT_MAP = new File(System.getProperty("user.home") + "/VitaX/map_roaring.dat.gz");
    public static File OUTPUT_TILE_TYPES = new File(System.getProperty("user.home") + "/VitaX/tile_types_roaring.dat.gz");
    public static final String COLLISION_DIR = System.getProperty("user.home") + "/VitaX/cachedumper/";
    public static final String CACHE_DIR = COLLISION_DIR + "/cache/";
    public static final String XTEA_DIR = COLLISION_DIR + "/keys/";
    private final RegionLoader regionLoader;
    private final ObjectManager objectManager;
    private final OverlayManager overlayManager;
    private final UnderlayManager underlayManager;
    private final CollisionMapWriter collisionMapWriter;
    private final CollisionMapWriter waterMaskWriter;
    private final TileTypeMapWriter tileTypeMapWriter;
    private final PaistiMap paistiMap;
    private final ICollisionMap waterTileMask;

    // Coordinate bounds tracking
    private int minX = Integer.MAX_VALUE;
    private int maxX = Integer.MIN_VALUE;
    private int minY = Integer.MAX_VALUE;
    private int maxY = Integer.MIN_VALUE;
    private int minZ = Integer.MAX_VALUE;
    private int maxZ = Integer.MIN_VALUE;
    private long maskedTilesReadFromPaistiMap = 0;

    private static OptionsParser optionsParser;
    private static CollisionMapFactory.Format format = CollisionMapFactory.Format.ROARING;

    /**
     * Creates a new dumper.
     *
     * @param store       the cache store
     * @param keyProvider the XTEA key provider
     */
    public Dumper(Store store, KeyProvider keyProvider) throws IOException {
        this.regionLoader = new RegionLoader(store, keyProvider);
        this.objectManager = new ObjectManager(store);
        this.overlayManager = new OverlayManager(store);
        this.underlayManager = new UnderlayManager(store);
        this.collisionMapWriter = CollisionMapFactory.createWriter(
                format
        );
        // Convert CollisionMapFactory.Format to TileTypeMapFactory.Format
        TileTypeMapFactory.Format tileTypeFormat;
        switch (format) {
            case SPARSE_BITSET:
                tileTypeFormat = TileTypeMapFactory.Format.SPARSE_BITSET;
                break;
            case SPARSE_WORDSET:
                tileTypeFormat = TileTypeMapFactory.Format.SPARSE_WORDSET;
                break;
            default:
                tileTypeFormat = TileTypeMapFactory.Format.ROARING;
                break;
        }
        this.tileTypeMapWriter = TileTypeMapFactory.createWriter(tileTypeFormat);

        this.waterMaskWriter = CollisionMapFactory.createWriter(
                CollisionMapFactory.Format.SPARSE_BITSET
        );


        ICollisionMap waterTilesMaskResult = null;
        try {
            waterTilesMaskResult = CollisionMapFactory.load(System.getProperty("user.home") + "/VitaX/water_mask_sparse.dat.gz");
            log.info("Water tiles mask loaded successfully.");
        } catch (Exception e) {
            log.error("Failed to load water tiles mask", e);
        }

        PaistiMap paistiMapResult = null;
        try {
            paistiMapResult = PaistiMap.loadFromResource();
            log.info("Paisti map loaded successfully.");
        } catch (Exception e) {
            log.error("Failed to load Paisti map.", e);
        }

        this.paistiMap = paistiMapResult;
        this.waterTileMask = waterTilesMaskResult;

        objectManager.load();
        overlayManager.load();
        underlayManager.load();
        regionLoader.loadRegions();
        regionLoader.calculateBounds();
    }

    /**
     * Finds an object definition by id.
     *
     * @param id the id
     * @return the object definition, or {@code null} if not found
     */
    private ObjectDefinition findObject(int id) {
        return objectManager.getObject(id);
    }

    /**
     * Finds an overlay definition by id.
     *
     * @param id the id
     * @return the overlay definition, or {@code null} if not found
     */
    private OverlayDefinition findOverlay(int id) {
        return overlayManager.provide(id);
    }

    /**
     * Finds an underlay definition by id.
     *
     * @param id the id
     * @return the underlay definition, or {@code null} if not found
     */
    private UnderlayDefinition findUnderlay(int id) {
        return underlayManager.provide(id);
    }

    /**
     * Dumps the collision data.
     *
     * @param args the command-line arguments
     * @throws IOException if an I/O error occurs
     */
    public static void main(String[] args) throws IOException {
        optionsParser = new OptionsParser(args);
        format = optionsParser.getFormat();
        OUTPUT_MAP = new File(optionsParser.getCollisionMapPath());
        OUTPUT_TILE_TYPES = new File(optionsParser.getTileTypeMapPath());

        log.info("Dumper options - dir: {}, format: {}", optionsParser.getOutputDir(), format);
        log.info("Collision map path: {}", OUTPUT_MAP.getPath());
        log.info("Tile type map path: {}", OUTPUT_TILE_TYPES.getPath());
        ensureDirectory(optionsParser.getOutputDir());
        ensureDirectory(COLLISION_DIR);
        ensureDirectory(XTEA_DIR);
        boolean fresh = optionsParser.isFreshCache();
        boolean emptyDir = isDirectoryEmpty(new File(COLLISION_DIR));
        boolean cacheDoesntExist = !(new File(COLLISION_DIR)).exists();
        if (fresh || emptyDir || cacheDoesntExist) {
            log.debug("Downloading fresh cache and XTEA keys (fresh={}, emptyDir={}, cacheDoesntExist={})", fresh, emptyDir, cacheDoesntExist);
            OpenRS2.update();
        }

        XteaKeyManager xteaKeyManager = new XteaKeyManager();
        try (FileInputStream fin = new FileInputStream(XTEA_DIR + "keys.json")) {
            Field keys = xteaKeyManager.getClass().getDeclaredField("keys");
            keys.setAccessible(true);
            Map<Integer, int[]> cKeys = (Map<Integer, int[]>) keys.get(xteaKeyManager);
            List<Map<String, Object>> keyList = new Gson().fromJson(new InputStreamReader(fin, StandardCharsets.UTF_8), new TypeToken<List<Map<String, Object>>>() {
            }.getType());

            for (Map<String, Object> entry : keyList) {
                int regionId = ((Double) entry.get("mapsquare")).intValue();  // mapsquare is your regionId
                List<Double> keyListDoubles = (List<Double>) entry.get("key");
                int[] keysArray = keyListDoubles.stream().mapToInt(Double::intValue).toArray();
                cKeys.put(regionId, keysArray);
            }
        } catch (Throwable t) {
            t.printStackTrace();
        }

        File base = new File(CACHE_DIR);

        try (Store store = new Store(base)) {
            store.load();

            Dumper dumper = new Dumper(store, xteaKeyManager);

            Collection<Region> regions = dumper.regionLoader.getRegions();

            int total = regions.size();

            ExecutorService executor = Executors.newFixedThreadPool(Runtime.getRuntime().availableProcessors());
            List<Future<Void>> futures = new ArrayList<>();

            for (Region region : regions) {
                futures.add(executor.submit(() ->
                {
                    dumper.processRegion(region);
                    return null;
                }));
            }

            executor.shutdown();

            int n = 0;
            ProgressBar progressBar = new ProgressBar(total, 50);
            for (Future<Void> future : futures) {
                future.get(); // wait for task to complete
                progressBar.update(++n);
            }

            log.info("Masked tiles read from Paisti map: {}", dumper.maskedTilesReadFromPaistiMap);
            dumper.collisionMapWriter.save(OUTPUT_MAP.getPath());
            log.info("Wrote collision map to {}", OUTPUT_MAP.getPath());
            dumper.tileTypeMapWriter.save(OUTPUT_TILE_TYPES.getPath());
            log.info("Wrote tile type map to {}", OUTPUT_TILE_TYPES.getPath());
            dumper.waterMaskWriter.save(new ConfigManager().outputDir() + "water_mask_sparse.dat.gz");
            log.info("Wrote water mask to {}", new ConfigManager().outputDir() + "water_mask_sparse.dat.gz");

            // Log coordinate bounds and calculate bits needed
            log.info("=== COORDINATE BOUNDS ===");
            log.info("X range: {} to {} (span: {})", dumper.minX, dumper.maxX, dumper.maxX - dumper.minX + 1);
            log.info("Y range: {} to {} (span: {})", dumper.minY, dumper.maxY, dumper.maxY - dumper.minY + 1);
            log.info("Z range: {} to {} (span: {})", dumper.minZ, dumper.maxZ, dumper.maxZ - dumper.minZ + 1);
        } catch (ExecutionException | InterruptedException e) {
            System.err.println("Error processing region");
            e.printStackTrace();
        }
    }

    /**
     * Processes a region.
     *
     * @param region the region
     */
    private void processRegion(Region region) {
        int baseX = region.getBaseX();
        int baseY = region.getBaseY();
        for (int z = 0; z < Region.Z; z++) {
            for (int localX = 0; localX < Region.X; localX++) {
                int regionX = baseX + localX;
                for (int localY = 0; localY < Region.Y; localY++) {
                    int regionY = baseY + localY;
                    // Track coordinate bounds
                    minX = Math.min(minX, regionX);
                    maxX = Math.max(maxX, regionX);
                    minY = Math.min(minY, regionY);
                    maxY = Math.max(maxY, regionY);
                    minZ = Math.min(minZ, z);
                    maxZ = Math.max(maxZ, z);

                    processCollisionOfRegionCoordinate(region, localX, localY, z, regionX, regionY);
                    processTileTypesOfRegionCoordinate(region, localX, localY, z, regionX, regionY);
                }
            }
        }
    }

    private void processTileTypesOfRegionCoordinate(Region region, int localX, int localY, int plane, int regionX, int regionY) {
        boolean isBridge = (region.getTileSetting(1, localX, localY) & 2) != 0;
        int tileZ = plane + (isBridge ? 1 : 0);
        int effectivePlane = plane < 3 ? tileZ : plane;

        int overlayId = region.getOverlayId(effectivePlane, localX, localY);
        if (overlayId <= 0) {
            return;
        }

        OverlayDefinition overlayDef = findOverlay(overlayId - 1);
        if (overlayDef == null) {
            return;
        }

        int textureId = overlayDef.getTexture();
        Byte tileType = TileType.SPRITE_ID_TO_TILE_TYPE.get(textureId);
        if (tileType != null && tileType > 0) {
            tileTypeMapWriter.setTileType(regionX, regionY, plane, tileType);

            // Mark the 5x5 area around water tiles in the water mask
            for (int dx = -2; dx <= 2; dx++) {
                for (int dy = -2; dy <= 12; dy++) {
                    waterMaskWriter.setPathableNorth(regionX, regionY, plane, false);
                    waterMaskWriter.setPathableEast(regionX, regionY, plane, false);
                }
            }
        }
    }

    private void processCollisionOfRegionCoordinate(Region region, int localX, int localY, int plane, int regionX, int regionY) {
        int tileZ = ProcessingUtils.getEffectiveTileZ(region, localX, localY, plane);

        // Keep some areas from Paisti's original map
        if (paistiMap != null && paistiMap.overrideTileCollisionIfApplicable(collisionMapWriter, regionX, regionY, plane)) {
            maskedTilesReadFromPaistiMap++;
            return;
        }

        for (Location loc : region.getLocations()) {
            Position pos = loc.getPosition();
            if (pos.getX() != regionX || pos.getY() != regionY || pos.getZ() != tileZ) {
                continue;
            }

            int type = loc.getType();
            int orientation = loc.getOrientation();
            int objectId = loc.getId();
            ObjectDefinition object = findObject(objectId);

            if (object == null) {
                continue;
            }
            Boolean exclusionRule = Exclusion.matches(objectId);

            boolean explicitlyExcluded = exclusionRule != null && exclusionRule;
            if (explicitlyExcluded) {
                continue;
            }

            // Skip objects that don't affect collision based on type and interactType
            // unless explicitly included via Exclusion rules
            boolean explicitlyIncluded = exclusionRule != null && !exclusionRule;
            if (!explicitlyIncluded && ProcessingUtils.isNonClipping(objectId, type, object.getInteractType())) {
                continue;
            }

            boolean shouldBlock = ProcessingUtils.shouldBlock(object, objectId);

            int sizeX = ProcessingUtils.getRotatedSizeX(object, orientation);
            int sizeY = ProcessingUtils.getRotatedSizeY(object, orientation);

            // Handle walls and doors
            if (type >= MapObjectShape.WallStraight.getType() && type <= MapObjectShape.WallSquareCorner.getType()) {
                if (type == MapObjectShape.WallStraight.getType() || type == MapObjectShape.WallL.getType()) {
                    switch (orientation) {
                        case 0: // wall on west
                            collisionMapWriter.westBlocking(regionX, regionY, plane, shouldBlock);
                            break;
                        case 1: // wall on north
                            collisionMapWriter.northBlocking(regionX, regionY, plane, shouldBlock);
                            break;
                        case 2: // wall on east
                            collisionMapWriter.eastBlocking(regionX, regionY, plane, shouldBlock);
                            break;
                        case 3: // wall on south
                            collisionMapWriter.southBlocking(regionX, regionY, plane, shouldBlock);
                            break;
                    }
                }
            }

            // Handle double walls, i.e the other direction of L-shaped walls
            if (type == MapObjectShape.WallL.getType()) {
                if (orientation == 3) //west
                {
                    collisionMapWriter.westBlocking(regionX, regionY, plane, shouldBlock);
                } else if (orientation == 0) //north
                {
                    collisionMapWriter.northBlocking(regionX, regionY, plane, shouldBlock);
                } else if (orientation == 1) //east
                {
                    collisionMapWriter.eastBlocking(regionX, regionY, plane, shouldBlock);
                } else if (orientation == 2) //south
                {
                    collisionMapWriter.southBlocking(regionX, regionY, plane, shouldBlock);
                }
            }

            // Handle diagonal walls (simplified)
            if (type == MapObjectShape.WallDiagonal.getType()) {
                collisionMapWriter.fullBlocking(regionX, regionY, plane, shouldBlock);
            }

            //objects
            if (type == 22 || (type >= 9 && type <= 11) || (type >= 12 && type <= 21)) {
                for (int x = 0; x < sizeX; x++) {
                    for (int y = 0; y < sizeY; y++) {
                        if (object.getInteractType() != 0 && (object.getWallOrDoor() == 1 || (type >= 10 && type <= 21))) {
                            collisionMapWriter.fullBlocking(regionX + x, regionY + y, plane, shouldBlock);
                        }
                    }
                }
            }
        }

        // Handle tiles without a floor or with blocking floor types
        int effectivePlane = ProcessingUtils.getEffectivePlane(region, localX, localY, plane);
        if (ProcessingUtils.isNoFloorOrBlockingFloor(region, localX, localY, effectivePlane)) {
            collisionMapWriter.fullBlocking(regionX, regionY, plane, true);
        }
    }

    /**
     * Ensures a directory exists.
     *
     * @param dir the directory
     */
    private static void ensureDirectory(String dir) {
        File file = new File(dir);
        if (!file.exists()) {
            if (!file.mkdirs()) {
                throw new RuntimeException("Unable to create directory " + dir);
            }
        }
    }

    /**
     * Checks if a directory is empty.
     *
     * @param directory the directory
     * @return {@code true} if the directory is empty, otherwise {@code false}
     */
    public static boolean isDirectoryEmpty(File directory) {
        if (directory.isDirectory()) {
            String[] files = directory.list();
            return files != null && files.length == 0;
        }
        return false;
    }
}