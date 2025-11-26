package osrs.dev.mapping.tiledatamap.sparse;

import VitaX.services.local.pathfinder.engine.collision.SparseBitSet;
import osrs.dev.mapping.ICoordIndexer;
import osrs.dev.mapping.tiledatamap.ITileDataMap;

import java.io.IOException;
import java.io.InputStream;
import java.io.ObjectInputStream;

/**
 * Generic SparseBitSet-based data map.
 * Stores arbitrary data bits at tile coordinates using SparseBitSet.
 */
public class SparseTileDataMap implements ITileDataMap {
    private final ICoordIndexer indexer;

    private final SparseBitSet bitSet;

    private SparseTileDataMap(SparseBitSet bitSet, ICoordIndexer indexer) {
        this.bitSet = bitSet;
        this.indexer = indexer;
    }

    @Override
    public ICoordIndexer getIndexer() {
        return indexer;
    }

    @Override
    public boolean isDataBitSet(int x, int y, int plane, int dataBitIndex) {
        int bitIndex = indexer.packToBitmapIndex(x, y, plane, dataBitIndex);
        return bitSet.get(bitIndex);
    }

    /**
     * Loads from an input stream.
     * The input stream should already be decompressed if it was gzipped.
     *
     * @param inputStream The input stream containing serialized SparseBitSet.
     * @return The data map.
     * @throws IOException            On file read error.
     * @throws ClassNotFoundException On class not found.
     */
    public static SparseTileDataMap load(InputStream inputStream, ICoordIndexer indexer) throws IOException, ClassNotFoundException {
        try (ObjectInputStream objectInputStream = new ObjectInputStream(inputStream)) {
            return new SparseTileDataMap((SparseBitSet) objectInputStream.readObject(), indexer);
        }
    }
}
