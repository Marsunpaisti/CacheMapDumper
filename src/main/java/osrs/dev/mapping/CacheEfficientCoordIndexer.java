package osrs.dev.mapping;

import lombok.Getter;

/**
 * Cache-efficient coordinate indexer that places addresses for each coordinate at contiguous indices.
 *
 * Unlike {@link ConfigurableCoordIndexer} which uses flag bits to distinguish data positions
 * (causing bitmap accesses to be far apart in memory), this indexer multiplies the packed
 * coordinate by the number of addresses and adds the address index as an offset.
 *
 * This results in all addresses for a single coordinate being adjacent in the bitmap,
 * improving CPU cache locality when multiple addresses per coordinate are accessed together.
 *
 * Example with 4 addresses per coordinate:
 * <pre>
 * Coordinate packs to 0x1111:
 *   Address 0 → index 0x1111 * 4 + 0 = 0x4444
 *   Address 1 → index 0x1111 * 4 + 1 = 0x4445
 *   Address 2 → index 0x1111 * 4 + 2 = 0x4446
 *   Address 3 → index 0x1111 * 4 + 3 = 0x4447
 * </pre>
 */
public class CacheEfficientCoordIndexer implements ICoordIndexer {
    /**
     * Cache-efficient packing: 12 bits X, 14 bits Y, 2 bits plane, 4 addresses per coordinate.
     * Total coordinate bits = 28, Leaving a 4 bit (0 - 15) address space per coord.
     */
    public static final CacheEfficientCoordIndexer SEQUENTIAL_COORD_INDEXER_8_ADDRESSES = new Builder()
            .maxBitCapacity(32)
            .xBits(12)
            .xBase(480)
            .yBits(14)
            .planeBits(2)
            .addressesPerCoordinate(8)
            .build();
    public static final CacheEfficientCoordIndexer SEQUENTIAL_COORD_INDEXER_2_ADDRESSES = new Builder()
            .maxBitCapacity(32)
            .xBits(12)
            .xBase(480)
            .yBits(14)
            .planeBits(2)
            .addressesPerCoordinate(2)
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
    private final int addressesPerCoordinate;
    @Getter
    private final int maxBitCapacity;
    @Getter
    private final int minX;
    @Getter
    private final int maxX;
    @Getter
    private final int minY;
    @Getter
    private final int maxY;
    @Getter
    private final int minPlane;
    @Getter
    private final int maxPlane;
    @Getter
    private final int totalCoordBits;
    @Getter
    private final boolean isAdditionalValidationEnabled;

