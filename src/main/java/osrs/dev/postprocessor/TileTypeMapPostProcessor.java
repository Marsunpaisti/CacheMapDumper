package osrs.dev.postprocessor;

import lombok.extern.slf4j.Slf4j;
import org.roaringbitmap.RoaringBitmap;
import osrs.dev.mapping.CacheEfficientCoordIndexer;
import osrs.dev.mapping.tiletypemap.TileTypeMap;
import osrs.dev.mapping.tiletypemap.TileTypeMapFactory;
import osrs.dev.mapping.tiletypemap.TileTypeMapWriter;

import java.io.IOException;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.function.Consumer;

/**
 * Post-processes a TileTypeMap to filter out small water bodies.
 * <p>
 * Uses flood fill (BFS) to identify connected water bodies per plane.
 * Bodies smaller than the minimum size threshold are set to NONE (0).
 * <p>
 * Usage:
 * <pre>
 * TileTypeMap source = TileTypeMapFactory.load("tile_types_roaring.dat.gz");
 * TileTypeMapPostProcessor processor = new TileTypeMapPostProcessor(source, TileTypeMapFactory.Format.ROARING);
 * processor.process(progressCallback);
 * processor.save("postprocessed_tile_types_roaring.dat.gz");
 * </pre>
 */
@Slf4j
public class TileTypeMapPostProcessor {
    private static final CacheEfficientCoordIndexer INDEXER =
            CacheEfficientCoordIndexer.SEQUENTIAL_COORD_INDEXER_8_ADDRESSES;

    /**
     * Minimum number of tiles for a water body to be preserved.
     * Bodies smaller than this will be filtered out.
     */
    private static final int MIN_WATER_BODY_SIZE = 5000;

    private final TileTypeMap sourceMap;
    private final TileTypeMapFactory.Format format;
    private TileTypeMapWriter outputWriter;
    private boolean processed = false;

    // Stats
    private long totalWaterTiles = 0;
    private long filteredTiles = 0;
    private int bodiesFiltered = 0;
    private int bodiesPreserved = 0;

    /**
     * Creates a post-processor for the given tile type map.
     *
     * @param sourceMap the source tile type map to process
     * @param format    the output format (ROARING or SPARSE_BITSET)
     */
    public TileTypeMapPostProcessor(TileTypeMap sourceMap, TileTypeMapFactory.Format format) {
        this.sourceMap = sourceMap;
        this.format = format;
    }

    /**
     * Processes the tile type map to filter small water bodies.
     * Uses all available processors for parallelism.
     */
    public void process() {
        process(null);
    }

    /**
     * Processes the tile type map with progress callback.
     *
     * @param progressCallback callback invoked with completed plane count (0-4), nullable
     */
    public void process(Consumer<Integer> progressCallback) {
        log.info("Post-processing tile type map to filter water bodies smaller than {} tiles...", MIN_WATER_BODY_SIZE);

        this.outputWriter = TileTypeMapFactory.createWriter(format);

        // Use source map's indexer bounds (includes safety margin)
        CacheEfficientCoordIndexer indexer = (CacheEfficientCoordIndexer) sourceMap.getIndexer();
        int minX = indexer.getMinX();
        int maxX = indexer.getMaxX();
        int minY = indexer.getMinY();
        int maxY = indexer.getMaxY();
        int minPlane = indexer.getMinPlane();
        int maxPlane = indexer.getMaxPlane();

        // Process each plane independently
        for (int plane = minPlane; plane <= maxPlane; plane++) {
            processPlane(plane, minX, maxX, minY, maxY);

            if (progressCallback != null) {
                progressCallback.accept(plane + 1);
            }
        }

        this.processed = true;

        log.info("Post-processing complete. Total water tiles: {}, Filtered: {}, Bodies preserved: {}, Bodies filtered: {}",
                totalWaterTiles, filteredTiles, bodiesPreserved, bodiesFiltered);
    }

