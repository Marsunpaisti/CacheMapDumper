package osrs.dev.dumper;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.stream.JsonWriter;
import net.runelite.cache.ConfigType;
import net.runelite.cache.IndexType;
import net.runelite.cache.definitions.NpcDefinition;
import net.runelite.cache.definitions.SequenceDefinition;
import net.runelite.cache.definitions.loaders.NpcLoader;
import net.runelite.cache.definitions.loaders.SequenceLoader;
import net.runelite.cache.fs.Archive;
import net.runelite.cache.fs.ArchiveFiles;
import net.runelite.cache.fs.FSFile;
import net.runelite.cache.fs.Index;
import net.runelite.cache.fs.Store;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.Writer;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.stream.Collectors;

/** Complete decoded NPC definitions plus the animation (sequence) timings a simulator needs. */
public final class NpcDefinitionDumper {
    private static final Gson JSON = new GsonBuilder().serializeNulls().disableHtmlEscaping().create();
    private static final List<String> OUTPUT_FILES = List.of("npc-definitions.json", "sequences.json", "manifest.json");
    // The client advances animations once per 20 ms client cycle; a game tick is 600 ms.
    private static final int CLIENT_CYCLES_PER_TICK = 30;

    private NpcDefinitionDumper() {}

    public static void main(String[] args) throws Exception {
        Path cache = Path.of(Dumper.CACHE_DIR);
        Path output = Path.of("build", "npcs");
        for (int i = 0; i < args.length; i++) {
            if ("--help".equals(args[i])) {
                System.out.println("NpcDefinitionDumper [--cache directory] [--output new-directory]");
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
        Path staging = Files.createTempDirectory(output.getParent(), ".npc-dump-");
        try {
            Map<String, Object> manifest = new LinkedHashMap<>();
            manifest.put("schemaVersion", 1);
            manifest.put("generatedAt", Instant.now().toString());
            // Keep this coordinate in sync with the pinned dependency in build.gradle.kts.
            manifest.put("cacheDecoder", "net.runelite:cache:1.13.1");
            manifest.put("cachePath", cache.toAbsolutePath().toString());
            manifest.put("clientCyclesPerTick", CLIENT_CYCLES_PER_TICK);

            int npcCount;
            int sequenceCount;
            try (Store store = new Store(cache.toFile())) {
                store.load();
                Index index = store.getIndex(IndexType.CONFIGS);
                if (index == null) throw new IOException("Cache has no config index");
                manifest.put("configsIndexRevision", index.getRevision());
                manifest.put("configsIndexCrc", index.getCrc());

                ArchiveFiles npcFiles = loadArchive(store, index, ConfigType.NPC, "npc", manifest);
                NpcLoader npcLoader = new NpcLoader().configureForRevision(index.getArchive(ConfigType.NPC.getId()).getRevision());
                npcCount = 0;
                try (JsonWriter writer = new JsonWriter(Files.newBufferedWriter(staging.resolve("npc-definitions.json")))) {
                    writer.beginObject();
                    writer.name("schemaVersion").value(1);
                    writer.name("npcs").beginObject();
                    for (FSFile file : sortedFiles(npcFiles)) {
                        NpcDefinition npc = npcLoader.load(file.getFileId(), file.getContents());
                        if (npc.params != null) npc.params = new TreeMap<>(npc.params);
                        writer.name(Integer.toString(npc.id));
                        JSON.toJson(npc, NpcDefinition.class, writer);
                        ++npcCount;
                    }
                    writer.endObject();
                    writer.endObject();
                }

                ArchiveFiles sequenceFiles = loadArchive(store, index, ConfigType.SEQUENCE, "sequence", manifest);
                SequenceLoader sequenceLoader = new SequenceLoader()
                        .configureForRevision(index.getArchive(ConfigType.SEQUENCE.getId()).getRevision());
                sequenceCount = 0;
                try (JsonWriter writer = new JsonWriter(Files.newBufferedWriter(staging.resolve("sequences.json")))) {
                    writer.beginObject();
                    writer.name("schemaVersion").value(1);
                    writer.name("sequences").beginObject();
                    for (FSFile file : sortedFiles(sequenceFiles)) {
                        SequenceDefinition sequence = sequenceLoader.load(file.getFileId(), file.getContents());
                        writer.name(Integer.toString(sequence.getId()));
                        JSON.toJson(sequenceJson(sequence), writer);
                        ++sequenceCount;
                    }
                    writer.endObject();
                    writer.endObject();
                }
            }
            manifest.put("npcDefinitionCount", npcCount);
            manifest.put("sequenceCount", sequenceCount);

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
            System.out.printf("Exported %d NPC definitions and %d sequences to %s%n", npcCount, sequenceCount, output);
        } finally {
            if (Files.exists(staging)) {
                for (String name : OUTPUT_FILES) Files.deleteIfExists(staging.resolve(name));
                Files.delete(staging);
            }
        }
    }

    private static ArchiveFiles loadArchive(Store store, Index index, ConfigType type, String label,
                                            Map<String, Object> manifest) throws IOException {
        Archive archive = index.getArchive(type.getId());
        if (archive == null) throw new IOException("Cache has no " + label + " config archive");
        byte[] data = store.getStorage().loadArchive(archive);
        if (data == null) throw new IOException("Cache " + label + " config archive is missing");
        manifest.put(label + "ArchiveRevision", archive.getRevision());
        manifest.put(label + "ArchiveSha256", sha256(new ByteArrayInputStream(data)));
        ArchiveFiles files = archive.getFiles(data);
        if (files.getFiles().isEmpty()) throw new IOException("Cache " + label + " config archive is empty");
        return files;
    }

    private static List<FSFile> sortedFiles(ArchiveFiles files) {
        return files.getFiles().stream().sorted(Comparator.comparingInt(FSFile::getFileId)).collect(Collectors.toList());
    }

    // The fields that time and identify an animation. Frame sounds are left out: they are cosmetic and a Guava
    // multimap that Gson can't serialize as is.
    private static JsonObject sequenceJson(SequenceDefinition sequence) {
        JsonObject json = new JsonObject();
        json.addProperty("id", sequence.getId());
        json.addProperty("debugName", sequence.getDebugName());
        json.add("frameLengths", intArray(sequence.getFrameLengths()));
        json.addProperty("frameStep", sequence.getFrameStep());
        json.addProperty("maxLoops", sequence.getMaxLoops());
        json.addProperty("replyMode", sequence.getReplyMode());
        json.addProperty("precedenceAnimating", sequence.getPrecedenceAnimating());
        json.addProperty("priority", sequence.getPriority());
        json.addProperty("forcedPriority", sequence.getForcedPriority());
        json.addProperty("animMayaID", sequence.getAnimMayaID());
        json.addProperty("animMayaStart", sequence.getAnimMayaStart());
        json.addProperty("animMayaEnd", sequence.getAnimMayaEnd());
        // Frame-based animations last the sum of their frame lengths. Skeletal ("maya") animations play one frame
        // per client cycle from animMayaStart to animMayaEnd (unverified against the client; raw values are above).
        // Death animations usually end on a very long final frame that holds the last pose until the NPC
        // despawns, so their sum is not how long the visible animation lasts; read frameLengths for that.
        Integer cycles = null;
        if (sequence.getAnimMayaID() >= 0) {
            cycles = sequence.getAnimMayaEnd() - sequence.getAnimMayaStart();
        } else if (sequence.getFrameLengths() != null) {
            int sum = 0;
            for (int length : sequence.getFrameLengths()) sum += length;
            cycles = sum;
        }
        json.addProperty("lengthClientCycles", cycles);
        return json;
    }

    private static JsonArray intArray(int[] values) {
        if (values == null) return null;
        JsonArray array = new JsonArray();
        for (int value : values) array.add(value);
        return array;
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
