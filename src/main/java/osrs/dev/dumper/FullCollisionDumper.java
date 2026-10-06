package osrs.dev.dumper;

import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import net.runelite.cache.IndexType;
import net.runelite.cache.ObjectManager;
import net.runelite.cache.definitions.LocationsDefinition;
import net.runelite.cache.definitions.MapDefinition;
import net.runelite.cache.fs.Archive;
import net.runelite.cache.fs.Index;
import net.runelite.cache.fs.Store;
import net.runelite.cache.region.Region;
import net.runelite.cache.region.RegionLoader;
import net.runelite.cache.util.Djb2;
import net.runelite.cache.util.KeyProvider;

import java.io.BufferedOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.Reader;
import java.io.Writer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.stream.Collectors;

/** Offline export of an existing cache; never downloads or updates its inputs. */
public final class FullCollisionDumper {
    private FullCollisionDumper() {}

    public static void main(String[] args) throws Exception {
        Path cache = Path.of(Dumper.CACHE_DIR);
        Path keys = Path.of(Dumper.XTEA_DIR, "keys.json");
        Path output = Path.of("build", "full-collision");
        for (int i = 0; i < args.length; i++) {
            if ("--help".equals(args[i])) {
                System.out.println("FullCollisionDumper [--cache directory] [--keys keys.json] [--output new-directory]");
                return;
            }
            String option = args[i];
            if (!(option.equals("--cache") || option.equals("--keys") || option.equals("--output"))
                    || i + 1 == args.length) {
                throw new IllegalArgumentException("Unknown option or missing value: " + option);
            }
            Path value = Path.of(args[++i]);
            switch (option) {
                case "--cache": cache = value; break;
                case "--keys": keys = value; break;
                case "--output": output = value; break;
                default: throw new AssertionError(option);
            }
        }
        export(cache, keys, output);
    }

    static void export(Path cache, Path keys, Path output) throws Exception {
        if (!Files.isRegularFile(cache.resolve("main_file_cache.dat2")) || !Files.isRegularFile(keys)) {
            throw new IOException("An existing disk cache and OpenRS2 keys.json are required: " + cache + ", " + keys);
        }
        if (Files.exists(output)) {
            throw new IOException("Output already exists; choose a new --output directory: " + output);
        }
        KeyProvider keyProvider = readKeys(keys);
        Map<String, Object> manifest = new LinkedHashMap<>();
        manifest.put("format", "REGC");
        manifest.put("version", FullCollisionMap.FORMAT_VERSION);
        manifest.put("collision_policy", "cachemapdumper-navigation-objects-v1");
        manifest.put("cache_files", cacheFingerprints(cache));
        manifest.put("keys_sha256", sha256(keys));
        manifest.put("java_version", System.getProperty("java.version"));

        FullCollisionMap map;
        Map<Integer, String> unavailable = new TreeMap<>();
        try (Store store = new Store(cache.toFile())) {
            store.load();
            Index index = store.getIndex(IndexType.MAPS);
            if (index == null) {
                throw new IOException("Cache has no maps index");
            }
            manifest.put("maps_index_revision", index.getRevision());
            manifest.put("maps_index_crc", index.getCrc());
            ObjectManager objects = new ObjectManager(store);
            objects.load();
            RegionLoader loader = new RegionLoader(store, keyProvider);
            Set<Integer> archiveNames = new HashSet<>();
            for (Archive archive : index.getArchives()) {
                archiveNames.add(archive.getNameHash());
            }
            List<Region> ready = new ArrayList<>();
            for (int id = 0; id <= 0xffff; id++) {
                boolean terrainExists = index.isNamed()
                        ? archiveNames.contains(Djb2.hash("m" + (id >> 8) + "_" + (id & 255)))
                        : index.getArchive(id) != null;
                if (!terrainExists) {
                    continue;
                }
                try {
                    MapDefinition terrain = loader.loadMapDef(id);
                    LocationsDefinition locations = loader.loadLocDef(id);
                    if (terrain == null || locations == null) {
                        unavailable.put(id, terrain == null ? "missing-terrain" : "missing-locations");
                        continue;
                    }
                    // loadRegionFromArchive also accepts terrain-only regions;
                    // that would turn missing object data into false open ground.
                    Region region = new Region(id);
                    region.loadTerrain(terrain);
                    region.loadLocations(locations);
                    ready.add(region);
                } catch (IOException | RuntimeException e) {
                    unavailable.put(id, "decode-failed: " + e.getClass().getSimpleName() + ": " + e.getMessage());
                }
            }
            if (ready.isEmpty()) {
                throw new IOException("No complete regions decoded; check the cache format and matching XTEA keys");
            }
            System.out.printf("Decoded %d complete regions; %d terrain regions unavailable%n", ready.size(), unavailable.size());
            map = FullCollisionMap.generate(ready, objects::getObject);
        }

        Files.createDirectories(output.toAbsolutePath().getParent());
        Files.createDirectory(output);
        List<Map<String, Object>> collisionFiles = new ArrayList<>();
        long collisionBytes = 0;
        for (int region : map.regionIds()) {
            Path binary = output.resolve("region_" + region + ".regc");
            try (BufferedOutputStream stream = new BufferedOutputStream(Files.newOutputStream(binary))) {
                map.writeRegion(region, stream);
            }
            Map<String, Object> record = new LinkedHashMap<>();
            record.put("file", binary.getFileName().toString());
            record.put("bytes", Files.size(binary));
            record.put("sha256", sha256(binary));
            collisionFiles.add(record);
            collisionBytes += Files.size(binary);
        }
        manifest.put("region_count", map.regionIds().size());
        manifest.put("regions", map.regionIds());
        manifest.put("unavailable_regions", unavailable);
        manifest.put("collision_bytes", collisionBytes);
        manifest.put("collision_files", collisionFiles);
        // The manifest is the completion marker, so a failed region write must
        // never leave a dataset that a reader can mistake for a complete export.
        try (Writer writer = Files.newBufferedWriter(output.resolve("manifest.json"))) {
            new GsonBuilder().setPrettyPrinting().create().toJson(manifest, writer);
        }
        System.out.printf("Exported %d REGC regions to %s (%d bytes)%n",
                map.regionIds().size(), output.toAbsolutePath(), collisionBytes);
    }

