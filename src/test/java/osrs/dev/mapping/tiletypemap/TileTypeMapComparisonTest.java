package osrs.dev.mapping.tiletypemap;

import org.junit.jupiter.api.Test;

import java.io.File;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for tile type map formats: memory usage, read speed benchmarks, and data comparison.
 */
public class TileTypeMapComparisonTest {

    private static final String BASE_PATH = System.getProperty("user.home") + "/VitaX/";
    private static final String ROARING_PATH = BASE_PATH + "tile_types_roaring.dat.gz";
    private static final String SPARSE_PATH = BASE_PATH + "tile_types_sparse.dat.gz";
    private static final String WORDSET_PATH = BASE_PATH + "tile_types_wordset.dat.gz";

    // Coordinate bounds from DirectCoordPacker.STANDARD
    private static final int MIN_X = 480;
    private static final int MAX_X = 4575;
    private static final int MIN_Y = 0;
    private static final int MAX_Y = 16383;
    private static final int MIN_PLANE = 0;
    private static final int MAX_PLANE = 2;

    private static final int RANDOM_ITERATIONS = 10_000_000;

    // ==================== Memory Tests ====================

    @Test
    public void testRoaringBitmapMemory() throws Exception {
        runMemoryTest("RoaringBitmap", ROARING_PATH);
    }

    @Test
    public void testSparseBitSetMemory() throws Exception {
        runMemoryTest("SparseBitSet", SPARSE_PATH);
    }

    @Test
    public void testSparseWordSetMemory() throws Exception {
        runMemoryTest("SparseWordSet", WORDSET_PATH);
    }

    private void runMemoryTest(String formatName, String filePath) throws Exception {
        File file = new File(filePath);
        if (!file.exists()) {
            System.out.println("Skipping " + formatName + " memory test - file not found: " + filePath);
            return;
        }

        System.out.println(formatName + " Memory Test");
        System.out.println("=".repeat(formatName.length() + 12));
        System.out.printf("File: %s%n", filePath);
        System.out.printf("File size: %,d bytes (%.2f MB)%n", file.length(), file.length() / (1024.0 * 1024.0));

        forceGC();
        long beforeMemory = usedMemory();

        TileTypeMap map = TileTypeMapFactory.load(filePath);

        forceGC();
        long afterMemory = usedMemory();
        long mapMemory = afterMemory - beforeMemory;

        System.out.printf("In-memory size: %,d bytes (%.2f MB)%n", mapMemory, mapMemory / (1024.0 * 1024.0));
        System.out.printf("Memory/Disk ratio: %.2fx%n", mapMemory / (double) file.length());

        // Keep reference alive
        map.getTileType(MIN_X, MIN_Y, 0);
    }

    // ==================== Benchmark Tests ====================

    @Test
    public void testRoaringBitmapBenchmark() throws Exception {
        runBenchmark("RoaringBitmap", ROARING_PATH);
    }

    @Test
    public void testSparseBitSetBenchmark() throws Exception {
        runBenchmark("SparseBitSet", SPARSE_PATH);
    }

    @Test
    public void testSparseWordSetBenchmark() throws Exception {
        runBenchmark("SparseWordSet", WORDSET_PATH);
    }

    private void runBenchmark(String formatName, String filePath) throws Exception {
        File file = new File(filePath);
        if (!file.exists()) {
            System.out.println("Skipping " + formatName + " benchmark - file not found: " + filePath);
            return;
        }

        System.out.println(formatName + " Benchmark");
        System.out.println("=".repeat(formatName.length() + 10));

        TileTypeMap map = TileTypeMapFactory.load(filePath);

        // Warmup
        System.out.println("Warming up JIT (200k reads)...");
        warmup(map);
        try { Thread.sleep(200); } catch (InterruptedException ignored) {}

        // Random access benchmark - getTileType
        System.out.printf("%n--- Random Access - getTileType (%,d reads) ---%n", RANDOM_ITERATIONS);
        long randomTime = benchmarkRandomGetTileType(map, RANDOM_ITERATIONS, 42);
        double randomNsPerRead = randomTime / (double) RANDOM_ITERATIONS;
        double randomReadsPerSec = RANDOM_ITERATIONS / (randomTime / 1_000_000_000.0);
        System.out.printf("Total time: %,d ns (%.2f ms)%n", randomTime, randomTime / 1_000_000.0);
        System.out.printf("Per read: %.2f ns%n", randomNsPerRead);
        System.out.printf("Throughput: %,.0f reads/sec%n", randomReadsPerSec);

        try { Thread.sleep(200); } catch (InterruptedException ignored) {}

        // Sequential access benchmark
        System.out.println("\n--- Sequential Access (full range scan) ---");
        long seqTime = benchmarkSequential(map);
        long seqReads = (long)(MAX_X - MIN_X + 1) * (MAX_Y - MIN_Y + 1) * (MAX_PLANE - MIN_PLANE + 1);
        double seqNsPerRead = seqTime / (double) seqReads;
        double seqReadsPerSec = seqReads / (seqTime / 1_000_000_000.0);
        System.out.printf("Total reads: %,d%n", seqReads);
        System.out.printf("Total time: %,d ns (%.2f ms)%n", seqTime, seqTime / 1_000_000.0);
        System.out.printf("Per read: %.2f ns%n", seqNsPerRead);
        System.out.printf("Throughput: %,.0f reads/sec%n", seqReadsPerSec);
    }

    // ==================== Comparison Tests ====================

