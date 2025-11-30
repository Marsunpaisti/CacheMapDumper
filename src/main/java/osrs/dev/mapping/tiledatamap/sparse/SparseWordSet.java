package osrs.dev.mapping.tiledatamap.sparse;

import java.io.*;

/**
 * A sparse data structure for storing N-bit values at arbitrary indices.
 * Uses a 3-level hierarchical storage model (similar to SparseBitSet) where
 * unpopulated regions consume no memory.
 *
 * <p>Each value occupies a configurable number of bits (must divide 64 evenly:
 * 1, 2, 4, 8, 16, 32, or 64). Values at unset indices return 0.
 *
 * <p>Storage structure:
 * <pre>
 * data[level1][level2][level3] → long (stores 64/bitsPerValue values)
 * </pre>
 *
 * <p>For 4-bit values (nibbles), each long stores 16 values, and each level3
 * block (32 longs) stores 512 values.
 */
public class SparseWordSet implements Serializable {
    private static final long serialVersionUID = 1L;

    // Level structure constants (same as SparseBitSet)
    private static final int LEVEL3 = 5;
    private static final int LEVEL2 = 5;
    private static final int LENGTH3 = 1 << LEVEL3;  // 32 longs per block
    private static final int LENGTH2 = 1 << LEVEL2;  // 32 blocks per area
    private static final int MASK3 = LENGTH3 - 1;
    private static final int MASK2 = LENGTH2 - 1;
    private static final int SHIFT2 = LEVEL3;
    private static final int SHIFT1 = LEVEL2 + LEVEL3;

    /** Number of bits per stored value. */
    private final int bitsPerValue;

    /** Number of values that fit in one long word. */
    private final int valuesPerWord;

    /** Shift amount to convert value index to word index (log2 of valuesPerWord). */
    private final int valueShift;

    /** Mask for extracting a single value from a word. */
    private final long valueMask;

    /** The 3-level sparse data structure. */
    private transient long[][][] data;

    /**
     * Creates a new SparseWordSet with the specified bits per value.
     *
     * @param bitsPerValue number of bits per stored value (must divide 64 evenly)
     * @throws IllegalArgumentException if bitsPerValue doesn't divide 64 evenly
     */
    public SparseWordSet(int bitsPerValue) {
        if (bitsPerValue < 1 || bitsPerValue > 64 || (64 % bitsPerValue != 0)) {
            throw new IllegalArgumentException(
                    "bitsPerValue must be 1, 2, 4, 8, 16, 32, or 64, got: " + bitsPerValue);
        }
        this.bitsPerValue = bitsPerValue;
        this.valuesPerWord = 64 / bitsPerValue;
        this.valueShift = Integer.numberOfTrailingZeros(valuesPerWord);
        this.valueMask = (1L << bitsPerValue) - 1;
        this.data = new long[1][][];
    }

    /**
     * Gets the value at the specified index.
     *
     * @param index the index (must be non-negative)
     * @return the value at the index, or 0 if not set
     * @throws IndexOutOfBoundsException if index is negative
     */
    public int get(int index) {
        if (index < 0) {
            throw new IndexOutOfBoundsException("index=" + index);
        }

        int w = index >> valueShift;        // word index
        int w1 = w >> SHIFT1;               // level1 index
        int w2 = (w >> SHIFT2) & MASK2;     // level2 index
        int w3 = w & MASK3;                 // level3 index

        if (w1 >= data.length) {
            return 0;
        }
        long[][] a2 = data[w1];
        if (a2 == null) {
            return 0;
        }
        long[] a3 = a2[w2];
        if (a3 == null) {
            return 0;
        }

        int offset = (index & (valuesPerWord - 1)) * bitsPerValue;
        return (int) ((a3[w3] >> offset) & valueMask);
    }

    /**
     * Sets the value at the specified index.
     *
     * @param index the index (must be non-negative)
     * @param value the value to set (will be masked to bitsPerValue bits)
     * @throws IndexOutOfBoundsException if index is negative
     */
    public void set(int index, int value) {
        if (index < 0) {
            throw new IndexOutOfBoundsException("index=" + index);
        }

        int w = index >> valueShift;
        int w1 = w >> SHIFT1;
        int w2 = (w >> SHIFT2) & MASK2;
        int w3 = w & MASK3;

        ensureCapacity(w1);

        long[][] a2 = data[w1];
        if (a2 == null) {
            a2 = data[w1] = new long[LENGTH2][];
        }

        long[] a3 = a2[w2];
        if (a3 == null) {
            a3 = a2[w2] = new long[LENGTH3];
        }

        int offset = (index & (valuesPerWord - 1)) * bitsPerValue;
        long word = a3[w3];
        a3[w3] = (word & ~(valueMask << offset)) | ((long) (value & valueMask) << offset);
    }

