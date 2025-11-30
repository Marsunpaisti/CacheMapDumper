package osrs.dev.mapping.tiledatamap.sparse;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.io.*;

import static org.junit.jupiter.api.Assertions.*;

@DisplayName("SparseWordSet Tests")
class SparseWordSetTest {

    @Test
    @DisplayName("Constructor should accept valid bitsPerValue")
    void testConstructorValidBitsPerValue() {
        assertDoesNotThrow(() -> new SparseWordSet(1));
        assertDoesNotThrow(() -> new SparseWordSet(2));
        assertDoesNotThrow(() -> new SparseWordSet(4));
        assertDoesNotThrow(() -> new SparseWordSet(8));
        assertDoesNotThrow(() -> new SparseWordSet(16));
        assertDoesNotThrow(() -> new SparseWordSet(32));
        assertDoesNotThrow(() -> new SparseWordSet(64));
    }

    @Test
    @DisplayName("Constructor should reject invalid bitsPerValue")
    void testConstructorInvalidBitsPerValue() {
        assertThrows(IllegalArgumentException.class, () -> new SparseWordSet(0));
        assertThrows(IllegalArgumentException.class, () -> new SparseWordSet(3));
        assertThrows(IllegalArgumentException.class, () -> new SparseWordSet(5));
        assertThrows(IllegalArgumentException.class, () -> new SparseWordSet(7));
        assertThrows(IllegalArgumentException.class, () -> new SparseWordSet(65));
    }

    @Test
    @DisplayName("get should return 0 for unset indices")
    void testGetReturnsZeroForUnset() {
        SparseWordSet set = new SparseWordSet(8);

        assertEquals(0, set.get(0));
        assertEquals(0, set.get(100));
        assertEquals(0, set.get(1000000));
    }

    @Test
    @DisplayName("set and get should work correctly for 4-bit values")
    void testSetGet4Bit() {
        SparseWordSet set = new SparseWordSet(4);

        set.set(0, 0xF);
        set.set(1, 0x5);
        set.set(100, 0xA);

        assertEquals(0xF, set.get(0));
        assertEquals(0x5, set.get(1));
        assertEquals(0xA, set.get(100));
        assertEquals(0, set.get(2)); // Unset
    }

    @Test
    @DisplayName("set and get should work correctly for 8-bit values")
    void testSetGet8Bit() {
        SparseWordSet set = new SparseWordSet(8);

        set.set(0, 255);
        set.set(1, 128);
        set.set(1000, 42);

        assertEquals(255, set.get(0));
        assertEquals(128, set.get(1));
        assertEquals(42, set.get(1000));
    }

    @Test
    @DisplayName("set should mask values to bitsPerValue")
    void testSetMasksValues() {
        SparseWordSet set = new SparseWordSet(4);

        // Try to set 0xFF (8 bits), should only store 0xF (4 bits)
        set.set(0, 0xFF);
        assertEquals(0xF, set.get(0));

        // Try to set negative, should store lower bits
        set.set(1, -1);
        assertEquals(0xF, set.get(1));
    }

    @Test
    @DisplayName("set should overwrite existing values")
    void testSetOverwrites() {
        SparseWordSet set = new SparseWordSet(8);

        set.set(0, 100);
        assertEquals(100, set.get(0));

        set.set(0, 200);
        assertEquals(200, set.get(0));

        set.set(0, 0);
        assertEquals(0, set.get(0));
    }

    @Test
    @DisplayName("should handle sparse indices efficiently")
    void testSparseIndices() {
        SparseWordSet set = new SparseWordSet(8);

        // Set values at very sparse indices
        set.set(0, 1);
        set.set(100000, 2);
        set.set(10000000, 3);

        assertEquals(1, set.get(0));
        assertEquals(2, set.get(100000));
        assertEquals(3, set.get(10000000));

        // Intermediate indices should still be 0
        assertEquals(0, set.get(50000));
        assertEquals(0, set.get(5000000));
    }

    @Test
    @DisplayName("should pack multiple values per word correctly")
    void testMultipleValuesPerWord() {
        SparseWordSet set = new SparseWordSet(4);
        // 16 values per long

        // Set values in the same word
        for (int i = 0; i < 16; i++) {
            set.set(i, i);
        }

        // Verify each value
        for (int i = 0; i < 16; i++) {
            assertEquals(i, set.get(i));
        }
    }

    @Test
    @DisplayName("get should throw for negative index")
    void testGetThrowsForNegativeIndex() {
        SparseWordSet set = new SparseWordSet(8);
        assertThrows(IndexOutOfBoundsException.class, () -> set.get(-1));
    }

    @Test
    @DisplayName("set should throw for negative index")
    void testSetThrowsForNegativeIndex() {
        SparseWordSet set = new SparseWordSet(8);
        assertThrows(IndexOutOfBoundsException.class, () -> set.set(-1, 0));
    }

