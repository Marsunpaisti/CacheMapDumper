package osrs.dev.dumper;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.google.gson.stream.JsonReader;
import com.google.gson.stream.JsonToken;
import com.google.gson.stream.JsonWriter;
import net.runelite.cache.ConfigType;
import net.runelite.cache.IndexType;
import net.runelite.cache.ItemManager;
import net.runelite.cache.definitions.ItemDefinition;
import net.runelite.cache.fs.Archive;
import net.runelite.cache.fs.Index;
import net.runelite.cache.fs.Store;

import java.io.IOException;
import java.io.InputStream;
import java.io.Writer;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/** Complete decoded item definitions plus a separate snapshot of RuneLite's published item stats. */
public final class ItemDefinitionDumper {
    static final String STATS_URL = "https://static.runelite.net/item/stats.ids.min.json";
    private static final Gson JSON = new GsonBuilder().serializeNulls().disableHtmlEscaping().create();
    private static final List<String> OUTPUT_FILES = List.of("item-definitions.json", "item-stats.json", "manifest.json");

    private ItemDefinitionDumper() {}

    public static void main(String[] args) throws Exception {
        Path cache = Path.of(Dumper.CACHE_DIR);
        Path output = Path.of("build", "items");
        Path stats = null;
        for (int i = 0; i < args.length; i++) {
            if ("--help".equals(args[i])) {
                System.out.println("ItemDefinitionDumper [--cache directory] [--output new-directory] [--item-stats file.json]");
                System.out.println("Defaults: cache=" + cache + ", output=" + output + ", item stats=" + STATS_URL);
                return;
            }
            String option = args[i];
            if (!(option.equals("--cache") || option.equals("--output") || option.equals("--item-stats"))
                    || i + 1 == args.length || args[i + 1].startsWith("--")) {
                throw new IllegalArgumentException("Unknown option or missing value: " + option);
            }
            Path value = Path.of(args[++i]);
            switch (option) {
                case "--cache": cache = value; break;
                case "--output": output = value; break;
                case "--item-stats": stats = value; break;
                default: throw new AssertionError(option);
            }
        }
        export(cache, stats, output);
    }

