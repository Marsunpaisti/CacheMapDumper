package osrs.dev.mapping.tiletypemap;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

class TileTypeTest {

    @Test
    void neverTraversableSpriteIsNotSailable() {
        assertEquals(208, TileType.NEVER_TRAVERSABLE_SPRITE_ID);
        assertFalse(TileType.SPRITE_ID_TO_TILE_TYPE.containsKey(TileType.NEVER_TRAVERSABLE_SPRITE_ID));
    }
}