    static KeyProvider readKeys(Path path) throws IOException {
        Map<Integer, int[]> keys = new HashMap<>();
        try (Reader reader = Files.newBufferedReader(path)) {
            for (JsonElement element : JsonParser.parseReader(reader).getAsJsonArray()) {
                JsonObject entry = element.getAsJsonObject();
                int region = entry.get("mapsquare").getAsInt();
                JsonArray values = entry.getAsJsonArray("key");
                if (region < 0 || region > 0xffff || values.size() != 4) {
                    throw new IOException("Invalid XTEA key for region " + region);
                }
                int[] key = new int[4];
                for (int i = 0; i < 4; i++) {
                    key[i] = values.get(i).getAsInt();
                }
                if (keys.putIfAbsent(region, key) != null) {
                    throw new IOException("Duplicate XTEA key for region " + region);
                }
            }
        }
        // All-zero keys also allow genuinely unencrypted location archives to
        // decode. Failed decryption is recorded as unavailable, never as empty.
        int[] unencrypted = new int[4];
        return region -> keys.getOrDefault(region, unencrypted);
    }

    private static List<Map<String, Object>> cacheFingerprints(Path directory) throws IOException {
        List<Path> files;
        try (var stream = Files.list(directory)) {
            files = stream.filter(path -> path.getFileName().toString().startsWith("main_file_cache."))
                    .sorted().collect(Collectors.toList());
        }
        List<Map<String, Object>> result = new ArrayList<>();
        for (Path file : files) {
            Map<String, Object> record = new LinkedHashMap<>();
            record.put("file", file.getFileName().toString());
            record.put("bytes", Files.size(file));
            record.put("sha256", sha256(file));
            result.add(record);
        }
        return result;
    }

    private static String sha256(Path path) throws IOException {
        MessageDigest digest;
        try {
            digest = MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException e) {
            throw new AssertionError(e);
        }
        try (InputStream stream = Files.newInputStream(path)) {
            byte[] buffer = new byte[64 * 1024];
            int count;
            while ((count = stream.read(buffer)) != -1) {
                digest.update(buffer, 0, count);
            }
        }
        StringBuilder hex = new StringBuilder();
        for (byte value : digest.digest()) {
            hex.append(String.format("%02x", value & 255));
        }
        return hex.toString();
    }
}