    /**
     * Creates a cache-efficient coordinate indexer.
     *
     * The index formula is: (packedCoordinate * addressesPerCoordinate) + addressIndex
     *
     * @param maxBitCapacity                maximum bit capacity of the target bitmap (e.g., 32 for RoaringBitmap, 31 for SparseBitSet)
     * @param isAdditionalValidationEnabled whether to validate inputs at runtime
     * @param xBits                         number of bits for X coordinate
     * @param xBase                         base offset for X coordinate
     * @param yBits                         number of bits for Y coordinate
     * @param yBase                         base offset for Y coordinate
     * @param planeBits                     number of bits for plane/level coordinate
     * @param planeBase                     base offset for plane coordinate
     * @param addressesPerCoordinate        number of addresses (slots) per coordinate
     * @throws IllegalArgumentException if the configuration would exceed the bit capacity
     */
    public CacheEfficientCoordIndexer(int maxBitCapacity, boolean isAdditionalValidationEnabled,
                                      int xBits, int xBase, int yBits, int yBase,
                                      int planeBits, int planeBase, int addressesPerCoordinate) {
        this.totalCoordBits = xBits + yBits + planeBits;
        this.addressesPerCoordinate = addressesPerCoordinate;
        this.maxBitCapacity = maxBitCapacity;

        if (addressesPerCoordinate < 1) {
            throw new IllegalArgumentException("addressesPerCoordinate must be at least 1");
        }

        if (totalCoordBits > maxBitCapacity) {
            throw new IllegalArgumentException(
                    String.format("Total coordinate bits (%d) exceeds maximum bit capacity (%d)",
                            totalCoordBits, maxBitCapacity));
        }

        // Validate that addressesPerCoordinate doesn't cause overflow
        // Max index = (2^totalCoordBits - 1) * addressesPerCoordinate + (addressesPerCoordinate - 1)
        // This must fit in maxBitCapacity bits, i.e., < 2^maxBitCapacity
        // Simplified: addressesPerCoordinate <= 2^(maxBitCapacity - totalCoordBits)
        int availableBitsForAddresses = maxBitCapacity - totalCoordBits;
        long maxAllowedAddresses = 1L << availableBitsForAddresses;
        if (addressesPerCoordinate > maxAllowedAddresses) {
            throw new IllegalArgumentException(
                    String.format("addressesPerCoordinate (%d) exceeds maximum allowed (%d) for %d coordinate bits with %d-bit capacity. " +
                                    "Max index would overflow.",
                            addressesPerCoordinate, maxAllowedAddresses, totalCoordBits, maxBitCapacity));
        }

        // Compute masks and shifts based on bit widths
        // Layout: [PLANE][Y][X]
        this.xMask = (1 << xBits) - 1;
        this.xShift = 0;
        this.xBase = xBase;

        this.yMask = (1 << yBits) - 1;
        this.yShift = xBits;
        this.yBase = yBase;

        this.planeMask = (1 << planeBits) - 1;
        this.planeShift = xBits + yBits;
        this.planeBase = planeBase;

        this.isAdditionalValidationEnabled = isAdditionalValidationEnabled;

        // Safety margin of 2 to avoid edge cases with SparseBitSet at Integer.MAX_VALUE
        this.minX = xBase + 2;
        this.maxX = xBase + xMask - 2;
        this.minY = yBase + 2;
        this.maxY = yBase + yMask - 2;
        this.minPlane = planeBase;
        this.maxPlane = planeBase + planeMask;
    }

    @Override
    public int packToBitmapIndex(int x, int y, int plane, int addressIndex) {
        if (isAdditionalValidationEnabled) {
            validateAddressIndex(addressIndex);
        }
        int packedCoord = packCoordinate(x, y, plane);
        return packedCoord * addressesPerCoordinate + addressIndex;
    }

    @Override
    public int getMaxAddressIndex() {
        return addressesPerCoordinate - 1;
    }

    /**
     * Returns the maximum number of addresses that could be configured for this
     * coordinate bit layout without exceeding the bit capacity.
     *
     * @return the maximum configurable addresses per coordinate
     */
    public long getMaxConfigurableAddresses() {
        return 1L << (maxBitCapacity - totalCoordBits);
    }

    private int packCoordinate(int x, int y, int plane) {
        return packX(x) | packY(y) | packPlane(plane);
    }

    private int packX(int x) {
        if (isAdditionalValidationEnabled) {
            validatePackableX(x);
        }
        int xOffset = x - xBase;
        return (xOffset & xMask) << xShift;
    }

    private int packY(int y) {
        if (isAdditionalValidationEnabled) {
            validatePackableY(y);
        }
        int yOffset = y - yBase;
        return (yOffset & yMask) << yShift;
    }

    private int packPlane(int plane) {
        if (isAdditionalValidationEnabled) {
            validatePackablePlane(plane);
        }
        int planeOffset = plane - planeBase;
        return (planeOffset & planeMask) << planeShift;
    }

    private void validatePackableX(int x) {
        if (x < minX || x > maxX) {
            throw new IllegalArgumentException(
                    String.format("X coordinate %d is outside valid range [%d, %d]", x, minX, maxX)
            );
        }
    }

    private void validatePackableY(int y) {
        if (y < minY || y > maxY) {
            throw new IllegalArgumentException(
                    String.format("Y coordinate %d is outside valid range [%d, %d]", y, minY, maxY)
            );
        }
    }