    /**
     * Ensures the level1 array can hold the specified index.
     */
    private void ensureCapacity(int w1) {
        if (w1 >= data.length) {
            int newSize = Math.max(data.length * 2, w1 + 1);
            // Round up to power of 2
            newSize = Integer.highestOneBit(newSize - 1) << 1;
            long[][][] newData = new long[newSize][][];
            System.arraycopy(data, 0, newData, 0, data.length);
            data = newData;
        }
    }

    /**
     * Returns the number of bits per stored value.
     */
    public int getBitsPerValue() {
        return bitsPerValue;
    }

    /**
     * Serializes this SparseWordSet to a DataOutputStream.
     * Uses a compact format that only writes non-null blocks.
     *
     * @param out the output stream
     * @throws IOException if an I/O error occurs
     */
    public void serialize(DataOutputStream out) throws IOException {
        out.writeInt(bitsPerValue);
        out.writeInt(data.length);

        for (int i = 0; i < data.length; i++) {
            long[][] a2 = data[i];
            if (a2 == null) {
                out.writeBoolean(false);
                continue;
            }
            out.writeBoolean(true);

            for (int j = 0; j < LENGTH2; j++) {
                long[] a3 = a2[j];
                if (a3 == null) {
                    out.writeBoolean(false);
                    continue;
                }
                out.writeBoolean(true);

                for (int k = 0; k < LENGTH3; k++) {
                    out.writeLong(a3[k]);
                }
            }
        }
    }

    /**
     * Deserializes a SparseWordSet from a DataInputStream.
     *
     * @param in the input stream
     * @return the deserialized SparseWordSet
     * @throws IOException if an I/O error occurs
     */
    public static SparseWordSet deserialize(DataInputStream in) throws IOException {
        int bitsPerValue = in.readInt();
        int length = in.readInt();

        SparseWordSet set = new SparseWordSet(bitsPerValue);
        set.data = new long[length][][];

        for (int i = 0; i < length; i++) {
            if (!in.readBoolean()) {
                continue;
            }

            set.data[i] = new long[LENGTH2][];
            for (int j = 0; j < LENGTH2; j++) {
                if (!in.readBoolean()) {
                    continue;
                }

                set.data[i][j] = new long[LENGTH3];
                for (int k = 0; k < LENGTH3; k++) {
                    set.data[i][j][k] = in.readLong();
                }
            }
        }
        return set;
    }

    /**
     * Custom serialization to write fields and data structure.
     */
    private void writeObject(ObjectOutputStream out) throws IOException {
        out.defaultWriteObject();
        serialize(new DataOutputStream(out));
    }

    /**
     * Custom deserialization to read fields and data structure.
     */
    private void readObject(ObjectInputStream in) throws IOException, ClassNotFoundException {
        in.defaultReadObject();
        // Read data using our deserialize format, but we need to handle it differently
        // since we're reading into 'this' instance
        DataInputStream dataIn = new DataInputStream(in);
        int readBitsPerValue = dataIn.readInt();
        int length = dataIn.readInt();

        if (readBitsPerValue != this.bitsPerValue) {
            throw new IOException("bitsPerValue mismatch: expected " + bitsPerValue + ", got " + readBitsPerValue);
        }

        this.data = new long[length][][];
        for (int i = 0; i < length; i++) {
            if (!dataIn.readBoolean()) {
                continue;
            }

            data[i] = new long[LENGTH2][];
            for (int j = 0; j < LENGTH2; j++) {
                if (!dataIn.readBoolean()) {
                    continue;
                }

                data[i][j] = new long[LENGTH3];
                for (int k = 0; k < LENGTH3; k++) {
                    data[i][j][k] = dataIn.readLong();
                }
            }
        }
    }

    /**
     * Returns approximate memory usage in bytes (excluding object overhead).
     */
    public long estimateMemoryUsage() {
        long bytes = 0;
        // Level 1 array
        bytes += (long) data.length * 8; // references

        for (long[][] a2 : data) {
            if (a2 == null) continue;
            // Level 2 array
            bytes += (long) LENGTH2 * 8; // references

            for (long[] a3 : a2) {
                if (a3 == null) continue;
                // Level 3 array (actual data)
                bytes += (long) LENGTH3 * 8; // longs
            }
        }
        return bytes;
    }

    /**
     * Returns statistics about the sparse structure.
     */
    public String getStatistics() {
        int level1Count = 0;
        int level2Count = 0;
        int level3Count = 0;

        for (long[][] a2 : data) {
            if (a2 == null) continue;
            level1Count++;
            for (long[] a3 : a2) {
                if (a3 == null) continue;
                level2Count++;
                level3Count++;
            }
        }

        long memBytes = estimateMemoryUsage();
        return String.format("SparseWordSet[bitsPerValue=%d, level1=%d/%d, level2=%d, level3=%d, memory=%.2fMB]",
                bitsPerValue, level1Count, data.length, level2Count, level3Count, memBytes / (1024.0 * 1024.0));
    }
}
