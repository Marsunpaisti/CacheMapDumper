package osrs.dev.mapping.tiledatamap.sparse;

import osrs.dev.mapping.ICoordIndexer;
import osrs.dev.mapping.tiledatamap.ITileDataMapWriter;

import java.io.FileOutputStream;
import java.io.IOException;
import java.io.ObjectOutputStream;
import java.util.zip.GZIPOutputStream;

/**
 * Generic SparseBitSet-based data map writer.
 * Writes arbitrary data bits at tile coordinates using SparseBitSet.
 */
public class SparseTileDataMapWriter implements ITileDataMapWriter {
    private final ICoordIndexer indexer;
    private final SparseBitSet bitSet;

    public SparseTileDataMapWriter(ICoordIndexer indexer) {
        this.bitSet = new SparseBitSet();
        this.indexer = indexer;
    }

    @Override
    public synchronized void setDataBit(int x, int y, int plane, int dataBitIndex) {
        bitSet.set(indexer.packToBitmapIndex(x, y, plane, dataBitIndex));
    }

    @Override
    public void save(String filePath) throws IOException {
        if (filePath.endsWith(".gz")) {
            saveGzipped(filePath);
        } else {
            saveWithoutGzip(filePath);
        }
    }

    /**
     * Saves the data map with GZIP compression.
     *
     * @param filePath path to save the data map
     * @throws IOException if saving fails
     */
    public void saveGzipped(String filePath) throws IOException {
        try (FileOutputStream fos = new FileOutputStream(filePath);
             GZIPOutputStream gzos = new GZIPOutputStream(fos);
             ObjectOutputStream oos = new ObjectOutputStream(gzos)) {
            oos.writeObject(bitSet);
        }
    }

    /**
     * Saves the data map without GZIP compression.
     *
     * @param filePath path to save the data map
     * @throws IOException if saving fails
     */
    public void saveWithoutGzip(String filePath) throws IOException {
        try (FileOutputStream fos = new FileOutputStream(filePath);
             ObjectOutputStream oos = new ObjectOutputStream(fos)) {
            oos.writeObject(bitSet);
        }
    }
}
