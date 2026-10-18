package osrs.dev.dumper;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import net.runelite.cache.ConfigType;
import net.runelite.cache.IndexType;
import net.runelite.cache.fs.Archive;
import net.runelite.cache.fs.ArchiveFiles;
import net.runelite.cache.fs.Container;
import net.runelite.cache.fs.FSFile;
import net.runelite.cache.fs.Store;
import net.runelite.cache.index.FileData;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class ItemDefinitionDumperTest {
    @TempDir
    Path temporary;

    @Test
    void exportsEveryDefinitionBeforeAndAfterLinkingAndPreservesAllStats() throws Exception {
        Path cache = createCache();
        Path stats = temporary.resolve("stats.json");
        String source = "{\"10\":{\"weight\":1.25,\"equipable\":true,\"equipment\":{\"slot\":3,\"aspeed\":4,\"is2h\":true},"
                + "\"futureField\":{\"value\":7}},\"999\":{\"ge_limit\":100}}";
        Files.writeString(stats, source);
        Path output = temporary.resolve("export");
        ItemDefinitionDumper.main(new String[]{"--cache", cache.toString(), "--item-stats", stats.toString(),
                "--output", output.toString()});

        JsonObject dump = json(output.resolve("item-definitions.json"));
        JsonObject raw = dump.getAsJsonObject("raw");
        JsonObject linked = dump.getAsJsonObject("linked");
        assertEquals(1, dump.get("schemaVersion").getAsInt());
        assertEquals(List.of("10", "20", "21", "22", "23"), List.copyOf(raw.keySet()));
        assertEquals(raw.keySet(), linked.keySet());
        assertEquals(0, raw.getAsJsonObject("21").get("stackable").getAsInt());
        assertEquals(1, linked.getAsJsonObject("21").get("stackable").getAsInt());
        for (String id : List.of("21", "22", "23")) {
            assertEquals("Test sword", linked.getAsJsonObject(id).get("name").getAsString());
            assertEquals(123, linked.getAsJsonObject(id).get("inventoryModel").getAsInt());
        }
        JsonObject base = raw.getAsJsonObject("10");
        assertEquals(42, base.getAsJsonObject("params").get("7").getAsInt());
        assertEquals("retained", base.getAsJsonObject("params").get("8").getAsString());
        assertEquals("Wield", base.getAsJsonArray("interfaceOptions").get(1).getAsString());
        assertTrue(base.get("colorFind").isJsonNull());
        assertEquals(source, Files.readString(output.resolve("item-stats.json")));

        JsonObject manifest = json(output.resolve("manifest.json"));
        assertEquals(5, manifest.get("itemDefinitionCount").getAsInt());
        assertEquals(2, manifest.get("itemStatsCount").getAsInt());
        assertEquals(1, manifest.get("equipmentStatsCount").getAsInt());
        assertEquals(1, manifest.get("definitionsWithStats").getAsInt());
        assertEquals(1, manifest.get("statsWithoutDefinition").getAsInt());
        for (String name : List.of("item-definitions.json", "item-stats.json")) {
            byte[] bytes = Files.readAllBytes(output.resolve(name));
            JsonObject fingerprint = manifest.getAsJsonObject("files").getAsJsonObject(name);
            assertEquals(bytes.length, fingerprint.get("bytes").getAsLong());
            StringBuilder hash = new StringBuilder();
            for (byte b : MessageDigest.getInstance("SHA-256").digest(bytes)) hash.append(String.format("%02x", b & 255));
            assertEquals(hash.toString(), fingerprint.get("sha256").getAsString());
        }
        Path repeat = temporary.resolve("repeat");
        ItemDefinitionDumper.export(cache, stats, repeat);
        assertArrayEquals(Files.readAllBytes(output.resolve("item-definitions.json")),
                Files.readAllBytes(repeat.resolve("item-definitions.json")));
    }

    @Test
    void refusesToOverwriteAndDoesNotPublishIncompleteExports() throws Exception {
        Path cache = createCache();
        Path output = Files.createDirectory(temporary.resolve("existing"));
        Path marker = output.resolve("keep.txt");
        Files.writeString(marker, "keep");
        assertThrows(IOException.class, () -> ItemDefinitionDumper.export(cache, null, output));
        assertEquals("keep", Files.readString(marker));

        Path stats = temporary.resolve("invalid.json");
        Files.writeString(stats, "[]");
        Path failed = temporary.resolve("failed");
        assertThrows(IOException.class, () -> ItemDefinitionDumper.export(cache, stats, failed));
        assertFalse(Files.exists(failed));
        try (var paths = Files.list(temporary)) {
            assertFalse(paths.anyMatch(path -> path.getFileName().toString().startsWith(".item-dump-")));
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"{}", "[]", "null", "<html>error</html>", "{\"-1\":{}}", "{\"01\":{}}",
            "{\"1\":{}} {}", "{\"1\":{},\"1\":{}}", "{\"1\":null}", "{\"1\":{\"equipment\":[]}}"})
    void rejectsInvalidStats(String input) throws Exception {
        Path stats = temporary.resolve("stats.json");
        Files.writeString(stats, input);
        assertThrows(IOException.class, () -> ItemDefinitionDumper.readStats(stats));
    }

    @Test
    void rejectsIncompleteAndUnknownArguments() {
        assertThrows(IllegalArgumentException.class, () -> ItemDefinitionDumper.main(new String[]{"--cache"}));
        assertThrows(IllegalArgumentException.class, () -> ItemDefinitionDumper.main(new String[]{"--output", "--cache"}));
        assertThrows(IllegalArgumentException.class, () -> ItemDefinitionDumper.main(new String[]{"--unknown", "value"}));
    }

    private static JsonObject json(Path path) throws IOException {
        return JsonParser.parseString(Files.readString(path)).getAsJsonObject();
    }

    private Path createCache() throws Exception {
        Path cache = Files.createDirectory(temporary.resolve("cache"));
        try (Store store = new Store(cache.toFile())) {
            Archive archive = store.addIndex(IndexType.CONFIGS.getNumber()).addArchive(ConfigType.ITEM.getId());
            ArchiveFiles files = new ArchiveFiles();
            int[] ids = {10, 20, 21, 22, 23};
            FileData[] metadata = new FileData[ids.length];
            for (int i = 0; i < ids.length; i++) {
                int id = ids[i];
                metadata[i] = new FileData();
                metadata[i].setId(id);
                ByteArrayOutputStream bytes = new ByteArrayOutputStream();
                DataOutputStream data = new DataOutputStream(bytes);
                if (id == 10) {
                    data.writeByte(2); data.writeBytes("Test sword"); data.writeByte(0);
                    data.writeByte(36); data.writeBytes("Wield"); data.writeByte(0);
                    data.writeByte(249); data.writeByte(2);
                    data.writeByte(0); data.write(new byte[]{0, 0, 7}); data.writeInt(42);
                    data.writeByte(1); data.write(new byte[]{0, 0, 8}); data.writeBytes("retained"); data.writeByte(0);
                } else if (id == 20) {
                    data.writeByte(1); data.writeShort(123);
                } else {
                    // Note, bought and placeholder pairs exercise the decoder's three linking rules.
                    data.writeByte(id == 21 ? 97 : id == 22 ? 139 : 148); data.writeShort(10);
                    data.writeByte(id == 21 ? 98 : id == 22 ? 140 : 149); data.writeShort(20);
                }
                data.writeByte(0);
                FSFile file = new FSFile(id);
                file.setContents(bytes.toByteArray());
                files.addFile(file);
            }
            archive.setFileData(metadata);
            Container container = new Container(0, -1);
            container.compress(files.saveContents(), null);
            archive.setCrc(container.crc);
            store.getStorage().saveArchive(archive, container.data);
            store.save();
        }
        return cache;
    }
}
