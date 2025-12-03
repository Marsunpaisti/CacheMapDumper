package osrs.dev.ui.viewport;

import com.google.common.collect.ImmutableMap;
import lombok.Getter;
import osrs.dev.Main;
import osrs.dev.mapping.collisionmap.Flags;
import osrs.dev.mapping.tiletypemap.TileType;
import osrs.dev.ui.ViewerMode;
import osrs.dev.util.WorldPoint;

import javax.imageio.ImageIO;
import java.awt.*;
import java.awt.image.BufferedImage;
import java.awt.image.DataBufferByte;
import java.awt.image.IndexColorModel;
import java.io.File;
import java.io.IOException;
import java.util.*;

/**
 * The ViewPort class is responsible for rendering the game world to the screen.
 * Uses chunk-based in-memory caching for fast scrolling.
 */
public class ViewPort
{
    @Getter
    private BufferedImage canvas;
    private WorldPoint base;
    private int cellDim;
    private int lastPlane;
    private int displayPlane;
    private int lastWidth = 0;
    private int lastHeight = 0;
    private ViewerMode viewerMode = ViewerMode.COLLISION;

    /**
     * Immutable map of tile types to their rendering colors.
     */
    private static final ImmutableMap<Byte, Color> TILE_TYPE_COLORS = ImmutableMap.<Byte, Color>builder()
            .put(TileType.WATER, new Color(0, 100, 200))
            .put(TileType.CRANDOR_SMEGMA_WATER, new Color(100, 150, 100))
            .put(TileType.TEMPOR_STORM_WATER, new Color(80, 80, 150))
            .put(TileType.DISEASE_WATER, new Color(100, 180, 80))
            .put(TileType.KELP_WATER, new Color(0, 150, 100))
            .put(TileType.SUNBAKED_WATER, new Color(200, 180, 100))
            .put(TileType.JAGGED_REEFS_WATER, new Color(100, 80, 80))
            .put(TileType.SHARP_CRYSTAL_WATER, new Color(180, 100, 200))
            .put(TileType.ICE_WATER, new Color(150, 200, 220))
            .put(TileType.NE_PURPLE_GRAY_WATER, new Color(140, 120, 160))
            .put(TileType.NW_GRAY_WATER, new Color(120, 120, 130))
            .put(TileType.SE_PURPLE_WATER, new Color(160, 100, 180))
            .build();

    private static final Color DEFAULT_COLOR = new Color(150, 150, 150);

    // Predefined rendering colors
    private static final Color BACKGROUND_COLOR = new Color(0xF8, 0xF8, 0xF8);
    private static final Color GRID_COLOR = new Color(0x80, 0x80, 0x80);
    private static final Color COLLISION_COLOR = new Color(0xFF, 0x00, 0x00);
    private static final Color WALL_COLOR = new Color(0x00, 0x00, 0x00);

    // Cache configuration constants
    private static final int CACHE_PX_PER_TILE = 16;
    private static final int CHUNK_SIZE = 64;
    private static final int CHUNK_PX = CHUNK_SIZE * CACHE_PX_PER_TILE;
    private static final int WORLD_MIN_X = 480;
    private static final int WORLD_MAX_X = 4575;
    private static final int WORLD_MIN_Y = 0;
    private static final int WORLD_MAX_Y = 16383;
    private static final int LRU_CACHE_SIZE = 512;

    // Track empty chunks to avoid re-scanning
    private final Set<Long> emptyCollisionChunks = new HashSet<>();
    private final Set<Long> emptyTileTypeChunks = new HashSet<>();
    private final Set<Long> emptyCombinedChunks = new HashSet<>();

    // LRU cache for recently decompressed images (for smooth scrolling)
    private final LinkedHashMap<Long, BufferedImage> collisionLru = createLruCache();
    private final LinkedHashMap<Long, BufferedImage> tileTypeLru = createLruCache();
    private final LinkedHashMap<Long, BufferedImage> combinedLru = createLruCache();

    // Color models for indexed images
    private IndexColorModel collisionColorModel;
    private IndexColorModel tileTypeColorModel;

