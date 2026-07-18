package java.awt.image;

public class WritableRaster extends Raster {
    private DataBuffer dataBuffer;

    public WritableRaster() {}
    public WritableRaster(DataBuffer buffer) { this.dataBuffer = buffer; }

    @Override
    public DataBuffer getDataBuffer() { return dataBuffer; }

    public void setPixel(int x, int y, int[] iArray) {}
    public void setPixel(int x, int y, float[] fArray) {}
    public void setPixel(int x, int y, double[] dArray) {}
    public void setPixels(int startX, int startY, int w, int h, int[] iArray) {}
    public void setSamples(int x, int y, int w, int h, int band, int[] iArray) {}
    public void setDataElements(int x, int y, Object inObj) {}
    public void setDataElements(int x, int y, int w, int h, Object inObj) {}
}
