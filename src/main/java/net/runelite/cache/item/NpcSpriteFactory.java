package net.runelite.cache.item;

import net.runelite.cache.definitions.FrameDefinition;
import net.runelite.cache.definitions.FramemapDefinition;
import net.runelite.cache.definitions.ModelDefinition;
import net.runelite.cache.definitions.NpcDefinition;
import net.runelite.cache.definitions.providers.ModelProvider;
import net.runelite.cache.definitions.providers.SpriteProvider;
import net.runelite.cache.definitions.providers.TextureProvider;
import net.runelite.cache.models.JagexColor;

import java.awt.image.BufferedImage;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;

/**
 * A still picture of an NPC: its models merged, recoloured, scaled and lit as the client builds an NPC, drawn by
 * RuneLite's item icon renderer. It sits in RuneLite's package to reach that renderer's package-private classes, and
 * lights the model with ItemSpriteFactory's own (private) lighting through reflection.
 */
public final class NpcSpriteFactory {
    private static final Method LIGHT;
    // The vertices of each animation label, which ModelLoader builds from the labels as it loads a model.
    private static final Field VERTEX_GROUPS;

    static {
        try {
            LIGHT = ItemSpriteFactory.class.getDeclaredMethod("light", ModelDefinition.class, int.class, int.class,
                    int.class, int.class, int.class);
            LIGHT.setAccessible(true);
            VERTEX_GROUPS = ModelDefinition.class.getDeclaredField("vertexGroups");
            VERTEX_GROUPS.setAccessible(true);
        } catch (NoSuchMethodException | NoSuchFieldException e) {
            throw new ExceptionInInitializerError(e);
        }
    }

    private NpcSpriteFactory() {}

