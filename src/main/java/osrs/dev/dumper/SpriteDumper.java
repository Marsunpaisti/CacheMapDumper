package osrs.dev.dumper;

import com.google.gson.GsonBuilder;
import net.runelite.cache.IndexType;
import net.runelite.cache.SpriteManager;
import net.runelite.cache.fs.Index;
import net.runelite.cache.fs.Store;

import java.io.IOException;
import java.io.Writer;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.time.Instant;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.stream.Stream;

/** Every cache sprite as a PNG, named <sprite id>-<frame>.png by RuneLite's SpriteManager. */
public final class SpriteDumper {
    private SpriteDumper() {}

    public static void main(String[] args) throws Exception {
        Path cache = Path.of(Dumper.CACHE_DIR);
        Path output = Path.of("build", "sprites");
        for (int i = 0; i < args.length; i++) {
            if ("--help".equals(args[i])) {
                System.out.println("SpriteDumper [--cache directory] [--output new-directory]");
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
        Path staging = Files.createTempDirectory(output.getParent(), ".sprite-dump-");
        try {
            Map<String, Object> manifest = new LinkedHashMap<>();
            manifest.put("schemaVersion", 1);
            manifest.put("generatedAt", Instant.now().toString());
            // Keep this coordinate in sync with the pinned dependency in build.gradle.kts.
            manifest.put("cacheDecoder", "net.runelite:cache:1.13.1");
            manifest.put("cachePath", cache.toAbsolutePath().toString());
            int spriteCount;
            try (Store store = new Store(cache.toFile())) {
                store.load();
                Index index = store.getIndex(IndexType.SPRITES);
                if (index == null) throw new IOException("Cache has no sprite index");
                manifest.put("spritesIndexRevision", index.getRevision());
                manifest.put("spritesIndexCrc", index.getCrc());
                SpriteManager sprites = new SpriteManager(store);
                sprites.load();
                sprites.export(staging.toFile());
                spriteCount = sprites.getSprites().size();
            }
            manifest.put("spriteFrameCount", spriteCount);
            try (Writer writer = Files.newBufferedWriter(staging.resolve("manifest.json"))) {
                new GsonBuilder().setPrettyPrinting().create().toJson(manifest, writer);
            }
            // Publish only a complete export; never replace an existing one.
            Files.move(staging, output);
            System.out.printf("Exported %d sprite frames to %s%n", spriteCount, output);
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
