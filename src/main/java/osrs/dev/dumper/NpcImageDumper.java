package osrs.dev.dumper;

import com.google.gson.GsonBuilder;
import net.runelite.cache.ConfigType;
import net.runelite.cache.IndexType;
import net.runelite.cache.NpcManager;
import net.runelite.cache.SpriteManager;
import net.runelite.cache.TextureManager;
import net.runelite.cache.definitions.FrameDefinition;
import net.runelite.cache.definitions.FramemapDefinition;
import net.runelite.cache.definitions.NpcDefinition;
import net.runelite.cache.definitions.SequenceDefinition;
import net.runelite.cache.definitions.loaders.FrameLoader;
import net.runelite.cache.definitions.loaders.FramemapLoader;
import net.runelite.cache.definitions.loaders.ModelLoader;
import net.runelite.cache.definitions.loaders.SequenceLoader;
import net.runelite.cache.definitions.providers.ModelProvider;
import net.runelite.cache.fs.Archive;
import net.runelite.cache.fs.ArchiveFiles;
import net.runelite.cache.fs.FSFile;
import net.runelite.cache.fs.Index;
import net.runelite.cache.fs.Store;
import net.runelite.cache.item.NpcSpriteFactory;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.io.Writer;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Stream;

/**
 * A still picture of every named NPC with models: <npc id>.png, its models merged and lit as the client builds the
 * NPC, in the first frame of its standing animation, seen from the front and above, cut to the pixels drawn
 * (NpcSpriteFactory). Skeletal ("maya") standing animations aren't applied; NPCs that draw nothing get no picture.
 */
public final class NpcImageDumper {
    private static final int SIZE = 128;
    // In the client's 2048 units to a circle: 45 degrees above level, turned to face the viewer.
    private static final int PITCH = 256;
    private static final int YAW = 0;

    private NpcImageDumper() {}

    public static void main(String[] args) throws Exception {
        Path cache = Path.of(Dumper.CACHE_DIR);
        Path output = Path.of("build", "npc-images");
        Set<Integer> only = null;
        for (int i = 0; i < args.length; i++) {
            if ("--help".equals(args[i])) {
                System.out.println("NpcImageDumper [--cache directory] [--output new-directory] [--ids id,id,...]");
                System.out.println("Defaults: cache=" + cache + ", output=" + output + ", every NPC");
                return;
            }
            String option = args[i];
            if (!(option.equals("--cache") || option.equals("--output") || option.equals("--ids"))
                    || i + 1 == args.length || args[i + 1].startsWith("--")) {
                throw new IllegalArgumentException("Unknown option or missing value: " + option);
            }
            String value = args[++i];
            if (option.equals("--cache")) {
                cache = Path.of(value);
            } else if (option.equals("--output")) {
                output = Path.of(value);
            } else {
                only = new HashSet<>();
                for (String id : value.split(",")) only.add(Integer.parseInt(id.trim()));
            }
        }
        export(cache, output, only);
    }