    // Palette indices for collision mode
    private static final int COL_IDX_BACKGROUND = 0;
    private static final int COL_IDX_COLLISION = 1;
    private static final int COL_IDX_WALL = 2;

    /**
     * Creates an LRU cache for decompressed images.
     */
    private static LinkedHashMap<Long, BufferedImage> createLruCache() {
        return new LinkedHashMap<Long, BufferedImage>(LRU_CACHE_SIZE, 0.75f, true) {
            @Override
            protected boolean removeEldestEntry(Map.Entry<Long, BufferedImage> eldest) {
                return size() > LRU_CACHE_SIZE;
            }
        };
    }

    /**
     * Constructs a new ViewPort object.
     */
    public ViewPort()
    {
        lastPlane = 0;
        displayPlane = 0;
        initColorModels();
    }

    /**
     * Initialize indexed color models for cached rendering.
     */
    private void initColorModels() {
        byte[] r = new byte[4];
        byte[] g = new byte[4];
        byte[] b = new byte[4];

        Color bg = new Color(0xF8, 0xF8, 0xF8);
        r[0] = (byte) bg.getRed();
        g[0] = (byte) bg.getGreen();
        b[0] = (byte) bg.getBlue();

        Color col = new Color(0xFF, 0x00, 0x00);
        r[1] = (byte) col.getRed();
        g[1] = (byte) col.getGreen();
        b[1] = (byte) col.getBlue();

        Color wall = new Color(0x00, 0x00, 0x00);
        r[2] = (byte) wall.getRed();
        g[2] = (byte) wall.getGreen();
        b[2] = (byte) wall.getBlue();

        r[3] = 0; g[3] = 0; b[3] = 0;

        collisionColorModel = new IndexColorModel(8, 4, r, g, b);

        byte[] tr = new byte[16];
        byte[] tg = new byte[16];
        byte[] tb = new byte[16];

        tr[0] = (byte) bg.getRed();
        tg[0] = (byte) bg.getGreen();
        tb[0] = (byte) bg.getBlue();

        setTileTypeColor(tr, tg, tb, TileType.WATER, new Color(0, 100, 200));
        setTileTypeColor(tr, tg, tb, TileType.CRANDOR_SMEGMA_WATER, new Color(100, 150, 100));
        setTileTypeColor(tr, tg, tb, TileType.TEMPOR_STORM_WATER, new Color(80, 80, 150));
        setTileTypeColor(tr, tg, tb, TileType.DISEASE_WATER, new Color(100, 180, 80));
        setTileTypeColor(tr, tg, tb, TileType.KELP_WATER, new Color(0, 150, 100));
        setTileTypeColor(tr, tg, tb, TileType.SUNBAKED_WATER, new Color(200, 180, 100));
        setTileTypeColor(tr, tg, tb, TileType.JAGGED_REEFS_WATER, new Color(100, 80, 80));
        setTileTypeColor(tr, tg, tb, TileType.SHARP_CRYSTAL_WATER, new Color(180, 100, 200));
        setTileTypeColor(tr, tg, tb, TileType.ICE_WATER, new Color(150, 200, 220));
        setTileTypeColor(tr, tg, tb, TileType.NE_PURPLE_GRAY_WATER, new Color(140, 120, 160));
        setTileTypeColor(tr, tg, tb, TileType.NW_GRAY_WATER, new Color(120, 120, 130));
        setTileTypeColor(tr, tg, tb, TileType.SE_PURPLE_WATER, new Color(160, 100, 180));

        Color def = new Color(150, 150, 150);
        tr[13] = (byte) def.getRed();
        tg[13] = (byte) def.getGreen();
        tb[13] = (byte) def.getBlue();

        tileTypeColorModel = new IndexColorModel(8, 16, tr, tg, tb);
    }

    private void setTileTypeColor(byte[] r, byte[] g, byte[] b, byte tileType, Color color) {
        r[tileType] = (byte) color.getRed();
        g[tileType] = (byte) color.getGreen();
        b[tileType] = (byte) color.getBlue();
    }

    // ==================== Chunk Coordinate Utilities ====================

