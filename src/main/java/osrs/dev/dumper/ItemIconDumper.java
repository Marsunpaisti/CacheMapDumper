package osrs.dev.dumper;

import com.google.gson.GsonBuilder;
import net.runelite.cache.IndexType;
import net.runelite.cache.ItemManager;
import net.runelite.cache.SpriteManager;
import net.runelite.cache.TextureManager;
import net.runelite.cache.definitions.ItemDefinition;
import net.runelite.cache.definitions.loaders.ModelLoader;
import net.runelite.cache.definitions.providers.ModelProvider;
import net.runelite.cache.fs.Archive;
import net.runelite.cache.fs.Index;
import net.runelite.cache.fs.Store;
import net.runelite.cache.item.ItemSpriteFactory;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.io.Writer;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

/**
 * Every named item's inventory icon, rendered from its model the way the client draws it, by RuneLite's
 * ItemSpriteFactory: <item id>.png, quantity 1, with the inventory's black outline and shadow.
 */
public final class ItemIconDumper {
    // The arguments RuneLite's own ItemSpriteFactory test renders inventory icons with.
    private static final int BORDER = 1;
    private static final int SHADOW_COLOUR = 0x302020;

    private ItemIconDumper() {}

    public static void main(String[] args) throws Exception {
        Path cache = Path.of(Dumper.CACHE_DIR);
        Path output = Path.of("build", "item-icons");
        for (int i = 0; i < args.length; i++) {
            if ("--help".equals(args[i])) {
                System.out.println("ItemIconDumper [--cache directory] [--output new-directory]");
                System.out.println("Defaults: cache=" + cache + ", output=" + output);
                return;
            }
            String option = args[i];
            if (!(option.equals("--cache") || option.equals("--output"))
                    || i + 1 == args.length || args[i + 1].startsWith("--")) {
                throw new IllegalArgumentException("Unknown option or missing value: " + option);
            }
            Path value = Path.of(args[++i]);
            if (option.equals("--cache")) cache = value;
            else output = value;
        }
        export(cache, output);
    }

    static void export(Path cache, Path output) throws Exception {
        if (!Files.isRegularFile(cache.resolve("main_file_cache.dat2"))
                || !Files.isRegularFile(cache.resolve("main_file_cache.idx255"))) {
            throw new IOException("An existing disk cache is required: " + cache);
        }
        output = output.toAbsolutePath();
        if (Files.exists(output, LinkOption.NOFOLLOW_LINKS)) {
            throw new IOException("Output already exists; choose a new --output directory: " + output);
        }
        Files.createDirectories(output.getParent());
        Path staging = Files.createTempDirectory(output.getParent(), ".item-icon-dump-");
        try {
            Map<String, Object> manifest = new LinkedHashMap<>();
            manifest.put("schemaVersion", 1);
            manifest.put("generatedAt", Instant.now().toString());
            // Keep this coordinate in sync with the pinned dependency in build.gradle.kts.
            manifest.put("cacheDecoder", "net.runelite:cache:1.13.1");
            manifest.put("cachePath", cache.toAbsolutePath().toString());
            manifest.put("quantity", 1);
            int rendered = 0;
            List<Integer> failed = new ArrayList<>();
            try (Store store = new Store(cache.toFile())) {
                store.load();
                Index models = store.getIndex(IndexType.MODELS);
                if (models == null) throw new IOException("Cache has no model index");
                manifest.put("modelsIndexRevision", models.getRevision());
                ItemManager items = new ItemManager(store);
                items.load();
                items.link();
                SpriteManager sprites = new SpriteManager(store);
                sprites.load();
                TextureManager textures = new TextureManager(store);
                textures.load();
                ModelProvider modelProvider = modelId -> {
                    Archive archive = models.getArchive(modelId);
                    byte[] data = archive.decompress(store.getStorage().loadArchive(archive));
                    return new ModelLoader().load(modelId, data);
                };
                List<ItemDefinition> sorted = new ArrayList<>(items.getItems());
                sorted.sort(Comparator.comparingInt(item -> item.id));
                for (ItemDefinition item : sorted) {
                    if (item.name == null || item.name.equalsIgnoreCase("null")) continue;
                    try {
                        BufferedImage icon = ItemSpriteFactory.createSprite(items, modelProvider, sprites, textures,
                                item.id, 1, BORDER, SHADOW_COLOUR, false);
                        if (icon == null) {
                            failed.add(item.id);
                            continue;
                        }
                        ImageIO.write(icon, "PNG", staging.resolve(item.id + ".png").toFile());
                        ++rendered;
                    } catch (Exception e) {
                        failed.add(item.id);
                    }
                }
            }
            manifest.put("iconCount", rendered);
            manifest.put("failedItemIds", failed);
            try (Writer writer = Files.newBufferedWriter(staging.resolve("manifest.json"))) {
                new GsonBuilder().setPrettyPrinting().create().toJson(manifest, writer);
            }
            // Publish only a complete export; never replace an existing one.
            Files.move(staging, output);
            System.out.printf("Rendered %d item icons to %s (%d failed)%n", rendered, output, failed.size());
        } finally {
            if (Files.exists(staging)) {
                try (Stream<Path> files = Files.walk(staging)) {
                    for (Path file : (Iterable<Path>) files.sorted(Comparator.reverseOrder())::iterator) {
                        Files.delete(file);
                    }
                }
            }
        }
    }
}
