package osrs.dev.dumper;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.DisplayName;
import osrs.dev.mapping.CacheEfficientCoordIndexer;

import static org.junit.jupiter.api.Assertions.*;

@DisplayName("CacheEfficientCoordIndexer Tests")
class CacheEfficientCoordIndexerTest {

    @Test
    @DisplayName("Addresses for same coordinate should be contiguous")
    void testAddressesAreContiguous() {
        CacheEfficientCoordIndexer indexer = new CacheEfficientCoordIndexer(
                32, false, 4, 0, 4, 0, 2, 0, 4);

        int x = 15, y = 5, plane = 3;

        int idx0 = indexer.packToBitmapIndex(x, y, plane, 0);
        int idx1 = indexer.packToBitmapIndex(x, y, plane, 1);
        int idx2 = indexer.packToBitmapIndex(x, y, plane, 2);
        int idx3 = indexer.packToBitmapIndex(x, y, plane, 3);

        // All indices should be adjacent
        assertEquals(idx0 + 1, idx1);
        assertEquals(idx1 + 1, idx2);
        assertEquals(idx2 + 1, idx3);
    }

    @Test
    @DisplayName("Index formula should be packedCoord * addresses + addressIndex")
    void testIndexFormula() {
        CacheEfficientCoordIndexer indexer = new CacheEfficientCoordIndexer(
                32, false, 4, 0, 4, 0, 2, 0, 4);

        // Coordinate (15, 5, 3) packs to: (3 << 8) | (5 << 4) | 15 = 863
        int expectedPackedCoord = 863;

        int idx0 = indexer.packToBitmapIndex(15, 5, 3, 0);
        int idx1 = indexer.packToBitmapIndex(15, 5, 3, 1);
        int idx2 = indexer.packToBitmapIndex(15, 5, 3, 2);
        int idx3 = indexer.packToBitmapIndex(15, 5, 3, 3);

        assertEquals(expectedPackedCoord * 4 + 0, idx0);
        assertEquals(expectedPackedCoord * 4 + 1, idx1);
        assertEquals(expectedPackedCoord * 4 + 2, idx2);
        assertEquals(expectedPackedCoord * 4 + 3, idx3);
    }

    @Test
    @DisplayName("Adjacent coordinates should have addresses in separate contiguous blocks")
    void testAdjacentCoordinatesLayout() {
        CacheEfficientCoordIndexer indexer = new CacheEfficientCoordIndexer(
                32, false, 4, 0, 4, 0, 2, 0, 4);

        // Coordinate (0, 0, 0) packs to 0
        // Coordinate (1, 0, 0) packs to 1

        int coord0_addr0 = indexer.packToBitmapIndex(0, 0, 0, 0);
        int coord0_addr3 = indexer.packToBitmapIndex(0, 0, 0, 3);
        int coord1_addr0 = indexer.packToBitmapIndex(1, 0, 0, 0);

        // coord0 addresses: 0, 1, 2, 3
        // coord1 addresses: 4, 5, 6, 7
        assertEquals(0, coord0_addr0);
        assertEquals(3, coord0_addr3);
        assertEquals(4, coord1_addr0);
    }

    @Test
    @DisplayName("Constructor should calculate correct masks and shifts")
    void testConstructorCalculatesMasksAndShifts() {
        CacheEfficientCoordIndexer indexer = new CacheEfficientCoordIndexer(
                32, false, 12, 0, 14, 0, 2, 0, 4);

        assertEquals(4095, indexer.getXMask());      // (1 << 12) - 1
        assertEquals(0, indexer.getXShift());
        assertEquals(16383, indexer.getYMask());     // (1 << 14) - 1
        assertEquals(12, indexer.getYShift());       // xBits
        assertEquals(3, indexer.getPlaneMask());     // (1 << 2) - 1
        assertEquals(26, indexer.getPlaneShift());   // xBits + yBits
    }

    @Test
    @DisplayName("getMaxAddressIndex should return addressesPerCoordinate - 1")
    void testGetMaxAddressIndex() {
        CacheEfficientCoordIndexer indexer4 = new CacheEfficientCoordIndexer(
                32, false, 12, 0, 14, 0, 2, 0, 4);
        assertEquals(3, indexer4.getMaxAddressIndex());

        CacheEfficientCoordIndexer indexer8 = new CacheEfficientCoordIndexer(
                32, false, 12, 0, 14, 0, 2, 0, 8);
        assertEquals(7, indexer8.getMaxAddressIndex());
    }