    private long packChunkKey(int chunkX, int chunkY, int plane) {
        return ((long) chunkX << 32) | ((long) (chunkY & 0xFFFF) << 16) | (plane & 0xF);
    }

    private int worldToChunkX(int worldX) {
        return (worldX - WORLD_MIN_X) / CHUNK_SIZE;
    }

    private int worldToChunkY(int worldY) {
        return (worldY - WORLD_MIN_Y) / CHUNK_SIZE;
    }

    private int chunkToWorldX(int chunkX) {
        return WORLD_MIN_X + chunkX * CHUNK_SIZE;
    }

    private int chunkToWorldY(int chunkY) {
        return WORLD_MIN_Y + chunkY * CHUNK_SIZE;
    }

    // ==================== Cache Invalidation ====================

    /**
     * Invalidate all collision chunk caches.
     */
    public void invalidateCollisionCache() {
        emptyCollisionChunks.clear();
        collisionLru.clear();
        // Also invalidate combined since it depends on collision
        invalidateCombinedCache();
    }

    /**
     * Invalidate all tile type chunk caches.
     */
    public void invalidateTileTypeCache() {
        emptyTileTypeChunks.clear();
        tileTypeLru.clear();
        // Also invalidate combined since it depends on tile types
        invalidateCombinedCache();
    }

    /**
     * Invalidate all combined chunk caches.
     */
    public void invalidateCombinedCache() {
        emptyCombinedChunks.clear();
        combinedLru.clear();
    }

    // ==================== Chunk Retrieval ====================

    /**
     * Get a collision chunk image. Uses LRU cache for instant scrolling.
     */
    private BufferedImage getOrRenderCollisionChunk(int chunkX, int chunkY, int plane) {
        long key = packChunkKey(chunkX, chunkY, plane);

        BufferedImage cached = collisionLru.get(key);
        if (cached != null) {
            return cached;
        }

        if (emptyCollisionChunks.contains(key)) {
            return null;
        }

        BufferedImage chunk = renderCollisionChunk(chunkX, chunkY, plane);
        if (chunk == null) {
            emptyCollisionChunks.add(key);
            return null;
        }

        collisionLru.put(key, chunk);
        return chunk;
    }

    /**
     * Get a tile type chunk image. Uses LRU cache for instant scrolling.
     */
    private BufferedImage getOrRenderTileTypeChunk(int chunkX, int chunkY, int plane) {
        long key = packChunkKey(chunkX, chunkY, plane);

        BufferedImage cached = tileTypeLru.get(key);
        if (cached != null) {
            return cached;
        }

        if (emptyTileTypeChunks.contains(key)) {
            return null;
        }

        BufferedImage chunk = renderTileTypeChunk(chunkX, chunkY, plane);
        if (chunk == null) {
            emptyTileTypeChunks.add(key);
            return null;
        }

        tileTypeLru.put(key, chunk);
        return chunk;
    }

    /**
     * Get a combined chunk image. Uses LRU cache for instant scrolling.
     */
    private BufferedImage getOrRenderCombinedChunk(int chunkX, int chunkY, int plane) {
        long key = packChunkKey(chunkX, chunkY, plane);

        BufferedImage cached = combinedLru.get(key);
        if (cached != null) {
            return cached;
        }

        if (emptyCombinedChunks.contains(key)) {
            return null;
        }

        BufferedImage chunk = renderCombinedChunk(chunkX, chunkY, plane);
        if (chunk == null) {
            emptyCombinedChunks.add(key);
            return null;
        }

        combinedLru.put(key, chunk);
        return chunk;
    }

    // ==================== Chunk Rendering ====================

