package osrs.dev.mapping.collisionmap;

import lombok.extern.slf4j.Slf4j;
import osrs.dev.mapping.CacheEfficientCoordIndexer;
import osrs.dev.mapping.ConfigurableCoordIndexer;
import osrs.dev.mapping.tiledatamap.ITileDataMap;
import osrs.dev.mapping.tiledatamap.ITileDataMapWriter;
import osrs.dev.mapping.tiledatamap.roaring.RoaringTileDataMap;
import osrs.dev.mapping.tiledatamap.roaring.RoaringTileDataMapWriter;
import osrs.dev.mapping.tiledatamap.sparse.SparseTileDataMap;
import osrs.dev.mapping.tiledatamap.sparse.SparseTileDataMapWriter;
import osrs.dev.mapping.tiledatamap.sparse.SparseWordTileDataMap;
import osrs.dev.mapping.tiledatamap.sparse.SparseWordTileDataMapWriter;
import osrs.dev.mapping.DirectCoordPacker;

import java.io.*;
import java.util.zip.GZIPInputStream;

/**
 * Factory for loading collision maps and creating writers.
 * Auto-detects file format from filename ("roaring" or "sparse") and gzip from extension (.gz).
 */
@Slf4j
public class CollisionMapFactory {

    /**
     * Supported collision map formats.
     */
    public enum Format {
        /**
         * Legacy Java ObjectOutputStream format with SparseBitSet.
         * Detected by "sparse" in the filename.
         */
        SPARSE_BITSET,

        /**
         * SparseWordSet format - stores N-bit values per coordinate.
         * Detected by "wordset" in the filename.
         */
        SPARSE_WORDSET,

        /**
         * RoaringBitmap format.
         * Detected by "roaring" in the filename.
         * Can be gzipped (check with isGzipped()).
         */
        ROARING
    }

    private CollisionMapFactory() {}

    /**
     * Loads a collision map, auto-detecting the format and handling gzip decompression.
     *
     * @param filePath path to the collision map file
     * @return the loaded collision map
     * @throws Exception if loading fails
     */
    public static ICollisionMap load(String filePath) throws Exception {
        File file = new File(filePath);
        if (!file.exists() || !file.isFile()) {
           throw new FileNotFoundException("Collision map file not found: " + filePath);
        }

        Format format = detectFormat(filePath);
        boolean gzipped = isGzipped(filePath);
        log.debug("Loading map in format: {}, gzipped: {}", format, gzipped);

        try (FileInputStream fis = new FileInputStream(file);
             InputStream inputStream = gzipped ? new GZIPInputStream(fis) : fis) {

            ITileDataMap dataMap;
            switch (format) {
                case ROARING:
                    dataMap = RoaringTileDataMap.load(inputStream, CacheEfficientCoordIndexer.SEQUENTIAL_COORD_INDEXER_2_ADDRESSES);
                    break;
                case SPARSE_WORDSET:
                    dataMap = SparseWordTileDataMap.load(inputStream, DirectCoordPacker.STANDARD);
                    break;
                case SPARSE_BITSET:
                default:
                    dataMap = SparseTileDataMap.load(inputStream, CacheEfficientCoordIndexer.SEQUENTIAL_COORD_INDEXER_2_ADDRESSES);
                    break;
            }
            return new CollisionMap(dataMap);
        }
    }

    /**
     * Detects file format by examining the filename.
     * Looks for "roaring" or "sparse" in the path.
     *
     * @param filePath path to the file
     * @return detected format, defaults to ROARING for unknown formats
     */
    public static Format detectFormat(String filePath) {
        String lowerPath = filePath.toLowerCase();

        if (lowerPath.contains("roaring")) {
            return Format.ROARING;
        } else if (lowerPath.contains("wordset")) {
            // Check "wordset" before "sparse" since it's more specific
            return Format.SPARSE_WORDSET;
        } else if (lowerPath.contains("sparse")) {
            return Format.SPARSE_BITSET;
        }

        // Default to ROARING for new files
        return Format.ROARING;
    }

    /**
     * Determines if a file is gzipped by checking the extension.
     *
     * @param filePath path to the file
     * @return true if the file ends with .gz, false otherwise
     */
    public static boolean isGzipped(String filePath) {
        return filePath.endsWith(".gz");
    }

    /**
     * Creates a new writer for the specified format.
     *
     * @param format the format to write
     * @return a new collision map writer
     */
    public static CollisionMapWriter createWriter(Format format) {
        ITileDataMapWriter dataMapWriter;
        switch (format) {
            case ROARING:
                dataMapWriter = new RoaringTileDataMapWriter(CacheEfficientCoordIndexer.SEQUENTIAL_COORD_INDEXER_2_ADDRESSES.withValidationEnabled());
                break;
            case SPARSE_WORDSET:
                dataMapWriter = new SparseWordTileDataMapWriter(4, DirectCoordPacker.STANDARD);
                break;
            case SPARSE_BITSET:
            default:
                dataMapWriter = new SparseTileDataMapWriter(CacheEfficientCoordIndexer.SEQUENTIAL_COORD_INDEXER_2_ADDRESSES.withValidationEnabled());
                break;
        }
        return new CollisionMapWriter(dataMapWriter);
    }
}
