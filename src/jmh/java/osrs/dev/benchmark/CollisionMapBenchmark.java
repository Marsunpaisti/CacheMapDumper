package osrs.dev.benchmark;

import org.openjdk.jmh.annotations.*;
import org.openjdk.jmh.infra.Blackhole;
import osrs.dev.mapping.collisionmap.CollisionMapFactory;
import osrs.dev.mapping.collisionmap.ICollisionMap;

import java.util.Random;
import java.util.concurrent.TimeUnit;

@State(Scope.Thread)
@BenchmarkMode(Mode.Throughput)
@OutputTimeUnit(TimeUnit.SECONDS)
@Warmup(iterations = 3, time = 1)
@Measurement(iterations = 5, time = 1)
@Fork(2)
public class CollisionMapBenchmark {

    private static final String BASE_PATH = System.getProperty("user.home") + "/VitaX/";

    @Param({"roaring", "sparse", "wordset"})
    private String format;

    private ICollisionMap map;
    private Random random;

    // Coordinate bounds
    private static final int MIN_X = 480;
    private static final int MAX_X = 4575;
    private static final int MIN_Y = 0;
    private static final int MAX_Y = 16383;
    private static final int MAX_PLANE = 3;

    // Sequential iteration state
    private int seqX;
    private int seqY;
    private int seqPlane;

    @Setup(Level.Trial)
    public void setup() throws Exception {
        String path = BASE_PATH + "map_" + format + ".dat.gz";
        map = CollisionMapFactory.load(path);
        random = new Random(42);
        seqX = MIN_X;
        seqY = MIN_Y;
        seqPlane = 0;
    }

    @Benchmark
    public void randomAccess(Blackhole bh) {
        int x = MIN_X + random.nextInt(MAX_X - MIN_X + 1);
        int y = MIN_Y + random.nextInt(MAX_Y - MIN_Y + 1);
        int plane = random.nextInt(MAX_PLANE + 1);
        bh.consume(map.all(x, y, plane));
    }

    @Benchmark
    public void randomAccessIndividual(Blackhole bh) {
        int x = MIN_X + random.nextInt(MAX_X - MIN_X + 1);
        int y = MIN_Y + random.nextInt(MAX_Y - MIN_Y + 1);
        int plane = random.nextInt(MAX_PLANE + 1);
        bh.consume(map.pathableNorth(x, y, plane));
        bh.consume(map.pathableEast(x, y, plane));
        bh.consume(map.pathableSouth(x, y, plane));
        bh.consume(map.pathableWest(x, y, plane));
    }

    @Benchmark
    public void sequentialAccess(Blackhole bh) {
        bh.consume(map.all(seqX, seqY, seqPlane));
        advanceSequential();
    }

    @Benchmark
    public void sequentialAccessIndividual(Blackhole bh) {
        bh.consume(map.pathableNorth(seqX, seqY, seqPlane));
        bh.consume(map.pathableEast(seqX, seqY, seqPlane));
        bh.consume(map.pathableSouth(seqX, seqY, seqPlane));
        bh.consume(map.pathableWest(seqX, seqY, seqPlane));
        advanceSequential();
    }

    private void advanceSequential() {
        seqPlane++;
        if (seqPlane > MAX_PLANE) {
            seqPlane = 0;
            seqY++;
            if (seqY > MAX_Y) {
                seqY = MIN_Y;
                seqX++;
                if (seqX > MAX_X) {
                    seqX = MIN_X;
                }
            }
        }
    }
}