    /**
     * Render a collision chunk to BufferedImage.
     */
    private BufferedImage renderCollisionChunk(int chunkX, int chunkY, int plane) {
        if (Main.getCollision() == null) return null;

        int worldBaseX = chunkToWorldX(chunkX);
        int worldBaseY = chunkToWorldY(chunkY);

        // First pass: check if chunk has any data
        boolean hasData = false;
        outer:
        for (int x = 0; x < CHUNK_SIZE; x++) {
            for (int y = 0; y < CHUNK_SIZE; y++) {
                int worldX = worldBaseX + x;
                int worldY = worldBaseY + y;
                if (worldX > WORLD_MAX_X || worldY > WORLD_MAX_Y) continue;

                byte flag = Main.getCollision().all(worldX, worldY, plane);
                if (flag != Flags.ALL) {
                    hasData = true;
                    break outer;
                }
            }
        }

        if (!hasData) {
            return null;
        }

        // Create and render chunk image
        BufferedImage chunk = new BufferedImage(CHUNK_PX, CHUNK_PX, BufferedImage.TYPE_BYTE_INDEXED, collisionColorModel);
        byte[] pixels = ((DataBufferByte) chunk.getRaster().getDataBuffer()).getData();
        Arrays.fill(pixels, (byte) COL_IDX_BACKGROUND);

        for (int x = 0; x < CHUNK_SIZE; x++) {
            for (int y = 0; y < CHUNK_SIZE; y++) {
                int worldX = worldBaseX + x;
                int worldY = worldBaseY + y;
                if (worldX > WORLD_MAX_X || worldY > WORLD_MAX_Y) continue;

                byte flag = Main.getCollision().all(worldX, worldY, plane);
                if (flag == Flags.ALL) continue;

                int pixelX = x * CACHE_PX_PER_TILE;
                int pixelY = (CHUNK_SIZE - 1 - y) * CACHE_PX_PER_TILE;

                if (flag == Flags.NONE) {
                    fillChunkRect(pixels, pixelX, pixelY, CACHE_PX_PER_TILE, CACHE_PX_PER_TILE, (byte) COL_IDX_COLLISION);
                } else {
                    drawWallsToChunk(pixels, pixelX, pixelY, flag);
                }
            }
        }

        return chunk;
    }

    /**
     * Render a tile type chunk to BufferedImage.
     */
    private BufferedImage renderTileTypeChunk(int chunkX, int chunkY, int plane) {
        if (Main.getTileTypeMap() == null) return null;

        int worldBaseX = chunkToWorldX(chunkX);
        int worldBaseY = chunkToWorldY(chunkY);

        // First pass: check if chunk has any data
        boolean hasData = false;
        outer:
        for (int x = 0; x < CHUNK_SIZE; x++) {
            for (int y = 0; y < CHUNK_SIZE; y++) {
                int worldX = worldBaseX + x;
                int worldY = worldBaseY + y;
                if (worldX > WORLD_MAX_X || worldY > WORLD_MAX_Y) continue;

                byte tileType = Main.getTileTypeMap().getTileType(worldX, worldY, plane);
                if (tileType > 0) {
                    hasData = true;
                    break outer;
                }
            }
        }

        if (!hasData) {
            return null;
        }

        BufferedImage chunk = new BufferedImage(CHUNK_PX, CHUNK_PX, BufferedImage.TYPE_BYTE_INDEXED, tileTypeColorModel);
        byte[] pixels = ((DataBufferByte) chunk.getRaster().getDataBuffer()).getData();
        Arrays.fill(pixels, (byte) 0);

        for (int x = 0; x < CHUNK_SIZE; x++) {
            for (int y = 0; y < CHUNK_SIZE; y++) {
                int worldX = worldBaseX + x;
                int worldY = worldBaseY + y;
                if (worldX > WORLD_MAX_X || worldY > WORLD_MAX_Y) continue;

                byte tileType = Main.getTileTypeMap().getTileType(worldX, worldY, plane);
                if (tileType <= 0) continue;

                int pixelX = x * CACHE_PX_PER_TILE;
                int pixelY = (CHUNK_SIZE - 1 - y) * CACHE_PX_PER_TILE;

                byte colorIndex = (tileType >= 1 && tileType <= 12) ? tileType : 13;
                fillChunkRect(pixels, pixelX, pixelY, CACHE_PX_PER_TILE, CACHE_PX_PER_TILE, colorIndex);
            }
        }

        return chunk;
    }