    @Test
    public void testSparseBitSetAndSparseWordSetAreIdentical() throws Exception {
        runComparisonTest("SparseBitSet", SPARSE_PATH, "SparseWordSet", WORDSET_PATH);
    }

    @Test
    public void testRoaringBitmapAndSparseBitSetAreIdentical() throws Exception {
        runComparisonTest("RoaringBitmap", ROARING_PATH, "SparseBitSet", SPARSE_PATH);
    }

    @Test
    public void testRoaringBitmapAndSparseWordSetAreIdentical() throws Exception {
        runComparisonTest("RoaringBitmap", ROARING_PATH, "SparseWordSet", WORDSET_PATH);
    }

    private void runComparisonTest(String name1, String path1, String name2, String path2) throws Exception {
        File file1 = new File(path1);
        File file2 = new File(path2);

        if (!file1.exists() || !file2.exists()) {
            System.out.println("Skipping comparison test - files not found:");
            System.out.println("  " + path1 + " exists: " + file1.exists());
            System.out.println("  " + path2 + " exists: " + file2.exists());
            return;
        }

        System.out.println("Comparing " + name1 + " vs " + name2);
        System.out.println("=".repeat(14 + name1.length() + name2.length()));

        System.out.println("Loading " + name1 + "...");
        TileTypeMap map1 = TileTypeMapFactory.load(path1);

        System.out.println("Loading " + name2 + "...");
        TileTypeMap map2 = TileTypeMapFactory.load(path2);

        System.out.println("Comparing maps...");
        System.out.printf("Coordinate range: X[%d-%d], Y[%d-%d], Plane[%d-%d]%n",
                MIN_X, MAX_X, MIN_Y, MAX_Y, MIN_PLANE, MAX_PLANE);

        long totalTiles = 0;
        long mismatchCount = 0;
        int firstMismatchX = -1, firstMismatchY = -1, firstMismatchPlane = -1;
        String firstMismatchDetail = null;

        for (int plane = MIN_PLANE; plane <= 1; plane++) {
            System.out.printf("Checking plane %d...%n", plane);
            for (int x = MIN_X; x <= MAX_X; x++) {
                for (int y = MIN_Y; y <= MAX_Y; y++) {
                    totalTiles++;

                    int type1 = map1.getTileType(x, y, plane);
                    int type2 = map2.getTileType(x, y, plane);

                    if (type1 != type2) {
                        mismatchCount++;
                        if (firstMismatchX == -1) {
                            firstMismatchX = x;
                            firstMismatchY = y;
                            firstMismatchPlane = plane;
                            firstMismatchDetail = String.format(
                                    "%s=%d (0x%02X), %s=%d (0x%02X)",
                                    name1, type1, type1 & 0xFF,
                                    name2, type2, type2 & 0xFF);
                        }
                    }
                }
            }
        }

        System.out.printf("Comparison complete. Total tiles checked: %,d%n", totalTiles);

        if (mismatchCount > 0) {
            System.out.printf("FAILED: Found %,d mismatches%n", mismatchCount);
            System.out.printf("First mismatch at (%d, %d, %d): %s%n",
                    firstMismatchX, firstMismatchY, firstMismatchPlane, firstMismatchDetail);
            fail(String.format("Maps differ at %,d tiles. First mismatch at (%d, %d, %d): %s",
                    mismatchCount, firstMismatchX, firstMismatchY, firstMismatchPlane, firstMismatchDetail));
        } else {
            System.out.println("SUCCESS: Maps are identical!");
        }
    }

    // ==================== Helper Methods ====================

    private void warmup(TileTypeMap map) {
        java.util.Random random = new java.util.Random(12345);
        for (int i = 0; i < 200_000; i++) {
            int x = MIN_X + random.nextInt(MAX_X - MIN_X + 1);
            int y = MIN_Y + random.nextInt(MAX_Y - MIN_Y + 1);
            int plane = random.nextInt(MAX_PLANE + 1);
            map.getTileType(x, y, plane);
        }
    }

    private long benchmarkRandomGetTileType(TileTypeMap map, int iterations, long seed) {
        java.util.Random random = new java.util.Random(seed);
        long total = 0;

        long start = System.nanoTime();
        for (int i = 0; i < iterations; i++) {
            int x = MIN_X + random.nextInt(MAX_X - MIN_X + 1);
            int y = MIN_Y + random.nextInt(MAX_Y - MIN_Y + 1);
            int plane = random.nextInt(MAX_PLANE + 1);
            int tileType = map.getTileType(x, y, plane);
            total += tileType;
        }
        long elapsed = System.nanoTime() - start;

        // Prevent dead code elimination
        if (total == Long.MIN_VALUE) System.out.println(total);
        return elapsed;
    }

    private long benchmarkSequential(TileTypeMap map) {
        long total = 0;

        long start = System.nanoTime();
        for (int x = MIN_X; x <= MAX_X; x++) {
            for (int y = MIN_Y; y <= MAX_Y; y++) {
                for (int plane = MIN_PLANE; plane <= MAX_PLANE; plane++) {
                    int tileType = map.getTileType(x, y, plane);
                    total += tileType;
                }
            }
        }
        long elapsed = System.nanoTime() - start;

        // Prevent dead code elimination
        if (total == Long.MIN_VALUE) System.out.println(total);
        return elapsed;
    }

    private static void forceGC() {
        for (int i = 0; i < 3; i++) {
            System.gc();
            try {
                Thread.sleep(50);
            } catch (InterruptedException ignored) {}
        }
    }

    private static long usedMemory() {
        Runtime runtime = Runtime.getRuntime();
        return runtime.totalMemory() - runtime.freeMemory();
    }
}
