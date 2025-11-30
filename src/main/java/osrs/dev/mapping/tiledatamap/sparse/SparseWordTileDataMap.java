package osrs.dev.mapping.tiledatamap.sparse;

import osrs.dev.mapping.DirectCoordPacker;
import osrs.dev.mapping.ICoordIndexer;
import osrs.dev.mapping.tiledatamap.ITileDataMap;

import java.io.*;
import java.util.zip.GZIPInputStream;

/**
 * ITileDataMap implementation backed by SparseWordSet.
 * Provides O(1) retrieval of all data bits for a coordinate, unlike SparseBitSet-based
 * implementations which require O(N) individual bit lookups.
 */
public class SparseWordTileDataMap implements ITileDataMap {
    private final SparseWordSet wordSet;
    private final DirectCoordPacker packer;
    private final int bitsPerValue;
    private final ICoordIndexer indexerAdapter;

    /**
     * Creates a SparseWordTileDataMap wrapping the given SparseWordSet.
     *
     * @param wordSet the underlying word set
     * @param packer  the coordinate packer
     */
    public SparseWordTileDataMap(SparseWordSet wordSet, DirectCoordPacker packer) {
        this.wordSet = wordSet;
        this.packer = packer;
        this.bitsPerValue = wordSet.getBitsPerValue();
        this.indexerAdapter = new IndexerAdapter();
    }

    /**
     * Returns the coordinate packer used by this map.
     */
    public DirectCoordPacker getPacker() {
        return packer;
    }

    /**
     * Returns the underlying SparseWordSet.
     */
    public SparseWordSet getWordSet() {
        return wordSet;
    }

    @Override
    public ICoordIndexer getIndexer() {
        return indexerAdapter;
    }

    @Override
    public boolean isDataBitSet(int x, int y, int plane, int dataBitIndex) {
        if (dataBitIndex < 0 || dataBitIndex >= bitsPerValue) {
            return false;
        }
        int packedCoord = packer.pack(x, y, plane);
        int value = wordSet.get(packedCoord);
        return (value & (1 << dataBitIndex)) != 0;
    }

    /**
     * Gets all data bits for the coordinate in O(1) time.
     * This overrides the default O(N) implementation in ITileDataMap.
     *
     * @param x     the x coordinate
     * @param y     the y coordinate
     * @param plane the plane
     * @return the data value
     */
    @Override
    public byte getAllDataBits(int x, int y, int plane) {
        int packedCoord = packer.pack(x, y, plane);
        return (byte) wordSet.get(packedCoord);
    }

    /**
     * Gets the raw integer value at the coordinate (for values > 8 bits).
     *
     * @param x     the x coordinate
     * @param y     the y coordinate
     * @param plane the plane
     * @return the raw value
     */
    public int getValue(int x, int y, int plane) {
        int packedCoord = packer.pack(x, y, plane);
        return wordSet.get(packedCoord);
    }

    /**
     * Loads a SparseWordTileDataMap from a file.
     *
     * @param filePath the file path (supports .gz extension for gzip)
     * @param packer   the coordinate packer to use
     * @return the loaded map
     * @throws IOException if an I/O error occurs
     */
    public static SparseWordTileDataMap load(String filePath, DirectCoordPacker packer) throws IOException {
        try (InputStream is = createInputStream(filePath)) {
            return load(is, packer);
        }
    }

    /**
     * Loads a SparseWordTileDataMap from an input stream.
     *
     * @param inputStream the input stream (should be decompressed if originally gzipped)
     * @param packer      the coordinate packer to use
     * @return the loaded map
     * @throws IOException if an I/O error occurs
     */
    public static SparseWordTileDataMap load(InputStream inputStream, DirectCoordPacker packer) throws IOException {
        DataInputStream dataIn = new DataInputStream(inputStream);
        SparseWordSet wordSet = SparseWordSet.deserialize(dataIn);
        return new SparseWordTileDataMap(wordSet, packer);
    }

    private static InputStream createInputStream(String filePath) throws IOException {
        InputStream is = new BufferedInputStream(new FileInputStream(filePath));
        if (filePath.endsWith(".gz")) {
            is = new GZIPInputStream(is);
        }
        return is;
    }

    /**
     * Adapter to satisfy the ICoordIndexer interface requirement.
     * Note: This adapter exists for interface compatibility. Direct use of
     * {@link #getAllDataBits(int, int, int)} is preferred for O(1) access.
     */
    private class IndexerAdapter implements ICoordIndexer {
        @Override
        public int packToBitmapIndex(int x, int y, int plane, int addressIndex) {
            // For compatibility, we pack the coordinate and treat addressIndex as the bit offset
            // This shouldn't be used directly with SparseWordSet - use getValue() instead
            return packer.pack(x, y, plane);
        }

        @Override
        public int getMaxAddressIndex() {
            return bitsPerValue - 1;
        }
    }
}