    /**
     * Render a combined chunk (tile types + collision overlay) to BufferedImage.
     */
    private BufferedImage renderCombinedChunk(int chunkX, int chunkY, int plane) {
        int worldBaseX = chunkToWorldX(chunkX);
        int worldBaseY = chunkToWorldY(chunkY);

        // Check if chunk has any data from either source
        boolean hasData = false;
        outer:
        for (int x = 0; x < CHUNK_SIZE; x++) {
            for (int y = 0; y < CHUNK_SIZE; y++) {
                int worldX = worldBaseX + x;
                int worldY = worldBaseY + y;
                if (worldX > WORLD_MAX_X || worldY > WORLD_MAX_Y) continue;

                // Check collision data
                if (Main.getCollision() != null) {
                    byte flag = Main.getCollision().all(worldX, worldY, plane);
                    if (flag != Flags.ALL) {
                        hasData = true;
                        break outer;
                    }
                }

                // Check tile type data
                if (Main.getTileTypeMap() != null) {
                    byte tileType = Main.getTileTypeMap().getTileType(worldX, worldY, plane);
                    if (tileType > 0) {
                        hasData = true;
                        break outer;
                    }
                }
            }
        }

        if (!hasData) {
            return null;
        }

        // Use RGB for combined mode since we're mixing palettes
        BufferedImage chunk = new BufferedImage(CHUNK_PX, CHUNK_PX, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = chunk.createGraphics();

        // Fill with background
        Color bgColor = new Color(0xF8, 0xF8, 0xF8);
        g.setColor(bgColor);
        g.fillRect(0, 0, CHUNK_PX, CHUNK_PX);

        // First pass: render tile types as background
        if (Main.getTileTypeMap() != null) {
            for (int x = 0; x < CHUNK_SIZE; x++) {
                for (int y = 0; y < CHUNK_SIZE; y++) {
                    int worldX = worldBaseX + x;
                    int worldY = worldBaseY + y;
                    if (worldX > WORLD_MAX_X || worldY > WORLD_MAX_Y) continue;

                    byte tileType = Main.getTileTypeMap().getTileType(worldX, worldY, plane);
                    if (tileType <= 0) continue;

                    int pixelX = x * CACHE_PX_PER_TILE;
                    int pixelY = (CHUNK_SIZE - 1 - y) * CACHE_PX_PER_TILE;

                    Color color = getTileTypeColor(tileType);
                    g.setColor(color);
                    g.fillRect(pixelX, pixelY, CACHE_PX_PER_TILE, CACHE_PX_PER_TILE);
                }
            }
        }

        // Second pass: overlay collision data
        if (Main.getCollision() != null) {
            Color collisionColor = new Color(0xFF, 0x00, 0x00); // Red for blocked tiles
            Color wallColor = new Color(0x00, 0x00, 0x00);       // Black for walls

            for (int x = 0; x < CHUNK_SIZE; x++) {
                for (int y = 0; y < CHUNK_SIZE; y++) {
                    int worldX = worldBaseX + x;
                    int worldY = worldBaseY + y;
                    if (worldX > WORLD_MAX_X || worldY > WORLD_MAX_Y) continue;

                    byte flag = Main.getCollision().all(worldX, worldY, plane);
                    if (flag == Flags.ALL) continue;

                    int pixelX = x * CACHE_PX_PER_TILE;
                    int pixelY = (CHUNK_SIZE - 1 - y) * CACHE_PX_PER_TILE;

                    if (flag == Flags.NONE) {
                        // Fully blocked tile
                        g.setColor(collisionColor);
                        g.fillRect(pixelX, pixelY, CACHE_PX_PER_TILE, CACHE_PX_PER_TILE);
                    } else {
                        // Draw walls
                        g.setColor(wallColor);
                        int size = CACHE_PX_PER_TILE;

                        if ((flag & Flags.WEST) == 0) {
                            g.fillRect(pixelX, pixelY, 1, size);
                        }
                        if ((flag & Flags.EAST) == 0) {
                            g.fillRect(pixelX + size - 1, pixelY, 1, size);
                        }
                        if ((flag & Flags.NORTH) == 0) {
                            g.fillRect(pixelX, pixelY, size, 1);
                        }
                        if ((flag & Flags.SOUTH) == 0) {
                            g.fillRect(pixelX, pixelY + size - 1, size, 1);
                        }
                    }
                }
            }
        }

        g.dispose();
        return chunk;
    }

    // ==================== Pixel Manipulation ====================

    private void fillChunkRect(byte[] pixels, int x, int y, int w, int h, byte colorIndex) {
        for (int dy = 0; dy < h; dy++) {
            int rowStart = (y + dy) * CHUNK_PX + x;
            for (int dx = 0; dx < w; dx++) {
                int idx = rowStart + dx;
                if (idx >= 0 && idx < pixels.length) {
                    pixels[idx] = colorIndex;
                }
            }
        }
    }

    private void drawWallsToChunk(byte[] pixels, int pixelX, int pixelY, byte flag) {
        int size = CACHE_PX_PER_TILE;

        if ((flag & Flags.WEST) == 0) {
            for (int dy = 0; dy < size; dy++) {
                setChunkPixel(pixels, pixelX, pixelY + dy, (byte) COL_IDX_WALL);
            }
        }
        if ((flag & Flags.EAST) == 0) {
            for (int dy = 0; dy < size; dy++) {
                setChunkPixel(pixels, pixelX + size - 1, pixelY + dy, (byte) COL_IDX_WALL);
            }
        }
        if ((flag & Flags.NORTH) == 0) {
            for (int dx = 0; dx < size; dx++) {
                setChunkPixel(pixels, pixelX + dx, pixelY, (byte) COL_IDX_WALL);
            }
        }
        if ((flag & Flags.SOUTH) == 0) {
            for (int dx = 0; dx < size; dx++) {
                setChunkPixel(pixels, pixelX + dx, pixelY + size - 1, (byte) COL_IDX_WALL);
            }
        }
    }

    private void setChunkPixel(byte[] pixels, int x, int y, byte colorIndex) {
        if (x >= 0 && x < CHUNK_PX && y >= 0 && y < CHUNK_PX) {
            pixels[y * CHUNK_PX + x] = colorIndex;
        }
    }

    // ==================== Main Render Method ====================

    /**
     * Renders the game world to the screen.
     */
    public void render(WorldPoint base, int width, int height, int cellDim, ViewerMode viewerMode)
    {
        try
        {
            this.base = base;
            this.cellDim = cellDim;
            this.viewerMode = viewerMode;
            if(lastWidth != width || lastHeight != height)
            {
                lastWidth = width;
                lastHeight = height;
                this.canvas = new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB);
            }

            Graphics2D g2d = canvas.createGraphics();
            g2d.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_NEAREST_NEIGHBOR);

            g2d.setColor(BACKGROUND_COLOR);
            g2d.fillRect(0, 0, width, height);

            if (viewerMode == ViewerMode.COLLISION) {
                renderCollisionMode(g2d, width, height);
            } else if (viewerMode == ViewerMode.TILE_TYPE) {
                renderTileTypeMode(g2d, width, height);
            } else if (viewerMode == ViewerMode.COMBINED) {
                renderCombinedMode(g2d, width, height);
            }

            g2d.setColor(GRID_COLOR);
            float cellWidth = (float) width / cellDim;
            float cellHeight = (float) height / cellDim;
            if(cellWidth >= 10)
            {
                for (int i = 0; i <= cellDim; i++) {
                    g2d.drawLine(Math.round(i * cellWidth), 0, Math.round(i * cellWidth), height);
                    g2d.drawLine(0, Math.round(i * cellHeight), width, Math.round(i * cellHeight));
                }
            }

            g2d.dispose();
        }
        catch(Exception ignored) {
        }
    }

    private void renderCollisionMode(Graphics2D g2d, int width, int height) {
        if (Main.getCollision() == null) return;

        int plane = base.getPlane();
        if (plane < 0 || plane > 3) return;

        int startChunkX = worldToChunkX(base.getX());
        int startChunkY = worldToChunkY(base.getY());
        int endChunkX = worldToChunkX(base.getX() + cellDim - 1);
        int endChunkY = worldToChunkY(base.getY() + cellDim - 1);

        startChunkX = Math.max(0, startChunkX);
        startChunkY = Math.max(0, startChunkY);

        for (int cx = startChunkX; cx <= endChunkX; cx++) {
            for (int cy = startChunkY; cy <= endChunkY; cy++) {
                BufferedImage chunk = getOrRenderCollisionChunk(cx, cy, plane);
                if (chunk != null) {
                    blitChunk(g2d, chunk, cx, cy, width, height);
                }
            }
        }
    }

    private void renderTileTypeMode(Graphics2D g2d, int width, int height) {
        if (Main.getTileTypeMap() == null) return;

        int plane = base.getPlane();
        if (plane < 0 || plane > 3) return;

        if(lastPlane != plane) {
            lastPlane = plane;
            displayPlane = plane;
        }

        int startChunkX = worldToChunkX(base.getX());
        int startChunkY = worldToChunkY(base.getY());
        int endChunkX = worldToChunkX(base.getX() + cellDim - 1);
        int endChunkY = worldToChunkY(base.getY() + cellDim - 1);

        startChunkX = Math.max(0, startChunkX);
        startChunkY = Math.max(0, startChunkY);

        for (int cx = startChunkX; cx <= endChunkX; cx++) {
            for (int cy = startChunkY; cy <= endChunkY; cy++) {
                BufferedImage chunk = getOrRenderTileTypeChunk(cx, cy, plane);
                if (chunk != null) {
                    blitChunk(g2d, chunk, cx, cy, width, height);
                }
            }
        }
    }

    private void renderCombinedMode(Graphics2D g2d, int width, int height) {
        int plane = base.getPlane();
        if (plane < 0 || plane > 3) return;

        if(lastPlane != plane) {
            lastPlane = plane;
            displayPlane = plane;
        }

        int startChunkX = worldToChunkX(base.getX());
        int startChunkY = worldToChunkY(base.getY());
        int endChunkX = worldToChunkX(base.getX() + cellDim - 1);
        int endChunkY = worldToChunkY(base.getY() + cellDim - 1);

        startChunkX = Math.max(0, startChunkX);
        startChunkY = Math.max(0, startChunkY);

        for (int cx = startChunkX; cx <= endChunkX; cx++) {
            for (int cy = startChunkY; cy <= endChunkY; cy++) {
                BufferedImage chunk = getOrRenderCombinedChunk(cx, cy, plane);
                if (chunk != null) {
                    blitChunk(g2d, chunk, cx, cy, width, height);
                }
            }
        }
    }

    private void blitChunk(Graphics2D g2d, BufferedImage chunk, int chunkX, int chunkY, int viewWidth, int viewHeight) {
        int chunkWorldX = chunkToWorldX(chunkX);
        int chunkWorldY = chunkToWorldY(chunkY);

        int viewWorldX = base.getX();
        int viewWorldY = base.getY();

        int overlapX1 = Math.max(chunkWorldX, viewWorldX);
        int overlapY1 = Math.max(chunkWorldY, viewWorldY);
        int overlapX2 = Math.min(chunkWorldX + CHUNK_SIZE, viewWorldX + cellDim);
        int overlapY2 = Math.min(chunkWorldY + CHUNK_SIZE, viewWorldY + cellDim);

        if (overlapX2 <= overlapX1 || overlapY2 <= overlapY1) return;

        int srcX = (overlapX1 - chunkWorldX) * CACHE_PX_PER_TILE;
        int srcY = (CHUNK_SIZE - (overlapY2 - chunkWorldY)) * CACHE_PX_PER_TILE;
        int srcW = (overlapX2 - overlapX1) * CACHE_PX_PER_TILE;
        int srcH = (overlapY2 - overlapY1) * CACHE_PX_PER_TILE;

        float tilePixelWidth = (float) viewWidth / cellDim;
        float tilePixelHeight = (float) viewHeight / cellDim;
        int dstX = Math.round((overlapX1 - viewWorldX) * tilePixelWidth);
        int dstY = viewHeight - Math.round((overlapY2 - viewWorldY) * tilePixelHeight);
        int dstW = Math.round((overlapX2 - overlapX1) * tilePixelWidth);
        int dstH = Math.round((overlapY2 - overlapY1) * tilePixelHeight);

        g2d.drawImage(chunk,
                dstX, dstY, dstX + dstW, dstY + dstH,
                srcX, srcY, srcX + srcW, srcY + srcH,
                null);
    }

    // ==================== Export Feature ====================

    /**
     * Export the full map for a plane as a PNG file.
     * @param mode The viewer mode (collision or tiletype)
     * @param plane The plane to export (0-3)
     * @param outputFile The output PNG file
     * @return true if export succeeded
     */
    public boolean exportMapToPng(ViewerMode mode, int plane, File outputFile) {
        try {
            int worldWidth = WORLD_MAX_X - WORLD_MIN_X + 1;
            int worldHeight = WORLD_MAX_Y - WORLD_MIN_Y + 1;
            int imgWidth = worldWidth * CACHE_PX_PER_TILE;
            int imgHeight = worldHeight * CACHE_PX_PER_TILE;

            BufferedImage fullMap;
            if (mode == ViewerMode.COMBINED) {
                // Combined mode uses RGB
                fullMap = new BufferedImage(imgWidth, imgHeight, BufferedImage.TYPE_INT_RGB);
            } else {
                IndexColorModel colorModel = (mode == ViewerMode.COLLISION) ? collisionColorModel : tileTypeColorModel;
                fullMap = new BufferedImage(imgWidth, imgHeight, BufferedImage.TYPE_BYTE_INDEXED, colorModel);
            }
            Graphics2D g = fullMap.createGraphics();

            // Fill with background
            g.setColor(new Color(0xF8, 0xF8, 0xF8));
            g.fillRect(0, 0, imgWidth, imgHeight);

            // Calculate chunk range
            int maxChunkX = worldToChunkX(WORLD_MAX_X);
            int maxChunkY = worldToChunkY(WORLD_MAX_Y);

            // Render all chunks
            for (int cx = 0; cx <= maxChunkX; cx++) {
                for (int cy = 0; cy <= maxChunkY; cy++) {
                    BufferedImage chunk;
                    if (mode == ViewerMode.COLLISION) {
                        chunk = getOrRenderCollisionChunk(cx, cy, plane);
                    } else if (mode == ViewerMode.TILE_TYPE) {
                        chunk = getOrRenderTileTypeChunk(cx, cy, plane);
                    } else {
                        chunk = getOrRenderCombinedChunk(cx, cy, plane);
                    }

                    if (chunk != null) {
                        int destX = cx * CHUNK_PX;
                        int destY = imgHeight - (cy + 1) * CHUNK_PX;
                        g.drawImage(chunk, destX, destY, null);
                    }
                }
            }

            g.dispose();

            // Write to file
            ImageIO.write(fullMap, "PNG", outputFile);
            return true;

        } catch (Exception e) {
            e.printStackTrace();
            return false;
        }
    }

    /**
     * Get the color model (for export feature in UIFrame).
     */
    public IndexColorModel getCollisionColorModel() {
        return collisionColorModel;
    }

    public IndexColorModel getTileTypeColorModel() {
        return tileTypeColorModel;
    }

    // ==================== Legacy Methods ====================

    private Color getTileTypeColor(byte tileType) {
        return TILE_TYPE_COLORS.getOrDefault(tileType, DEFAULT_COLOR);
    }

    private Cell[][] buildCells() {
        if(lastPlane != base.getPlane()) {
            lastPlane = base.getPlane();
            displayPlane = base.getPlane();
        }
        Cell[][] cells = new Cell[cellDim][cellDim];
        for(int x = 0; x < cellDim; x++) {
            for(int y = 0; y < cellDim; y++) {
                Point cellPoint = new Point(x, y);
                byte flag = Main.getCollision().all(base.getX() + x, base.getY() + y, displayPlane);
                cells[x][y] = new Cell(flag, cellPoint);
            }
        }
        return cells;
    }
}