    /**
     * The NPC in a size by size picture, in `pose` (a frame of its standing animation; the client never draws an
     * NPC's models unanimated, and some are folded up without one), seen from `pitch` above level and turned `yaw`
     * (both in the client's 2048 units to a circle), fitted to the picture. Nothing when the NPC has no models.
     */
    public static BufferedImage render(NpcDefinition npc, ModelProvider modelProvider, SpriteProvider spriteProvider,
                                       TextureProvider textureProvider, FrameDefinition pose, int size, int pitch,
                                       int yaw) throws Exception {
        if (npc.models == null || npc.models.length == 0) return null;
        List<ModelDefinition> parts = new ArrayList<>();
        for (int id : npc.models) {
            ModelDefinition part = modelProvider.provide(id);
            if (part != null) parts.add(part);
        }
        if (parts.isEmpty()) return null;
        ModelDefinition merged = merge(parts);
        if (npc.widthScale != 128 || npc.heightScale != 128) merged.resize(npc.widthScale, npc.heightScale, npc.widthScale);
        if (npc.recolorToFind != null) {
            for (int i = 0; i < npc.recolorToFind.length; ++i) merged.recolor(npc.recolorToFind[i], npc.recolorToReplace[i]);
        }
        if (npc.retextureToFind != null) {
            for (int i = 0; i < npc.retextureToFind.length; ++i) {
                merged.retexture(npc.retextureToFind[i], npc.retextureToReplace[i]);
            }
        }
        if (pose != null && VERTEX_GROUPS.get(merged) != null) {
            FramemapDefinition skeleton = pose.framemap;
            for (int i = 0; i < pose.translatorCount; ++i) {
                int group = pose.indexFrameIds[i];
                merged.animate(skeleton.types[group], skeleton.frameMaps[group], pose.translator_x[i],
                        pose.translator_y[i], pose.translator_z[i]);
            }
        }
        // Translucent faces drawn solid: blended against the picture's empty background they all but vanish, and
        // some NPCs are mostly translucent (the Tz-Kih's wings).
        merged.faceTransparencies = null;
        // Centred on its bounding box: a flying NPC's model floats above its base.
        int[] min = {Integer.MAX_VALUE, Integer.MAX_VALUE, Integer.MAX_VALUE};
        int[] max = {Integer.MIN_VALUE, Integer.MIN_VALUE, Integer.MIN_VALUE};
        for (int v = 0; v < merged.vertexCount; ++v) {
            int[] at = {merged.vertexX[v], merged.vertexY[v], merged.vertexZ[v]};
            for (int axis = 0; axis < 3; ++axis) {
                min[axis] = Math.min(min[axis], at[axis]);
                max[axis] = Math.max(max[axis], at[axis]);
            }
        }
        if (merged.vertexCount == 0) return null;
        merged.move(-(min[0] + max[0]) / 2, -(min[1] + max[1]) / 2, -(min[2] + max[2]) / 2);
        long radiusSquared = 0;
        for (int v = 0; v < merged.vertexCount; ++v) {
            long x = merged.vertexX[v];
            long y = merged.vertexY[v];
            long z = merged.vertexZ[v];
            radiusSquared = Math.max(radiusSquared, x * x + y * y + z * z);
        }
        int radius = Math.max(1, (int) Math.ceil(Math.sqrt(radiusSquared)));
        // The client lights an NPC with ambient 64 and contrast 850 plus the NPC's own, from (-30, -50, -30).
        Model model = (Model) LIGHT.invoke(null, merged, npc.ambient + 64, npc.contrast + 850, -30, -50, -30);

        RSTextureProvider textures = new RSTextureProvider(textureProvider, spriteProvider);
        textures.brightness = JagexColor.BRIGHTNESS_MAX;
        SpritePixels picture = new SpritePixels(size, size);
        Graphics3D graphics = new Graphics3D(textures);
        graphics.setBrightness(JagexColor.BRIGHTNESS_MAX);
        graphics.setRasterBuffer(picture.pixels, size, size);
        graphics.reset();
        graphics.setRasterClipping();
        graphics.setOffset(size / 2, size / 2);
        graphics.rasterGouraudLowRes = false;

        model.calculateBoundsCylinder();
        // Far enough back that the model's bounding sphere fills 90% of the picture.
        int distance = radius * graphics.Rasterizer3D_zoom * 2 / (size * 9 / 10);
        int up = distance * Graphics3D.SINE[pitch] >> 16;
        int back = distance * Graphics3D.COSINE[pitch] >> 16;
        model.projectAndDraw(graphics, 0, yaw, 0, pitch, 0, up, back);
        picture.drawBorder(1);
        return cropped(picture.toBufferedImage());
    }

    /** The picture cut to the pixels drawn, so a model with empty space in its bounds fills a footprint. */
    static BufferedImage cropped(BufferedImage image) {
        int minX = image.getWidth();
        int minY = image.getHeight();
        int maxX = -1;
        int maxY = -1;
        for (int y = 0; y < image.getHeight(); ++y) {
            for (int x = 0; x < image.getWidth(); ++x) {
                if ((image.getRGB(x, y) >>> 24) == 0) continue;
                minX = Math.min(minX, x);
                minY = Math.min(minY, y);
                maxX = Math.max(maxX, x);
                maxY = Math.max(maxY, y);
            }
        }
        if (maxX < 0) return null;
        return image.getSubimage(minX, minY, maxX - minX + 1, maxY - minY + 1);
    }

