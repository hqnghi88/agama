package java.awt.image;

public class DirectColorModel extends ColorModel {
    public DirectColorModel(int bits, int rmask, int gmask, int bmask) { super(bits); }
    public DirectColorModel(int bits, int rmask, int gmask, int bmask, int amask) { super(bits); }
    public int getRedMask() { return 0xFF0000; }
    public int getGreenMask() { return 0xFF00; }
    public int getBlueMask() { return 0xFF; }
    public int getAlphaMask() { return 0; }
}
