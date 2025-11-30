package osrs.dev.mapping.tiledatamap.sparse;

import osrs.dev.mapping.DirectCoordPacker;
import osrs.dev.mapping.tiledatamap.ITileDataMapWriter;

import java.io.*;
import java.util.zip.GZIPOutputStream;

/**
 * Writer for SparseWordTileDataMap.
 * Allows setting individual bits or entire values at coordinates.
 */
public class SparseWordTileDataMapWriter implements ITileDataMapWriter {
    private final SparseWordSet wordSet;
    private final DirectCoordPacker packer;

    /**
     * Creates a new writer with the specified bits per value.
     *
     * @param bitsPerValue number of bits per coordinate value (must divide 64 evenly)
     * @param packer       the coordinate packer
     */
    public SparseWordTileDataMapWriter(int bitsPerValue, DirectCoordPacker packer) {
        this.wordSet = new SparseWordSet(bitsPerValue);
        this.packer = packer;
    }

    /**
     * Creates a new writer with the specified bits per value and standard packer.
     *
     * @param bitsPerValue number of bits per coordinate value
     */
    public SparseWordTileDataMapWriter(int bitsPerValue) {
        this(bitsPerValue, DirectCoordPacker.STANDARD);
    }

    /**
     * Returns the underlying SparseWordSet.
     */
    public SparseWordSet getWordSet() {
        return wordSet;
    }

    /**
     * Returns the coordinate packer.
     */
    public DirectCoordPacker getPacker() {
        return packer;
    }

    @Override
    public void setDataBit(int x, int y, int plane, int dataBitIndex) {
        int packedCoord = packer.pack(x, y, plane);
        int current = wordSet.get(packedCoord);
        wordSet.set(packedCoord, current | (1 << dataBitIndex));
    }

    /**
     * Clears a data bit at the specified coordinate.
     *
     * @param x            the x coordinate
     * @param y            the y coordinate
     * @param plane        the plane
     * @param dataBitIndex the bit index to clear
     */
    public void clearDataBit(int x, int y, int plane, int dataBitIndex) {
        int packedCoord = packer.pack(x, y, plane);
        int current = wordSet.get(packedCoord);
        wordSet.set(packedCoord, current & ~(1 << dataBitIndex));
    }

    /**
     * Sets all data bits from an integer value (O(1) operation).
     * This overrides the default O(N) implementation.
     *
     * @param x     the x coordinate
     * @param y     the y coordinate
     * @param plane the plane
     * @param data  the data value to set
     */
    @Override
    public void setAllDataBits(int x, int y, int plane, int data) {
        int packedCoord = packer.pack(x, y, plane);
        wordSet.set(packedCoord, data);
    }

    /**
     * Sets the raw value at the coordinate.
     * Alias for {@link #setAllDataBits(int, int, int, int)}.
     *
     * @param x     the x coordinate
     * @param y     the y coordinate
     * @param plane the plane
     * @param value the value to set
     */
    public void setValue(int x, int y, int plane, int value) {
        setAllDataBits(x, y, plane, value);
    }

    /**
     * Gets the current value at the coordinate.
     *
     * @param x     the x coordinate
     * @param y     the y coordinate
     * @param plane the plane
     * @return the current value
     */
    public int getValue(int x, int y, int plane) {
        int packedCoord = packer.pack(x, y, plane);
        return wordSet.get(packedCoord);
    }

    @Override
    public void save(String filePath) throws IOException {
        try (OutputStream os = createOutputStream(filePath)) {
            DataOutputStream dataOut = new DataOutputStream(os);
            wordSet.serialize(dataOut);
            dataOut.flush();
        }
    }

    private OutputStream createOutputStream(String filePath) throws IOException {
        OutputStream os = new BufferedOutputStream(new FileOutputStream(filePath));
        if (filePath.endsWith(".gz")) {
            os = new GZIPOutputStream(os);
        }
        return os;
    }

    /**
     * Creates a read-only SparseWordTileDataMap from this writer.
     *
     * @return a new SparseWordTileDataMap
     */
    public SparseWordTileDataMap toReadOnly() {
        return new SparseWordTileDataMap(wordSet, packer);
    }

    /**
     * Returns statistics about the underlying data structure.
     */
    public String getStatistics() {
        return wordSet.getStatistics();
    }
}