    static void export(Path cache, Path output, Set<Integer> only) throws Exception {
        if (!Files.isRegularFile(cache.resolve("main_file_cache.dat2"))
                || !Files.isRegularFile(cache.resolve("main_file_cache.idx255"))) {
            throw new IOException("An existing disk cache is required: " + cache);
        }
        output = output.toAbsolutePath();
        if (Files.exists(output, LinkOption.NOFOLLOW_LINKS)) {
            throw new IOException("Output already exists; choose a new --output directory: " + output);
        }
        Files.createDirectories(output.getParent());
        Path staging = Files.createTempDirectory(output.getParent(), ".npc-image-dump-");
        try {
            Map<String, Object> manifest = new LinkedHashMap<>();
            manifest.put("schemaVersion", 1);
            manifest.put("generatedAt", Instant.now().toString());
            // Keep this coordinate in sync with the pinned dependency in build.gradle.kts.
            manifest.put("cacheDecoder", "net.runelite:cache:1.13.1");
            manifest.put("cachePath", cache.toAbsolutePath().toString());
            manifest.put("size", SIZE);
            manifest.put("pitch", PITCH);
            manifest.put("yaw", YAW);
            int rendered = 0;
            List<Integer> failed = new ArrayList<>();
            try (Store store = new Store(cache.toFile())) {
                store.load();
                Index models = store.getIndex(IndexType.MODELS);
                if (models == null) throw new IOException("Cache has no model index");
                manifest.put("modelsIndexRevision", models.getRevision());
                NpcManager npcs = new NpcManager(store);
                npcs.load();
                SpriteManager sprites = new SpriteManager(store);
                sprites.load();
                TextureManager textures = new TextureManager(store);
                textures.load();
                Poses poses = new Poses(store);
                ModelProvider modelProvider = modelId -> {
                    Archive archive = models.getArchive(modelId);
                    if (archive == null) return null;
                    byte[] data = archive.decompress(store.getStorage().loadArchive(archive));
                    return new ModelLoader().load(modelId, data);
                };
                List<NpcDefinition> sorted = new ArrayList<>(npcs.getNpcs());
                sorted.sort(Comparator.comparingInt(npc -> npc.id));
                for (NpcDefinition npc : sorted) {
                    if (only != null && !only.contains(npc.id)) continue;
                    if (npc.name == null || npc.name.equalsIgnoreCase("null")) continue;
                    try {
                        BufferedImage image = NpcSpriteFactory.render(npc, modelProvider, sprites, textures,
                                poses.firstFrame(npc.standingAnimation), SIZE, PITCH, YAW);
                        if (image == null) continue;
                        ImageIO.write(image, "PNG", staging.resolve(npc.id + ".png").toFile());
                        ++rendered;
                    } catch (Exception e) {
                        failed.add(npc.id);
                        Throwable cause = e.getCause() != null ? e.getCause() : e;
                        System.err.printf("NPC %d (%s): %s%n", npc.id, npc.name, cause);
                    }
                }
            }
            manifest.put("imageCount", rendered);
            manifest.put("failedNpcIds", failed);
            try (Writer writer = Files.newBufferedWriter(staging.resolve("manifest.json"))) {
                new GsonBuilder().setPrettyPrinting().create().toJson(manifest, writer);
            }
            // Publish only a complete export; never replace an existing one.
            Files.move(staging, output);
            System.out.printf("Rendered %d NPC images to %s (%d failed)%n", rendered, output, failed.size());
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

    /** Loads animation frames from the cache, remembering their archives and skeletons. */
    private static final class Poses {
        private final Store store;
        private final Map<Integer, SequenceDefinition> sequences = new HashMap<>();
        private final Map<Integer, ArchiveFiles> frameArchives = new HashMap<>();
        private final Map<Integer, FramemapDefinition> skeletons = new HashMap<>();

        Poses(Store store) throws IOException {
            this.store = store;
            Archive archive = store.getIndex(IndexType.CONFIGS).getArchive(ConfigType.SEQUENCE.getId());
            byte[] data = store.getStorage().loadArchive(archive);
            SequenceLoader loader = new SequenceLoader().configureForRevision(archive.getRevision());
            for (FSFile file : archive.getFiles(data).getFiles()) {
                sequences.put(file.getFileId(), loader.load(file.getFileId(), file.getContents()));
            }
        }

        /** The animation's first frame; nothing for no animation, a skeletal one, or a frame the cache lacks. */
        FrameDefinition firstFrame(int animation) throws IOException {
            SequenceDefinition sequence = sequences.get(animation);
            if (sequence == null || sequence.animMayaID >= 0 || sequence.frameIDs == null || sequence.frameIDs.length == 0) {
                return null;
            }
            int frameId = sequence.frameIDs[0];
            ArchiveFiles frames = frameArchives.get(frameId >>> 16);
            if (frames == null) {
                Archive archive = store.getIndex(IndexType.ANIMATIONS).getArchive(frameId >>> 16);
                if (archive == null) return null;
                frames = archive.getFiles(store.getStorage().loadArchive(archive));
                frameArchives.put(frameId >>> 16, frames);
            }
            FSFile frame = frames.findFile(frameId & 0xFFFF);
            if (frame == null) return null;
            byte[] contents = frame.getContents();
            // A frame starts with the id of its skeleton.
            int skeletonId = (contents[0] & 0xFF) << 8 | contents[1] & 0xFF;
            FramemapDefinition skeleton = skeletons.get(skeletonId);
            if (skeleton == null) {
                Archive archive = store.getIndex(IndexType.SKELETONS).getArchive(skeletonId);
                if (archive == null) return null;
                skeleton = new FramemapLoader().load(skeletonId, archive.decompress(store.getStorage().loadArchive(archive)));
                skeletons.put(skeletonId, skeleton);
            }
            return new FrameLoader().load(skeleton, frameId, contents);
        }
    }
}