    /**
     * Processes a single plane to identify and filter small water bodies.
     */
    private void processPlane(int plane, int minX, int maxX, int minY, int maxY) {
        int xRange = maxX - minX + 1;
        int yRange = maxY - minY + 1;

        // Use RoaringBitmap for visited tracking - efficient for sparse data
        RoaringBitmap visited = new RoaringBitmap();

        // Set to track tiles that should be filtered (belong to small bodies)
        RoaringBitmap tilesToFilter = new RoaringBitmap();

        log.debug("Processing plane {} (x: {}-{}, y: {}-{})", plane, minX, maxX, minY, maxY);

        // Scan all tiles in this plane
        for (int x = minX; x <= maxX; x++) {
            for (int y = minY; y <= maxY; y++) {
                try {

                    byte tileType = sourceMap.getTileType(x, y, plane);

                    // Skip non-water tiles
                    if (tileType <= 0) {
                        continue;
                    }

                    int packedCoord = packCoord(x, y, minX, xRange);

                    // Skip if already visited
                    if (visited.contains(packedCoord)) {
                        continue;
                    }

                    // Found an unvisited water tile - flood fill to find the body
                    List<int[]> bodyTiles = floodFill(x, y, plane, minX, maxX, minY, maxY, xRange, visited);
                    totalWaterTiles += bodyTiles.size();

                    // Check if body is too small
                    if (bodyTiles.size() < MIN_WATER_BODY_SIZE) {
                        bodiesFiltered++;
                        filteredTiles += bodyTiles.size();
                        // Mark all tiles in this body for filtering
                        for (int[] coord : bodyTiles) {
                            tilesToFilter.add(packCoord(coord[0], coord[1], minX, xRange));
                        }
                    } else {
                        bodiesPreserved++;
                    }
                } catch (Exception e) {
                    log.error("Error processing tile at ({}, {}, {})", x, y, plane, e);
                    throw new RuntimeException(e);
                }
            }
        }

        // Now copy tiles to output, skipping filtered tiles
        for (int x = minX; x <= maxX; x++) {
            for (int y = minY; y <= maxY; y++) {
                byte tileType = sourceMap.getTileType(x, y, plane);

                if (tileType <= 0) {
                    // Non-water tile - don't copy (output defaults to 0)
                    continue;
                }

                int packedCoord = packCoord(x, y, minX, xRange);
                if (!tilesToFilter.contains(packedCoord)) {
                    // Preserve this water tile
                    outputWriter.setTileType(x, y, plane, tileType);
                }
                // else: filtered out - leave as 0
            }
        }
    }

    /**
     * Performs BFS flood fill starting from (startX, startY) to find all connected water tiles.
     *
     * @return list of [x, y] coordinates in the water body
     */
    private List<int[]> floodFill(int startX, int startY, int plane,
                                  int minX, int maxX, int minY, int maxY,
                                  int xRange, RoaringBitmap visited) {
        List<int[]> body = new ArrayList<>();
        Deque<int[]> queue = new ArrayDeque<>();

        int startPacked = packCoord(startX, startY, minX, xRange);
        visited.add(startPacked);
        queue.add(new int[]{startX, startY});
        body.add(new int[]{startX, startY});

        // 4-directional neighbors: N, S, E, W
        int[][] directions = {{0, 1}, {0, -1}, {1, 0}, {-1, 0}};

        while (!queue.isEmpty()) {
            int[] current = queue.poll();
            int cx = current[0];
            int cy = current[1];

            for (int[] dir : directions) {
                int nx = cx + dir[0];
                int ny = cy + dir[1];

                // Bounds check
                if (nx < minX || nx > maxX || ny < minY || ny > maxY) {
                    continue;
                }

                int neighborPacked = packCoord(nx, ny, minX, xRange);

                // Skip if already visited
                if (visited.contains(neighborPacked)) {
                    continue;
                }

                // Check if this neighbor is water (any type > 0)
                byte neighborType = sourceMap.getTileType(nx, ny, plane);
                if (neighborType > 0) {
                    visited.add(neighborPacked);
                    queue.add(new int[]{nx, ny});
                    body.add(new int[]{nx, ny});
                }
            }
        }

        return body;
    }

    /**
     * Packs (x, y) into a single int for bitmap indexing.
     * Formula: (y - minY) * xRange + (x - minX)
     */
    private int packCoord(int x, int y, int minX, int xRange) {
        int minY = INDEXER.getMinY();
        return (y - minY) * xRange + (x - minX);
    }

    /**
     * Returns the total number of planes to process (for progress bar initialization).
     */
    public int getTotalPlanes() {
        return INDEXER.getMaxPlane() - INDEXER.getMinPlane() + 1;
    }

    /**
     * Saves the post-processed tile type map to a file.
     *
     * @param filePath output file path (use .gz extension for compression)
     * @throws IOException           if saving fails
     * @throws IllegalStateException if process() has not been called
     */
    public void save(String filePath) throws IOException {
        if (!processed || outputWriter == null) {
            throw new IllegalStateException("Must call process() before save()");
        }
        outputWriter.save(filePath);
        log.info("Saved post-processed tile type map to {}", filePath);
    }

    /**
     * Returns the number of water tiles that were filtered out.
     */
    public long getFilteredTilesCount() {
        return filteredTiles;
    }

    /**
     * Returns the number of water bodies that were filtered out.
     */
    public int getFilteredBodiesCount() {
        return bodiesFiltered;
    }

    /**
     * Returns the number of water bodies that were preserved.
     */
    public int getPreservedBodiesCount() {
        return bodiesPreserved;
    }
}
