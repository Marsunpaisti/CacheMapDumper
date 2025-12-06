package osrs.dev.postprocessor;

import lombok.extern.slf4j.Slf4j;
import osrs.dev.mapping.CacheEfficientCoordIndexer;
import osrs.dev.mapping.collisionmap.CollisionMapFactory;
import osrs.dev.mapping.collisionmap.CollisionMapWriter;
import osrs.dev.mapping.collisionmap.ICollisionMap;
import osrs.dev.mapping.tiletypemap.TileTypeMap;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;

/**
 * Processes collision and tile type maps to generate a boat navigation map.
 * <p>
 * The output is a collision map where:
 * <ul>
 *   <li>pathableNorth(x, y) = true if a boat can fit centered at (x, y+1)</li>
 *   <li>pathableEast(x, y) = true if a boat can fit centered at (x+1, y)</li>
 * </ul>
 * <p>
 * Usage:
 * <pre>
 * ICollisionMap collision = CollisionMapFactory.load("path/to/collision.dat.gz");
 * TileTypeMap tileTypes = TileTypeMapFactory.load("path/to/tiletypes.dat.gz");
 *
 * BoatNavigationMapProcessor processor = new BoatNavigationMapProcessor(collision, tileTypes);
 * processor.process(4);  // 4x4 boat
 * processor.save("path/to/boat_nav_4x4_roaring.dat.gz");
 * </pre>
 */
@Slf4j
public class BoatNavigationMapProcessor {
    private static final CacheEfficientCoordIndexer INDEXER =
            CacheEfficientCoordIndexer.SEQUENTIAL_COORD_INDEXER_2_ADDRESSES;

    private final ICollisionMap collisionMap;
    private final TileTypeMap tileTypeMap;
    private final CollisionMapFactory.Format format;
    private CollisionMapWriter outputWriter;
    private int processedBoatSize = -1;

    /**
     * Creates a processor with the specified output format.
     *
     * @param collisionMap the source collision map
     * @param tileTypeMap  the source tile type map
     * @param format       the output format (ROARING or SPARSE_BITSET)
     */
    public BoatNavigationMapProcessor(ICollisionMap collisionMap, TileTypeMap tileTypeMap, CollisionMapFactory.Format format) {
        this.collisionMap = collisionMap;
        this.tileTypeMap = tileTypeMap;
        this.format = format;
    }

    /**
     * Processes the input maps to generate boat navigation data for the given boat size.
     * Uses all available processors for parallelism.
     *
     * @param boatSize the NxN boat size (must be >= 1)
     */
    public void process(int boatSize) {
        process(boatSize, Runtime.getRuntime().availableProcessors());
    }

    /**
     * Processes with specified parallelism.
     *
     * @param boatSize    the NxN boat size (must be >= 1)
     * @param threadCount number of threads to use
     */
    public void process(int boatSize, int threadCount) {
        process(boatSize, threadCount, null);
    }

    /**
     * Processes with specified parallelism and progress callback.
     *
     * @param boatSize         the NxN boat size (must be >= 1)
     * @param threadCount      number of threads to use
     * @param progressCallback callback invoked with the number of completed X strips (nullable)
     */
    public void process(int boatSize, int threadCount, Consumer<Integer> progressCallback) {
        if (boatSize < 1) {
            throw new IllegalArgumentException("Boat size must be at least 1");
        }

        log.info("Processing boat navigation map for {}x{} boats with {} threads (format: {})...",
                boatSize, boatSize, threadCount, format);

        this.outputWriter = CollisionMapFactory.createWriter(format);
        this.processedBoatSize = boatSize;

        BoatFitChecker checker = new BoatFitChecker(collisionMap, tileTypeMap, boatSize);

        // Get coordinate bounds from tile type map's indexer (includes safety margin)
        CacheEfficientCoordIndexer indexer = (CacheEfficientCoordIndexer) tileTypeMap.getIndexer();
        int minX = indexer.getMinX();
        int maxX = indexer.getMaxX();
        int minY = indexer.getMinY();
        int maxY = indexer.getMaxY();
        int minPlane = indexer.getMinPlane();
        int maxPlane = indexer.getMaxPlane();

        AtomicLong tilesProcessed = new AtomicLong(0);
        AtomicLong fitNorthCount = new AtomicLong(0);
        AtomicLong fitEastCount = new AtomicLong(0);
        AtomicInteger completedStrips = new AtomicInteger(0);

        ExecutorService executor = Executors.newFixedThreadPool(threadCount);
        List<Future<Void>> futures = new ArrayList<>();

        // Parallelize by X coordinate strips
        for (int x = minX; x <= maxX; x++) {
            final int currentX = x;
            futures.add(executor.submit(() -> {
                for (int y = minY; y <= maxY; y++) {
                    for (int plane = minPlane; plane <= maxPlane; plane++) {
                        // Check if boat can fit at (x, y+1) for pathableNorth
                        boolean canFitNorth = checker.canFitAt(currentX, y + 1, plane);
                        outputWriter.setPathableNorth(currentX, y, plane, canFitNorth);
                        if (canFitNorth) {
                            fitNorthCount.incrementAndGet();
                        }

                        // Check if boat can fit at (x+1, y) for pathableEast
                        boolean canFitEast = checker.canFitAt(currentX + 1, y, plane);
                        outputWriter.setPathableEast(currentX, y, plane, canFitEast);
                        if (canFitEast) {
                            fitEastCount.incrementAndGet();
                        }

                        tilesProcessed.incrementAndGet();
                    }
                }

                // Report progress after each X strip completes
                if (progressCallback != null) {
                    progressCallback.accept(completedStrips.incrementAndGet());
                }
                return null;
            }));
        }

        executor.shutdown();

        // Wait for all tasks to complete and check for exceptions
        try {
            for (Future<Void> future : futures) {
                future.get();
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new RuntimeException("Processing interrupted", e);
        } catch (ExecutionException e) {
            throw new RuntimeException("Error processing boat navigation map", e.getCause());
        }

        log.info("Processed {} tiles. Passable: {} north, {} east",
                tilesProcessed.get(), fitNorthCount.get(), fitEastCount.get());
    }

    /**
     * Returns the total number of X strips that will be processed.
     * Use this to initialize a progress bar.
     */
    public int getTotalStrips() {
        return INDEXER.getMaxX() - INDEXER.getMinX() + 1;
    }

    /**
     * Saves the processed boat navigation map to a file.
     *
     * @param filePath output file path (use .gz extension for compression)
     * @throws IOException          if saving fails
     * @throws IllegalStateException if process() has not been called
     */
    public void save(String filePath) throws IOException {
        if (outputWriter == null || processedBoatSize < 0) {
            throw new IllegalStateException("Must call process() before save()");
        }
        outputWriter.save(filePath);
        log.info("Saved boat navigation map to {}", filePath);
    }

    /**
     * Returns the boat size that was processed.
     */
    public int getProcessedBoatSize() {
        return processedBoatSize;
    }
}