    @Test
    @DisplayName("Constructor should throw when addressesPerCoordinate is less than 1")
    void testConstructorThrowsWhenAddressesInvalid() {
        IllegalArgumentException exception = assertThrows(IllegalArgumentException.class, () ->
                new CacheEfficientCoordIndexer(32, false, 12, 0, 14, 0, 2, 0, 0)
        );
        assertTrue(exception.getMessage().contains("addressesPerCoordinate"));
    }

    @Test
    @DisplayName("Constructor should throw when addressesPerCoordinate exceeds capacity")
    void testConstructorThrowsWhenAddressesExceedCapacity() {
        // With 28 coord bits and 32-bit capacity, max addresses = 2^4 = 16
        // Trying 17 should fail
        IllegalArgumentException exception = assertThrows(IllegalArgumentException.class, () ->
                new CacheEfficientCoordIndexer(32, false, 12, 0, 14, 0, 2, 0, 17)
        );
        assertTrue(exception.getMessage().contains("exceeds maximum allowed"));
        assertTrue(exception.getMessage().contains("16")); // max allowed
    }

    @Test
    @DisplayName("Constructor should throw when coordinate bits exceed capacity")
    void testConstructorThrowsWhenCoordBitsExceedCapacity() {
        IllegalArgumentException exception = assertThrows(IllegalArgumentException.class, () ->
                new CacheEfficientCoordIndexer(32, false, 15, 0, 15, 0, 4, 0, 1)
        );
        assertTrue(exception.getMessage().contains("exceeds maximum bit capacity"));
    }

    @Test
    @DisplayName("Constructor should set correct min/max coordinate bounds")
    void testConstructorSetsCoordinateBounds() {
        CacheEfficientCoordIndexer indexer = new CacheEfficientCoordIndexer(
                32, false, 12, 960, 14, 1000, 2, 0, 4);

        assertEquals(960, indexer.getMinX());
        assertEquals(960 + 4095, indexer.getMaxX());
        assertEquals(1000, indexer.getMinY());
        assertEquals(1000 + 16383, indexer.getMaxY());
        assertEquals(0, indexer.getMinPlane());
        assertEquals(3, indexer.getMaxPlane());
    }

    @Test
    @DisplayName("packToBitmapIndex with validation enabled should validate X coordinate")
    void testPackToBitmapIndexValidatesX() {
        CacheEfficientCoordIndexer indexer = new CacheEfficientCoordIndexer(
                32, true, 12, 960, 14, 0, 2, 0, 4);

        assertDoesNotThrow(() -> indexer.packToBitmapIndex(960, 0, 0, 0));

        IllegalArgumentException exception1 = assertThrows(IllegalArgumentException.class, () ->
                indexer.packToBitmapIndex(959, 0, 0, 0)
        );
        assertTrue(exception1.getMessage().contains("X coordinate"));

        IllegalArgumentException exception2 = assertThrows(IllegalArgumentException.class, () ->
                indexer.packToBitmapIndex(960 + 4096, 0, 0, 0)
        );
        assertTrue(exception2.getMessage().contains("X coordinate"));
    }

    @Test
    @DisplayName("packToBitmapIndex with validation enabled should validate Y coordinate")
    void testPackToBitmapIndexValidatesY() {
        CacheEfficientCoordIndexer indexer = new CacheEfficientCoordIndexer(
                32, true, 12, 0, 14, 100, 2, 0, 4);

        assertDoesNotThrow(() -> indexer.packToBitmapIndex(0, 100, 0, 0));

        IllegalArgumentException exception1 = assertThrows(IllegalArgumentException.class, () ->
                indexer.packToBitmapIndex(0, 99, 0, 0)
        );
        assertTrue(exception1.getMessage().contains("Y coordinate"));

        IllegalArgumentException exception2 = assertThrows(IllegalArgumentException.class, () ->
                indexer.packToBitmapIndex(0, 100 + 16384, 0, 0)
        );
        assertTrue(exception2.getMessage().contains("Y coordinate"));
    }

