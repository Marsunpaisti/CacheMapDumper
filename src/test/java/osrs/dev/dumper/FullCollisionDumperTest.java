package osrs.dev.dumper;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

class FullCollisionDumperTest {
    @TempDir
    Path temporary;

    @Test
    void loadsOpenRs2KeysWithoutReflectionAndAllowsUnencryptedArchives() throws Exception {
        Path keys = temporary.resolve("keys.json");
        Files.writeString(keys, "[{\"mapsquare\":12850,\"key\":[1,-2,3,4]}]");
        var provider = FullCollisionDumper.readKeys(keys);
        assertArrayEquals(new int[]{1, -2, 3, 4}, provider.getKey(12850));
        assertArrayEquals(new int[4], provider.getKey(0));
    }

    @Test
    void rejectsAmbiguousOrIncompleteKeyRecords() throws Exception {
        Path keys = temporary.resolve("keys.json");
        Files.writeString(keys, "[{\"mapsquare\":1,\"key\":[1,2,3]}]");
        assertThrows(IOException.class, () -> FullCollisionDumper.readKeys(keys));
        Files.writeString(keys, "[{\"mapsquare\":1,\"key\":[1,2,3,4]},"
                + "{\"mapsquare\":1,\"key\":[5,6,7,8]}]");
        assertThrows(IOException.class, () -> FullCollisionDumper.readKeys(keys));
    }

    @Test
    void missingInputsFailWithoutCreatingOutput() {
        Path output = temporary.resolve("output");
        assertThrows(IOException.class, () -> FullCollisionDumper.export(
                temporary.resolve("cache"), temporary.resolve("keys.json"), output));
        assertFalse(Files.exists(output));
    }

    @Test
    void existingOutputIsNeverOverwritten() throws Exception {
        Files.createFile(temporary.resolve("main_file_cache.dat2"));
        Path keys = Files.writeString(temporary.resolve("keys.json"), "[]");
        Path output = Files.createDirectory(temporary.resolve("output"));
        Path binary = Files.writeString(output.resolve("region_0.regc"), "existing dataset");
        assertThrows(IOException.class, () -> FullCollisionDumper.export(temporary, keys, output));
        assertEquals("existing dataset", Files.readString(binary));
    }

    @Test
    void rejectsUnknownOrIncompleteOptions() {
        assertThrows(IllegalArgumentException.class, () -> FullCollisionDumper.main(new String[]{"--fresh"}));
        assertThrows(IllegalArgumentException.class, () -> FullCollisionDumper.main(new String[]{"--cache"}));
    }
}
