package osrs.dev.mapping;

/**
 * Interface for indexing data associated with coordinates in a bitmap.
 * Each coordinate can have multiple addresses (slots) for storing independent bits of data.
 */
public interface ICoordIndexer {
    /**
     * Packs x, y, plane coordinates and an address index
     * into a bitmap index to retrieve a bit of data for the coordinate.
     *
     * @param x the x coordinate
     * @param y the y coordinate
     * @param plane the plane/level
     * @param addressIndex the address slot (0 to {@link #getMaxAddressIndex()})
     * @return packed integer index into the bitmap
     */
    int packToBitmapIndex(int x, int y, int plane, int addressIndex);

    /**
     * Returns the maximum valid address index that can be passed to
     * {@link #packToBitmapIndex(int, int, int, int)}.
     * Valid address indices are 0 to this value (inclusive).
     *
     * @return the maximum address index
     */
    int getMaxAddressIndex();
}