    @Test
    @DisplayName("packToBitmapIndex with validation enabled should validate plane coordinate")
    void testPackToBitmapIndexValidatesPlane() {
        CacheEfficientCoordIndexer indexer = new CacheEfficientCoordIndexer(
                32, true, 12, 0, 14, 0, 2, 0, 4);

        assertDoesNotThrow(() -> indexer.packToBitmapIndex(0, 0, 0, 0));
        assertDoesNotThrow(() -> indexer.packToBitmapIndex(0, 0, 3, 0));

        IllegalArgumentException exception1 = assertThrows(IllegalArgumentException.class, () ->
                indexer.packToBitmapIndex(0, 0, -1, 0)
        );
        assertTrue(exception1.getMessage().contains("Plane coordinate"));

        IllegalArgumentException exception2 = assertThrows(IllegalArgumentException.class, () ->
                indexer.packToBitmapIndex(0, 0, 4, 0)
        );
        assertTrue(exception2.getMessage().contains("Plane coordinate"));
    }

    @Test
    @DisplayName("packToBitmapIndex with validation enabled should validate address index")
    void testPackToBitmapIndexValidatesAddressIndex() {
        CacheEfficientCoordIndexer indexer = new CacheEfficientCoordIndexer(
                32, true, 12, 0, 14, 0, 2, 0, 4);

        assertDoesNotThrow(() -> indexer.packToBitmapIndex(0, 0, 0, 0));
        assertDoesNotThrow(() -> indexer.packToBitmapIndex(0, 0, 0, 3));

        IllegalArgumentException exception1 = assertThrows(IllegalArgumentException.class, () ->
                indexer.packToBitmapIndex(0, 0, 0, -1)
        );
        assertTrue(exception1.getMessage().contains("Address index"));

        IllegalArgumentException exception2 = assertThrows(IllegalArgumentException.class, () ->
                indexer.packToBitmapIndex(0, 0, 0, 4)
        );
        assertTrue(exception2.getMessage().contains("Address index"));
    }

    @Test
    @DisplayName("packToBitmapIndex without validation should not throw")
    void testPackToBitmapIndexWithoutValidation() {
        CacheEfficientCoordIndexer indexer = new CacheEfficientCoordIndexer(
                32, false, 12, 0, 14, 0, 2, 0, 4);

        assertDoesNotThrow(() -> indexer.packToBitmapIndex(-1, -1, -1, -1));
        assertDoesNotThrow(() -> indexer.packToBitmapIndex(10000, 10000, 10, 10));
    }

    @Test
    @DisplayName("withValidationEnabled should return same instance if already enabled")
    void testWithValidationEnabledReturnsSameInstanceIfAlreadyEnabled() {
        CacheEfficientCoordIndexer indexer = new CacheEfficientCoordIndexer(
                32, true, 12, 0, 14, 0, 2, 0, 4);
        CacheEfficientCoordIndexer result = indexer.withValidationEnabled();

        assertSame(indexer, result);
    }

    @Test
    @DisplayName("withValidationEnabled should create new instance if disabled")
    void testWithValidationEnabledCreatesNewInstanceIfDisabled() {
        CacheEfficientCoordIndexer indexer = new CacheEfficientCoordIndexer(
                32, false, 12, 0, 14, 0, 2, 0, 4);
        CacheEfficientCoordIndexer result = indexer.withValidationEnabled();

        assertNotSame(indexer, result);
        assertTrue(result.isAdditionalValidationEnabled());
        assertFalse(indexer.isAdditionalValidationEnabled());
    }

    @Test
    @DisplayName("withValidationDisabled should return same instance if already disabled")
    void testWithValidationDisabledReturnsSameInstanceIfAlreadyDisabled() {
        CacheEfficientCoordIndexer indexer = new CacheEfficientCoordIndexer(
                32, false, 12, 0, 14, 0, 2, 0, 4);
        CacheEfficientCoordIndexer result = indexer.withValidationDisabled();

        assertSame(indexer, result);
    }

    @Test
    @DisplayName("withValidationDisabled should create new instance if enabled")
    void testWithValidationDisabledCreatesNewInstanceIfEnabled() {
        CacheEfficientCoordIndexer indexer = new CacheEfficientCoordIndexer(
                32, true, 12, 0, 14, 0, 2, 0, 4);
        CacheEfficientCoordIndexer result = indexer.withValidationDisabled();

        assertNotSame(indexer, result);
        assertFalse(result.isAdditionalValidationEnabled());
        assertTrue(indexer.isAdditionalValidationEnabled());
    }

