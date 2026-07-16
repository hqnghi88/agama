package java.awt;

public class FontMetrics {
    protected Font font;

    public FontMetrics(Font font) { this.font = font; }

    public Font getFont() { return font; }
    public int getAscent() { return 12; }
    public int getDescent() { return 3; }
    public int getHeight() { return 15; }
    public int getLeading() { return 0; }
    public int charWidth(char ch) { return 8; }
    public int charWidth(int codePoint) { return 8; }
    public int charsWidth(char[] chars, int offset, int numchars) { return numchars * 8; }
    public int stringWidth(String str) { return str == null ? 0 : str.length() * 8; }
    public int bytesWidth(byte[] data, int offset, int len) { return len * 8; }
    public int getMaxAscent() { return 12; }
    public int getMaxDescent() { return 3; }
    public int getMaxAdvance() { return 8; }
    public Font[] getAvailableFonts() { return new Font[0]; }
}
