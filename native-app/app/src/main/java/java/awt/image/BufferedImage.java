package java.awt.image;

import java.awt.Color;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.Image;
import java.awt.Transparency;
import java.awt.geom.AffineTransform;
import java.awt.image.ColorModel;
import java.awt.image.WritableRaster;

public class BufferedImage extends Image implements Transparency {
    public static final int TYPE_INT_RGB = 1;
    public static final int TYPE_INT_ARGB = 2;
    public static final int TYPE_INT_ARGB_PRE = 3;
    public static final int TYPE_INT_BGR = 4;
    public static final int TYPE_3BYTE_BGR = 5;
    public static final int TYPE_4BYTE_ABGR = 6;
    public static final int TYPE_4BYTE_ABGR_PRE = 7;
    public static final int TYPE_USHORT_565_RGB = 8;
    public static final int TYPE_USHORT_555_RGB = 9;
    public static final int TYPE_BYTE_GRAY = 10;
    public static final int TYPE_USHORT_GRAY = 11;
    public static final int TYPE_BYTE_BINARY = 12;
    public static final int TYPE_BYTE_INDEXED = 13;

    private int width;
    private int height;
    private int type;
    private int[] data;
    private ColorModel colorModel;
    private WritableRaster raster;

    public BufferedImage(int width, int height, int imageType) {
        this.width = width;
        this.height = height;
        this.type = imageType;
        this.data = new int[width * height];
        this.colorModel = new DirectColorModel(24, 0xFF0000, 0xFF00, 0xFF);
        this.raster = new WritableRaster(new DataBufferInt(this.data, width * height));
    }

    public BufferedImage(int width, int height, int imageType, IndexColorModel cm) {
        this(width, height, imageType);
    }

    public BufferedImage(ColorModel cm, WritableRaster raster, boolean isRasterPremultiplied, java.util.Hashtable<?,?> properties) {
        this.width = raster.getWidth();
        this.height = raster.getHeight();
        this.type = TYPE_INT_ARGB;
        this.raster = raster;
        this.data = ((DataBufferInt) raster.getDataBuffer()).getData();
        this.colorModel = cm;
    }

    @Override public int getWidth(ImageObserver observer) { return width; }
    @Override public int getHeight(ImageObserver observer) { return height; }
    public int getWidth() { return width; }
    public int getHeight() { return height; }
    public int getType() { return type; }

    @Override
    public Object getProperty(String name, ImageObserver observer) { return null; }
    public Object getProperty(String name) { return null; }
    public String[] getPropertyNames() { return new String[0]; }

    @Override
    public ColorModel getColorModel() { return colorModel != null ? colorModel : new DirectColorModel(24, 0xFF0000, 0xFF00, 0xFF); }
    public ColorModel getColorModel(int x, int y, int w, int h) { return getColorModel(); }

    @Override
    public WritableRaster getRaster() { return raster; }

    @Override
    public Graphics getGraphics() { return new Graphics2D(); }

    public Graphics2D createGraphics() { return new Graphics2D(); }

    @Override
    public void flush() {}

    @Override
    public int getTransparency() { return Transparency.OPAQUE; }

    public int getMinX() { return 0; }
    public int getMinY() { return 0; }

    public int getRGB(int x, int y) { return (x >= 0 && x < width && y >= 0 && y < height) ? data[y * width + x] : 0; }
    public void setRGB(int x, int y, int rgb) { if (x >= 0 && x < width && y >= 0 && y < height) data[y * width + x] = rgb; }
    public void setRGB(int startX, int startY, int w, int h, int[] rgbArray, int offset, int scansize) {
        for (int row = 0; row < h; row++) {
            for (int col = 0; col < w; col++) {
                int idx = startY + row;
                int idxx = startX + col;
                if (idxx >= 0 && idxx < width && idx >= 0 && idx < height) {
                    data[idx * width + idxx] = rgbArray[offset + row * scansize + col];
                }
            }
        }
    }

    public int[] getRGB(int startX, int startY, int w, int h, int[] rgbArray, int offset, int scansize) {
        if (rgbArray == null) rgbArray = new int[w * h];
        for (int row = 0; row < h; row++) {
            for (int col = 0; col < w; col++) {
                rgbArray[offset + row * scansize + col] = getRGB(startX + col, startY + row);
            }
        }
        return rgbArray;
    }
}
