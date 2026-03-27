package dev.cerus.explorersmap.util;

import com.hypixel.hytale.protocol.packets.worldmap.MapImage;
import com.hypixel.hytale.server.core.universe.world.chunk.palette.BitFieldArr;
import com.hypixel.hytale.server.core.universe.world.worldmap.provider.chunk.ImageBuilder;
import it.unimi.dsi.fastutil.ints.Int2IntOpenHashMap;
import it.unimi.dsi.fastutil.ints.IntOpenHashSet;
import java.awt.image.BufferedImage;

public class MapImageUtil {
    private static final int[][] BAYER_MATRIX = new int[][]{{0, 8, 2, 10}, {12, 4, 14, 6}, {3, 11, 1, 9}, {15, 7, 13, 5}};

    private static int quantizeChannel(int value) {
        return Math.min(255, (value + 4) / 8 * 8);
    }

    private static int quantizeColor(int argb) {
        int r = quantizeChannel(argb >> 24 & 0xFF);
        int g = quantizeChannel(argb >> 16 & 0xFF);
        int b = quantizeChannel(argb >> 8 & 0xFF);
        int a = argb & 0xFF;
        return r << 24 | g << 16 | b << 8 | a;
    }

    private static int quantizeChannelWithDither(int value, int ditherOffset) {
        int adjusted = value + ditherOffset;
        adjusted = Math.max(0, Math.min(255, adjusted));
        return Math.min(255, (adjusted + 4) / 8 * 8);
    }

    private static int quantizeColorWithDither(int argb, int x, int y) {
        int bayerValue = BAYER_MATRIX[y & 3][x & 3];
        int ditherOffset = (bayerValue - 8) * 8 / 16;
        int r = quantizeChannelWithDither(argb >> 24 & 0xFF, ditherOffset);
        int g = quantizeChannelWithDither(argb >> 16 & 0xFF, ditherOffset);
        int b = quantizeChannelWithDither(argb >> 8 & 0xFF, ditherOffset);
        int a = argb & 0xFF;
        return r << 24 | g << 16 | b << 8 | a;
    }

    private static boolean isInTransitionZone(int index, int imageWidth, int imageHeight, int[] rawPixels) {
        int centerPixel = rawPixels[index];
        int centerQuantized = quantizeColor(centerPixel);
        int x = index % imageWidth;
        int y = index / imageWidth;

        for (int dy = -2; dy <= 2; dy++) {
            for (int dx = -2; dx <= 2; dx++) {
                if (dx != 0 || dy != 0) {
                    int nx = x + dx;
                    int ny = y + dy;
                    if (nx >= 0 && nx < imageWidth && ny >= 0 && ny < imageHeight) {
                        int neighborPixel = rawPixels[ny * imageWidth + nx];
                        int neighborQuantized = quantizeColor(neighborPixel);
                        if (neighborQuantized != centerQuantized) {
                            return true;
                        }
                    }
                }
            }
        }

        return false;
    }

    private static int calculateBitsRequired(int colorCount) {
        if (colorCount <= 16) {
            return 4;
        } else if (colorCount <= 256) {
            return 8;
        } else {
            return colorCount <= 4096 ? 12 : 16;
        }
    }

    public static MapImage buildMapImage(int width, int height, int[] pixels) {
        int pixelCount = pixels.length;
        int[] processedPixels = new int[pixelCount];
        IntOpenHashSet uniqueColors = new IntOpenHashSet();

        for (int i = 0; i < pixelCount; i++) {
            int pixel;
            if (ImageBuilder.isQuantizationEnabled()) {
                if (isInTransitionZone(i, width, height, pixels)) {
                    int x = i % width;
                    int y = i / height;
                    pixel = quantizeColorWithDither(pixels[i], x, y);
                } else {
                    pixel = quantizeColor(pixels[i]);
                }
            } else {
                pixel = pixels[i];
            }

            processedPixels[i] = pixel;
            uniqueColors.add(pixel);
        }

        int[] palette = uniqueColors.toIntArray();
        int bitsPerIndex = calculateBitsRequired(palette.length);
        Int2IntOpenHashMap colorToIndex = new Int2IntOpenHashMap(palette.length);

        for (int i = 0; i < palette.length; i++) {
            colorToIndex.put(palette[i], i);
        }

        BitFieldArr indices = new BitFieldArr(bitsPerIndex, pixelCount);

        for (int i = 0; i < pixelCount; i++) {
            indices.set(i, colorToIndex.get(processedPixels[i]));
        }

        byte[] packedIndices = indices.get();
        return new MapImage(width, height, palette, (byte)bitsPerIndex, packedIndices);
    }

    public static BufferedImage toBufferedImage(MapImage mapImage) {
        BufferedImage image = new BufferedImage(mapImage.width, mapImage.height, BufferedImage.TYPE_INT_ARGB);

        int pixelCount = mapImage.width * mapImage.height;
        BitFieldArr indices = new BitFieldArr(mapImage.bitsPerIndex, pixelCount);
        indices.set(mapImage.packedIndices);
        for (int i = 0; i < pixelCount; i++) {
            int colIdx = indices.get(i);
            int color = mapImage.palette[colIdx];
            int r = color >> 24 & 0xFF;
            int g = color >> 16 & 0xFF;
            int b = color >> 8 & 0xFF;
            int a = color >> 0 & 0xFF;
            int x = i % mapImage.width;
            int y = i / mapImage.width;
            image.setRGB(x, y, ((a & 0xFF) << 24) | ((r & 0xFF) << 16) | ((g & 0xFF) << 8) | ((b & 0xFF) << 0));
        }
        return image;
    }
}