    @ParameterizedTest
    @ValueSource(ints = {1, 2, 4, 8, 16, 32})
    @DisplayName("serialization round-trip should preserve values")
    void testSerializationRoundTrip(int bitsPerValue) throws IOException {
        SparseWordSet original = new SparseWordSet(bitsPerValue);
        int maxValue = (1 << bitsPerValue) - 1;

        // Set some values
        original.set(0, maxValue);
        original.set(1, 1);
        original.set(1000, maxValue / 2);
        original.set(100000, 0);
        original.set(100001, maxValue);

        // Serialize
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        original.serialize(new DataOutputStream(baos));

        // Deserialize
        ByteArrayInputStream bais = new ByteArrayInputStream(baos.toByteArray());
        SparseWordSet restored = SparseWordSet.deserialize(new DataInputStream(bais));

        // Verify
        assertEquals(bitsPerValue, restored.getBitsPerValue());
        assertEquals(original.get(0), restored.get(0));
        assertEquals(original.get(1), restored.get(1));
        assertEquals(original.get(1000), restored.get(1000));
        assertEquals(original.get(100000), restored.get(100000));
        assertEquals(original.get(100001), restored.get(100001));
        assertEquals(0, restored.get(500)); // Unset index
    }

    @Test
    @DisplayName("getBitsPerValue should return configured value")
    void testGetBitsPerValue() {
        assertEquals(4, new SparseWordSet(4).getBitsPerValue());
        assertEquals(8, new SparseWordSet(8).getBitsPerValue());
        assertEquals(16, new SparseWordSet(16).getBitsPerValue());
    }

    @Test
    @DisplayName("estimateMemoryUsage should return reasonable value")
    void testEstimateMemoryUsage() {
        SparseWordSet set = new SparseWordSet(8);

        // Empty set should use minimal memory
        long emptyUsage = set.estimateMemoryUsage();
        assertTrue(emptyUsage < 1000, "Empty set should use minimal memory");

        // Set a value
        set.set(0, 1);
        long usage1 = set.estimateMemoryUsage();
        assertTrue(usage1 > emptyUsage, "Set with value should use more memory");

        // Set a value at a high index
        set.set(1000000, 1);
        long usage2 = set.estimateMemoryUsage();
        assertTrue(usage2 > usage1, "Set with sparse values should allocate more blocks");
    }

    @Test
    @DisplayName("getStatistics should return non-empty string")
    void testGetStatistics() {
        SparseWordSet set = new SparseWordSet(8);
        set.set(0, 1);
        set.set(1000000, 2);

        String stats = set.getStatistics();
        assertNotNull(stats);
        assertTrue(stats.contains("SparseWordSet"));
        assertTrue(stats.contains("bitsPerValue=8"));
    }

    @Test
    @DisplayName("1-bit values should work like a bitset")
    void testOneBitValues() {
        SparseWordSet set = new SparseWordSet(1);

        set.set(0, 1);
        set.set(1, 0);
        set.set(63, 1);
        set.set(64, 1);

        assertEquals(1, set.get(0));
        assertEquals(0, set.get(1));
        assertEquals(1, set.get(63));
        assertEquals(1, set.get(64));
        assertEquals(0, set.get(100));
    }

    @Test
    @DisplayName("32-bit values should store full int range")
    void testThirtyTwoBitValues() {
        SparseWordSet set = new SparseWordSet(32);

        set.set(0, Integer.MAX_VALUE);
        set.set(1, Integer.MIN_VALUE);
        set.set(2, 0x12345678);

        assertEquals(Integer.MAX_VALUE, set.get(0));
        // Note: we store as unsigned, so MIN_VALUE comes back as its unsigned representation
        assertEquals(Integer.MIN_VALUE, set.get(1));
        assertEquals(0x12345678, set.get(2));
    }

    @Test
    @DisplayName("ObjectOutputStream serialization should work")
    void testObjectOutputStreamSerialization() throws IOException, ClassNotFoundException {
        SparseWordSet original = new SparseWordSet(8);
        original.set(0, 100);
        original.set(1000, 200);

        // Serialize using ObjectOutputStream
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        try (ObjectOutputStream oos = new ObjectOutputStream(baos)) {
            oos.writeObject(original);
        }

        // Deserialize
        ByteArrayInputStream bais = new ByteArrayInputStream(baos.toByteArray());
        try (ObjectInputStream ois = new ObjectInputStream(bais)) {
            SparseWordSet restored = (SparseWordSet) ois.readObject();

            assertEquals(8, restored.getBitsPerValue());
            assertEquals(100, restored.get(0));
            assertEquals(200, restored.get(1000));
        }
    }
}