    static void export(Path cache, Path statsSource, Path output) throws Exception {
        if (!Files.isRegularFile(cache.resolve("main_file_cache.dat2"))
                || !Files.isRegularFile(cache.resolve("main_file_cache.idx255"))) {
            throw new IOException("An existing disk cache is required: " + cache);
        }
        output = output.toAbsolutePath();
        if (Files.exists(output, LinkOption.NOFOLLOW_LINKS)) {
            throw new IOException("Output already exists; choose a new --output directory: " + output);
        }
        Files.createDirectories(output.getParent());
        Path staging = Files.createTempDirectory(output.getParent(), ".item-dump-");
        try {
            Path statsFile = staging.resolve("item-stats.json");
            if (statsSource == null) {
                HttpClient client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(30))
                        .followRedirects(HttpClient.Redirect.NORMAL).build();
                HttpRequest request = HttpRequest.newBuilder(URI.create(STATS_URL))
                        .timeout(Duration.ofSeconds(120)).header("User-Agent", "CacheMapDumper/1.0").build();
                HttpResponse<byte[]> response = client.send(request, HttpResponse.BodyHandlers.ofByteArray());
                if (response.statusCode() != 200) {
                    throw new IOException("Item stats download returned HTTP " + response.statusCode() + ": " + STATS_URL);
                }
                Files.write(statsFile, response.body());
            } else {
                Files.copy(statsSource, statsFile);
            }
            Map<Integer, JsonObject> stats = readStats(statsFile);
            Map<String, Object> manifest = new LinkedHashMap<>();
            manifest.put("schemaVersion", 1);
            manifest.put("generatedAt", Instant.now().toString());
            // Keep this coordinate in sync with the pinned dependency in build.gradle.kts.
            manifest.put("cacheDecoder", "net.runelite:cache:1.13.1");
            manifest.put("cachePath", cache.toAbsolutePath().toString());
            manifest.put("itemStatsSource", statsSource == null ? STATS_URL : statsSource.toAbsolutePath().toString());
            manifest.put("itemStatsCount", stats.size());
            manifest.put("equipmentStatsCount", stats.values().stream().filter(s -> s.has("equipment")
                    && s.get("equipment").isJsonObject()).count());

            int count;
            try (Store store = new Store(cache.toFile())) {
                store.load();
                Index index = store.getIndex(IndexType.CONFIGS);
                Archive archive = index == null ? null : index.getArchive(ConfigType.ITEM.getId());
                if (archive == null) throw new IOException("Cache has no item config archive");
                byte[] archiveData = store.getStorage().loadArchive(archive);
                if (archiveData == null) throw new IOException("Cache item config archive is missing");
                manifest.put("configsIndexRevision", index.getRevision());
                manifest.put("configsIndexCrc", index.getCrc());
                manifest.put("itemArchiveRevision", archive.getRevision());
                manifest.put("itemArchiveSha256", sha256(new java.io.ByteArrayInputStream(archiveData)));

                ItemManager manager = new ItemManager(store);
                manager.load();
                List<ItemDefinition> items = new ArrayList<>(manager.getItems());
                items.sort(Comparator.comparingInt(ItemDefinition::getId));
                count = items.size();
                if (count == 0 || count != archive.getFileData().length) {
                    throw new IOException("Item definition count does not match the nonempty config archive");
                }
                try (JsonWriter writer = new JsonWriter(Files.newBufferedWriter(staging.resolve("item-definitions.json")))) {
                    writer.beginObject();
                    writer.name("schemaVersion").value(1);
                    writer.name("raw");
                    writeItems(writer, items);
                    // Linking changes notes, bought variants and placeholders, so preserve pre-link data first.
                    manager.link();
                    writer.name("linked");
                    writeItems(writer, items);
                    writer.endObject();
                }
                manifest.put("itemDefinitionCount", count);
                manifest.put("definitionsWithStats", items.stream().filter(item -> stats.containsKey(item.id)).count());
                manifest.put("statsWithoutDefinition", stats.keySet().stream().filter(id -> manager.getItem(id) == null).count());
            }
            Map<String, Object> files = new LinkedHashMap<>();
            for (String name : OUTPUT_FILES.subList(0, 2)) {
                Path file = staging.resolve(name);
                files.put(name, Map.of("bytes", Files.size(file), "sha256", sha256(Files.newInputStream(file))));
            }
            manifest.put("files", files);
            try (Writer writer = Files.newBufferedWriter(staging.resolve("manifest.json"))) {
                new GsonBuilder().setPrettyPrinting().create().toJson(manifest, writer);
            }
            // Publish only after both payloads and their manifest are complete; never replace an existing export.
            Files.move(staging, output);
            System.out.printf("Exported %d item definitions and %d item stats to %s%n", count, stats.size(), output);
        } finally {
            if (Files.exists(staging)) {
                for (String name : OUTPUT_FILES) Files.deleteIfExists(staging.resolve(name));
                Files.delete(staging);
            }
        }
    }

    private static void writeItems(JsonWriter writer, List<ItemDefinition> items) throws IOException {
        writer.beginObject();
        for (ItemDefinition item : items) {
            if (item.params != null) item.params = new TreeMap<>(item.params);
            writer.name(Integer.toString(item.id));
            JSON.toJson(item, ItemDefinition.class, writer);
        }
        writer.endObject();
    }

    static Map<Integer, JsonObject> readStats(Path path) throws IOException {
        Map<Integer, JsonObject> result = new TreeMap<>();
        try (JsonReader reader = new JsonReader(Files.newBufferedReader(path))) {
            reader.beginObject();
            while (reader.hasNext()) {
                String key = reader.nextName();
                int id = Integer.parseInt(key);
                if (id < 0 || !Integer.toString(id).equals(key)) throw new IOException("Invalid item stats ID: " + key);
                JsonElement value = JsonParser.parseReader(reader);
                if (!value.isJsonObject() || result.putIfAbsent(id, value.getAsJsonObject()) != null) {
                    throw new IOException("Invalid or duplicate item stats record: " + key);
                }
                JsonElement equipment = value.getAsJsonObject().get("equipment");
                if (equipment != null && !equipment.isJsonNull() && !equipment.isJsonObject()) {
                    throw new IOException("Invalid equipment stats for item " + id);
                }
            }
            reader.endObject();
            if (reader.peek() != JsonToken.END_DOCUMENT || result.isEmpty()) {
                throw new IOException("Expected a nonempty item stats object with no trailing data");
            }
        } catch (RuntimeException e) {
            throw new IOException("Invalid RuneLite item stats JSON: " + path, e);
        }
        return result;
    }

    private static String sha256(InputStream input) throws IOException {
        try (InputStream stream = input) {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] buffer = new byte[64 * 1024];
            int count;
            while ((count = stream.read(buffer)) != -1) digest.update(buffer, 0, count);
            StringBuilder hex = new StringBuilder();
            for (byte value : digest.digest()) hex.append(String.format("%02x", value & 255));
            return hex.toString();
        } catch (NoSuchAlgorithmException e) {
            throw new AssertionError(e);
        }
    }
}