    private void validatePackablePlane(int plane) {
        if (plane < minPlane || plane > maxPlane) {
            throw new IllegalArgumentException(
                    String.format("Plane coordinate %d is outside valid range [%d, %d]", plane, minPlane, maxPlane)
            );
        }
    }

    private void validateAddressIndex(int addressIndex) {
        if (addressIndex < 0 || addressIndex >= addressesPerCoordinate) {
            throw new IllegalArgumentException(
                    String.format("Address index %d is outside valid range [0, %d]",
                            addressIndex, addressesPerCoordinate - 1)
            );
        }
    }

    /**
     * Creates a copy of this indexer with validation enabled.
     */
    public CacheEfficientCoordIndexer withValidationEnabled() {
        if (isAdditionalValidationEnabled) {
            return this;
        }
        int xBits = Integer.bitCount(xMask);
        int yBits = Integer.bitCount(yMask);
        int planeBits = Integer.bitCount(planeMask);
        return new CacheEfficientCoordIndexer(maxBitCapacity, true, xBits, xBase, yBits, yBase, planeBits, planeBase, addressesPerCoordinate);
    }

    /**
     * Creates a copy of this indexer with validation disabled.
     */
    public CacheEfficientCoordIndexer withValidationDisabled() {
        if (!isAdditionalValidationEnabled) {
            return this;
        }
        int xBits = Integer.bitCount(xMask);
        int yBits = Integer.bitCount(yMask);
        int planeBits = Integer.bitCount(planeMask);
        return new CacheEfficientCoordIndexer(maxBitCapacity, false, xBits, xBase, yBits, yBase, planeBits, planeBase, addressesPerCoordinate);
    }

    /**
     * Creates a new builder for CacheEfficientCoordIndexer with fluent API.
     */
    public static Builder builder() {
        return new Builder();
    }

    /**
     * Builder class for creating CacheEfficientCoordIndexer instances.
     */
    public static class Builder {
        private int xBits = 0;
        private int yBits = 0;
        private int planeBits = 0;
        private int xBase = 0;
        private int yBase = 0;
        private int planeBase = 0;
        private int addressesPerCoordinate = 1;
        private int maxBitCapacity = 32;
        private boolean isAdditionalValidationEnabled = false;

        public Builder xBits(int bits) {
            this.xBits = bits;
            return this;
        }

        public Builder yBits(int bits) {
            this.yBits = bits;
            return this;
        }

        public Builder planeBits(int bits) {
            this.planeBits = bits;
            return this;
        }

        public Builder xBase(int base) {
            this.xBase = base;
            return this;
        }

        public Builder yBase(int base) {
            this.yBase = base;
            return this;
        }

        public Builder planeBase(int base) {
            this.planeBase = base;
            return this;
        }

        /**
         * Sets the number of addresses (slots) available per coordinate.
         * Each coordinate can store this many independent bits of data.
         * Valid address indices will be 0 to (addressesPerCoordinate - 1).
         *
         * @param addresses number of addresses per coordinate
         */
        public Builder addressesPerCoordinate(int addresses) {
            this.addressesPerCoordinate = addresses;
            return this;
        }

        /**
         * Sets the maximum bit capacity of the target bitmap.
         * Use 32 for RoaringBitmap (unsigned), 31 for SparseBitSet (signed).
         * The builder will validate that the configuration fits within this capacity.
         *
         * @param capacity maximum number of bits for indexing
         */
        public Builder maxBitCapacity(int capacity) {
            this.maxBitCapacity = capacity;
            return this;
        }

        public Builder useAdditionalValidation() {
            this.isAdditionalValidationEnabled = true;
            return this;
        }

        public CacheEfficientCoordIndexer build() {
            return new CacheEfficientCoordIndexer(maxBitCapacity, isAdditionalValidationEnabled,
                    xBits, xBase, yBits, yBase, planeBits, planeBase, addressesPerCoordinate);
        }
    }
}