    @Test
    @DisplayName("Builder should create correct indexer")
    void testBuilderCreatesCorrectIndexer() {
        CacheEfficientCoordIndexer indexer = CacheEfficientCoordIndexer.builder()
                .maxBitCapacity(32)
                .xBits(12)
                .xBase(960)
                .yBits(14)
                .yBase(1000)
                .planeBits(2)
                .addressesPerCoordinate(4)
                .build();

        assertEquals(960, indexer.getMinX());
        assertEquals(1000, indexer.getMinY());
        assertEquals(4, indexer.getAddressesPerCoordinate());
        assertEquals(3, indexer.getMaxAddressIndex());
    }

    @Test
    @DisplayName("Builder useAdditionalValidation should enable validation")
    void testBuilderEnablesValidation() {
        CacheEfficientCoordIndexer indexer = CacheEfficientCoordIndexer.builder()
                .maxBitCapacity(32)
                .xBits(12)
                .yBits(14)
                .planeBits(2)
                .addressesPerCoordinate(4)
                .useAdditionalValidation()
                .build();

        assertTrue(indexer.isAdditionalValidationEnabled());
    }

    @Test
    @DisplayName("ROARINGBITMAP_4ADDR_COORD_INDEXER should have contiguous indices")
    void testPredefinedIndexerHasContiguousIndices() {
        CacheEfficientCoordIndexer indexer = CacheEfficientCoordIndexer.SEQUENTIAL_COORD_INDEXER_8_ADDRESSES;

        assertEquals(8, indexer.getAddressesPerCoordinate());
        assertEquals(7, indexer.getMaxAddressIndex());

        // Test with a real coordinate
        int x = 1000, y = 3000, plane = 1;
        int idx0 = indexer.packToBitmapIndex(x, y, plane, 0);
        int idx1 = indexer.packToBitmapIndex(x, y, plane, 1);
        int idx2 = indexer.packToBitmapIndex(x, y, plane, 2);
        int idx3 = indexer.packToBitmapIndex(x, y, plane, 3);

        assertEquals(idx0 + 1, idx1);
        assertEquals(idx1 + 1, idx2);
        assertEquals(idx2 + 1, idx3);
    }

    @Test
    @DisplayName("Coordinate with base offset should pack correctly")
    void testCoordinateWithBaseOffset() {
        CacheEfficientCoordIndexer indexer = new CacheEfficientCoordIndexer(
                32, false, 8, 100, 8, 200, 2, 0, 4);

        // Pack coordinate at base: packed = 0
        int result = indexer.packToBitmapIndex(100, 200, 0, 0);
        assertEquals(0, result); // 0 * 4 + 0 = 0

        // Pack coordinate one unit above base: packed = (0 << 8) | 1 | (1 << 8) = 257
        int result2 = indexer.packToBitmapIndex(101, 201, 0, 0);
        int expectedPacked = (1 << 8) | 1; // Y offset 1 at shift 8, X offset 1 at shift 0 = 257
        assertEquals(expectedPacked * 4, result2);
    }

    @Test
    @DisplayName("getMaxConfigurableAddresses should return correct value")
    void testGetMaxConfigurableAddresses() {
        // 28 coord bits, 32-bit capacity -> 2^4 = 16 max addresses
        CacheEfficientCoordIndexer indexer = new CacheEfficientCoordIndexer(
                32, false, 12, 0, 14, 0, 2, 0, 4);
        assertEquals(16, indexer.getMaxConfigurableAddresses());

        // 28 coord bits, 31-bit capacity -> 2^3 = 8 max addresses
        CacheEfficientCoordIndexer indexer31 = new CacheEfficientCoordIndexer(
                31, false, 12, 0, 14, 0, 2, 0, 4);
        assertEquals(8, indexer31.getMaxConfigurableAddresses());
    }

    @Test
    @DisplayName("Maximum allowed addresses should be configurable up to capacity limit")
    void testMaxAddressesAtCapacityLimit() {
        // With 28 coord bits and 32-bit capacity, max is 16
        assertDoesNotThrow(() -> new CacheEfficientCoordIndexer(
                32, false, 12, 0, 14, 0, 2, 0, 16));

        // With 28 coord bits and 31-bit capacity, max is 8
        assertDoesNotThrow(() -> new CacheEfficientCoordIndexer(
                31, false, 12, 0, 14, 0, 2, 0, 8));

        // Exceeding should throw
        assertThrows(IllegalArgumentException.class, () ->
                new CacheEfficientCoordIndexer(31, false, 12, 0, 14, 0, 2, 0, 9));
    }
}
