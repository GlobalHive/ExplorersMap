package dev.cerus.explorersmap.map;

import com.hypixel.hytale.protocol.packets.worldmap.MapImage;
import dev.cerus.explorersmap.util.MapImageUtil;
import java.awt.Image;
import java.awt.image.BufferedImage;

public interface Resolution {
    Resolution BEST = new ResolutionImpl(96, "BEST");
    Resolution GOOD = new ResolutionImpl(32, "GOOD");
    Resolution FAST = new ResolutionImpl(16, "FAST");
    Resolution FASTER = new ResolutionImpl(8, "FASTER");
    Resolution FASTEST = new ResolutionImpl(4, "FASTEST");

    BufferedImage rescale(BufferedImage tile);

    MapImage rescale(MapImage tile);

    float getScale();

    String getType();

    class ResolutionImpl implements Resolution {
        private final int imageSize;
        private final String type;

        private ResolutionImpl(int imageSize, String type) {
            this.imageSize = imageSize;
            this.type = type;
        }

        @Override
        public BufferedImage rescale(BufferedImage tile) {
            if (tile.getWidth() == imageSize && tile.getHeight() == imageSize) {
                return tile;
            }
            return resizeImage(tile, imageSize, imageSize);
        }

        @Override
        public MapImage rescale(MapImage tile) {
            if (tile.width == imageSize && tile.height == imageSize) {
                return tile;
            }

            BufferedImage tileImg = MapImageUtil.toBufferedImage(tile);
            tileImg = rescale(tileImg);

            int[] pixels = new int[imageSize * imageSize];
            for (int x = 0; x < tileImg.getWidth(); x++) {
                for (int y = 0; y < tileImg.getHeight(); y++) {
                    int rgb = tileImg.getRGB(x, y);
                    pixels[y * imageSize + x] = (((rgb >> 16) & 0xFF) & 255) << 24 | (((rgb >> 8) & 0xFF) & 255) << 16 | ((rgb & 0xFF) & 255) << 8 | (((rgb >> 24) & 0xFF) & 255);
                }
            }
            tile = MapImageUtil.buildMapImage(imageSize, imageSize, pixels);
            return tile;
        }

        @Override
        public float getScale() {
            return imageSize / 32f;
        }

        @Override
        public String getType() {
            return type;
        }

        private BufferedImage resizeImage(BufferedImage originalImage, int targetWidth, int targetHeight) {
            Image resultingImage = originalImage.getScaledInstance(targetWidth, targetHeight, Image.SCALE_SMOOTH);
            BufferedImage outputImage = new BufferedImage(targetWidth, targetHeight, BufferedImage.TYPE_INT_ARGB);
            outputImage.getGraphics().drawImage(resultingImage, 0, 0, null);
            return outputImage;
        }
    }
}
