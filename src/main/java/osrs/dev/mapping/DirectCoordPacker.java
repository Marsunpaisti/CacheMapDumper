package osrs.dev.mapping;

import lombok.Getter;

/**
 * Simple coordinate packer that packs x, y, plane into a single integer index.
 * Unlike {@link ICoordIndexer}, this does not include an address/data-bit dimension -
 * it's designed for use with {@link osrs.dev.mapping.tiledatamap.sparse.SparseWordSet}
 * where all data bits for a coordinate are stored in a single value.
 *
 * <p>Bit layout (LSB to MSB): [X][Y][PLANE]
 */
public class DirectCoordPacker {

    /**
     * Standard packer for OSRS coordinates: 12 bits X, 14 bits Y, 2 bits plane.
     * Total: 28 bits, supporting full game world coordinate range.
     */
    public static final DirectCoordPacker STANDARD = new Builder()
            .xBits(12)
            .xBase(480)  // Allows X range 480-4575
            .yBits(14)   // Allows Y range 0-16383
            .planeBits(2) // Planes 0-3
            .build();

    @Getter
    private final int xMask;
    @Getter
    private final int xShift;
    @Getter
    private final int xBase;
    @Getter
    private final int yMask;
    @Getter
    private final int yShift;
    @Getter
    private final int yBase;
    @Getter
    private final int planeMask;
    @Getter
    private final int planeShift;
    @Getter
    private final int planeBase;
    @Getter
    private final int totalBits;
    @Getter
    private final int minX, maxX, minY, maxY, minPlane, maxPlane;

    /**
     * Creates a coordinate packer with the specified bit layout.
     *
     * @param xBits     number of bits for X coordinate
     * @param xBase     base offset for X coordinate
     * @param yBits     number of bits for Y coordinate
     * @param yBase     base offset for Y coordinate
     * @param planeBits number of bits for plane
     * @param planeBase base offset for plane
     */
    public DirectCoordPacker(int xBits, int xBase, int yBits, int yBase, int planeBits, int planeBase) {
        this.totalBits = xBits + yBits + planeBits;
        if (totalBits > 31) {
            throw new IllegalArgumentException(
                    String.format("Total bits (%d) exceeds 31-bit limit for signed int indexing", totalBits));
        }

        // X at LSB
        this.xMask = (1 << xBits) - 1;
        this.xShift = 0;
        this.xBase = xBase;

        // Y next
        this.yMask = (1 << yBits) - 1;
        this.yShift = xBits;
        this.yBase = yBase;

        // Plane at MSB
        this.planeMask = (1 << planeBits) - 1;
        this.planeShift = xBits + yBits;
        this.planeBase = planeBase;

        // Coordinate ranges
        this.minX = xBase;
        this.maxX = xBase + xMask;
        this.minY = yBase;
        this.maxY = yBase + yMask;
        this.minPlane = planeBase;
        this.maxPlane = planeBase + planeMask;
    }

    /**
     * Packs coordinates into a single integer index.
     *
     * @param x     the x coordinate
     * @param y     the y coordinate
     * @param plane the plane
     * @return the packed index
     */
    public int pack(int x, int y, int plane) {
        int xOffset = (x - xBase) & xMask;
        int yOffset = (y - yBase) & yMask;
        int planeOffset = (plane - planeBase) & planeMask;
        return xOffset | (yOffset << yShift) | (planeOffset << planeShift);
    }

    /**
     * Unpacks the X coordinate from a packed index.
     *
     * @param packed the packed index
     * @return the X coordinate
     */
    public int unpackX(int packed) {
        return (packed & xMask) + xBase;
    }

    /**
     * Unpacks the Y coordinate from a packed index.
     *
     * @param packed the packed index
     * @return the Y coordinate
     */
    public int unpackY(int packed) {
        return ((packed >> yShift) & yMask) + yBase;
    }

    /**
     * Unpacks the plane from a packed index.
     *
     * @param packed the packed index
     * @return the plane
     */
    public int unpackPlane(int packed) {
        return ((packed >> planeShift) & planeMask) + planeBase;
    }

    /**
     * Returns the maximum packed index value.
     */
    public int getMaxIndex() {
        return (1 << totalBits) - 1;
    }

    /**
     * Checks if coordinates are within the valid range.
     *
     * @param x     the x coordinate
     * @param y     the y coordinate
     * @param plane the plane
     * @return true if coordinates are valid
     */
    public boolean isValidCoordinate(int x, int y, int plane) {
        return x >= minX && x <= maxX
                && y >= minY && y <= maxY
                && plane >= minPlane && plane <= maxPlane;
    }

    @Override
    public String toString() {
        return String.format("DirectCoordPacker[x=%d bits (base %d), y=%d bits (base %d), plane=%d bits, total=%d bits]",
                Integer.bitCount(xMask), xBase, Integer.bitCount(yMask), yBase, Integer.bitCount(planeMask), totalBits);
    }

    /**
     * Builder for DirectCoordPacker.
     */
    public static class Builder {
        private int xBits = 12;
        private int xBase = 0;
        private int yBits = 14;
        private int yBase = 0;
        private int planeBits = 2;
        private int planeBase = 0;

        public Builder xBits(int bits) {
            this.xBits = bits;
            return this;
        }

        public Builder xBase(int base) {
            this.xBase = base;
            return this;
        }

        public Builder yBits(int bits) {
            this.yBits = bits;
            return this;
        }

        public Builder yBase(int base) {
            this.yBase = base;
            return this;
        }

        public Builder planeBits(int bits) {
            this.planeBits = bits;
            return this;
        }

        public Builder planeBase(int base) {
            this.planeBase = base;
            return this;
        }

        public DirectCoordPacker build() {
            return new DirectCoordPacker(xBits, xBase, yBits, yBase, planeBits, planeBase);
        }
    }

    public static Builder builder() {
        return new Builder();
    }
}
