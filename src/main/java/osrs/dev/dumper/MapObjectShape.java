package osrs.dev.dumper;

import lombok.Getter;

/**
 * Map Object Shapes, names found in the iOS client on RuneScape 3.
 * Do not re-order the constants, as for efficiency reasons, enum hashsets are to be used with this enum.
 * Types are only there to quickly identify which is which.
 * @author Kris | 22/04/2022
 */
@Getter
public enum MapObjectShape {
    WallStraight(0, '1', MapObjectLayer.Wall),
    WallDiagonalCorner(1, '2', MapObjectLayer.Wall),
    WallL(2, '3', MapObjectLayer.Wall),
    WallSquareCorner(3, '4', MapObjectLayer.Wall),
    WallDecorStraightNoOffset(4, 'Q', MapObjectLayer.Decorative),
    WallDecorStraightOffset(5, 'W', MapObjectLayer.Decorative),
    WallDecorDiagonalOffset(6, 'E', MapObjectLayer.Decorative),
    WallDecorDiagonalNoOffset(7, 'R', MapObjectLayer.Decorative),
    WallDecorDiagonalBoth(8, 'T', MapObjectLayer.Decorative),
    WallDiagonal(9, '5', MapObjectLayer.Abstract),
    CentrepieceStraight(10, '8', MapObjectLayer.Abstract),
    CentrepieceDiagonal(11, '9', MapObjectLayer.Abstract),
    RoofStraight(12, 'A', MapObjectLayer.Abstract),
    RoofDiagonalWithRoofEdge(13, 'S', MapObjectLayer.Abstract),
    RoofDiagonal(14, 'D', MapObjectLayer.Abstract),
    RoofLConcave(15, 'F', MapObjectLayer.Abstract),
    RoofLConvex(16, 'G', MapObjectLayer.Abstract),
    RoofFlat(17, 'H', MapObjectLayer.Abstract),
    RoofEdgeStraight(18, 'Z', MapObjectLayer.Abstract),
    RoofEdgeDiagonalCorner(19, 'X', MapObjectLayer.Abstract),
    RoofEdgeL(20, 'C', MapObjectLayer.Abstract),
    RoofEdgeSquareCorner(21, 'V', MapObjectLayer.Abstract),
    GroundDecor(22, '0', MapObjectLayer.Ground),
    ;

    private static final MapObjectShape[] VALUES = values();

    static {
        for (int i = 0; i < VALUES.length; i++) {
            if (VALUES[i].type != i) {
                throw new IllegalStateException("MapObjectShape enum has been pushed out of order!");
            }
        }
    }

    private final int type;
    private final char symbol;
    private final MapObjectLayer slot;

    MapObjectShape(int type, char symbol, MapObjectLayer slot) {
        this.type = type;
        this.symbol = symbol;
        this.slot = slot;
    }

    public static MapObjectShape get(int type) {
        return VALUES[type];
    }

    public static MapObjectShape of(int id) {
        return get(id);
    }
}