    /** One model of all the parts, as the client merges an NPC's models: faces and vertices appended in order. */
    static ModelDefinition merge(List<ModelDefinition> parts) throws IllegalAccessException {
        if (parts.size() == 1) return parts.get(0);
        int vertices = 0;
        int faces = 0;
        int textureFaces = 0;
        boolean renderTypes = false;
        boolean priorities = false;
        boolean transparencies = false;
        boolean textures = false;
        boolean textureCoords = false;
        boolean vertexGroups = false;
        for (ModelDefinition part : parts) {
            vertices += part.vertexCount;
            faces += part.faceCount;
            textureFaces += part.numTextureFaces;
            renderTypes |= part.faceRenderTypes != null;
            priorities |= part.faceRenderPriorities != null || part.priority != parts.get(0).priority;
            transparencies |= part.faceTransparencies != null;
            textures |= part.faceTextures != null;
            textureCoords |= part.textureCoords != null;
            vertexGroups |= VERTEX_GROUPS.get(part) != null;
        }
        ModelDefinition merged = new ModelDefinition();
        merged.vertexCount = vertices;
        merged.vertexX = new int[vertices];
        merged.vertexY = new int[vertices];
        merged.vertexZ = new int[vertices];
        // The animation labels of the vertices, shared by every part of the NPC.
        if (vertexGroups) merged.packedVertexGroups = new int[vertices];
        merged.faceCount = faces;
        merged.faceIndices1 = new int[faces];
        merged.faceIndices2 = new int[faces];
        merged.faceIndices3 = new int[faces];
        merged.faceColors = new short[faces];
        merged.priority = parts.get(0).priority;
        if (renderTypes) merged.faceRenderTypes = new byte[faces];
        if (priorities) merged.faceRenderPriorities = new byte[faces];
        if (transparencies) merged.faceTransparencies = new byte[faces];
        if (textures) merged.faceTextures = new short[faces];
        if (textureCoords) merged.textureCoords = new byte[faces];
        merged.numTextureFaces = textureFaces;
        if (textureFaces > 0) {
            merged.texIndices1 = new short[textureFaces];
            merged.texIndices2 = new short[textureFaces];
            merged.texIndices3 = new short[textureFaces];
            merged.textureRenderTypes = new byte[textureFaces];
        }
        int vertexBase = 0;
        int faceBase = 0;
        int textureFaceBase = 0;
        for (ModelDefinition part : parts) {
            System.arraycopy(part.vertexX, 0, merged.vertexX, vertexBase, part.vertexCount);
            System.arraycopy(part.vertexY, 0, merged.vertexY, vertexBase, part.vertexCount);
            System.arraycopy(part.vertexZ, 0, merged.vertexZ, vertexBase, part.vertexCount);
            int[][] groups = (int[][]) VERTEX_GROUPS.get(part);
            if (groups != null) {
                for (int label = 0; label < groups.length; ++label) {
                    for (int vertex : groups[label]) merged.packedVertexGroups[vertexBase + vertex] = label;
                }
            }
            for (int f = 0; f < part.faceCount; ++f) {
                int to = faceBase + f;
                merged.faceIndices1[to] = part.faceIndices1[f] + vertexBase;
                merged.faceIndices2[to] = part.faceIndices2[f] + vertexBase;
                merged.faceIndices3[to] = part.faceIndices3[f] + vertexBase;
                merged.faceColors[to] = part.faceColors[f];
                if (renderTypes) merged.faceRenderTypes[to] = part.faceRenderTypes == null ? 0 : part.faceRenderTypes[f];
                if (priorities) {
                    merged.faceRenderPriorities[to] =
                            part.faceRenderPriorities == null ? part.priority : part.faceRenderPriorities[f];
                }
                if (transparencies) {
                    merged.faceTransparencies[to] = part.faceTransparencies == null ? 0 : part.faceTransparencies[f];
                }
                if (textures) merged.faceTextures[to] = part.faceTextures == null ? -1 : part.faceTextures[f];
                if (textureCoords) {
                    byte coord = part.textureCoords == null ? -1 : part.textureCoords[f];
                    merged.textureCoords[to] = coord == -1 ? -1 : (byte) ((coord & 255) + textureFaceBase);
                }
            }
            for (int t = 0; t < part.numTextureFaces; ++t) {
                int to = textureFaceBase + t;
                merged.texIndices1[to] = (short) (part.texIndices1[t] + vertexBase);
                merged.texIndices2[to] = (short) (part.texIndices2[t] + vertexBase);
                merged.texIndices3[to] = (short) (part.texIndices3[t] + vertexBase);
                merged.textureRenderTypes[to] = part.textureRenderTypes == null ? 0 : part.textureRenderTypes[t];
            }
            vertexBase += part.vertexCount;
            faceBase += part.faceCount;
            textureFaceBase += part.numTextureFaces;
        }
        merged.computeAnimationTables();
        return merged;
    }
}
