package java.awt;

public class Font {
    public static final int PLAIN = 0;
    public static final int BOLD = 1;
    public static final int ITALIC = 2;

    public String name;
    public int style;
    public int size;

    public Font(String name, int style, int size) {
        this.name = name;
        this.style = style;
        this.size = size;
    }

    public static Font decode(String str) { return new Font("Dialog", PLAIN, 12); }
    public static Font getFont(String nm) { return new Font("Dialog", PLAIN, 12); }
    public static Font getFont(String nm, Font font) { return font; }
    public static Font getFont(String nm, int style) { return new Font(nm, style, 12); }

    public String getFontName() { return name; }
    public String getName() { return name; }
    public String getFamily() { return name; }
    public int getStyle() { return style; }
    public int getSize() { return size; }
    public int getSizePoints() { return size; }
    public boolean isPlain() { return style == PLAIN; }
    public boolean isBold() { return (style & BOLD) != 0; }
    public boolean isItalic() { return (style & ITALIC) != 0; }

    public Font deriveFont(int style) { return new Font(name, style, size); }
    public Font deriveFont(float size) { return new Font(name, style, (int) size); }
    public Font deriveFont(int style, float size) { return new Font(name, style, (int) size); }

    public int hashCode() { return name.hashCode() ^ style ^ size; }
    public boolean equals(Object obj) {
        if (obj instanceof Font) { Font f = (Font) obj; return name.equals(f.name) && style == f.style && size == f.size; }
        return false;
    }
    public String toString() { return getClass().getName() + "[name=" + name + ",style=" + style + ",size=" + size + "]"; }

    public static final Font DIALOG = new Font("Dialog", PLAIN, 12);
    public static final Font DIALOG_INPUT = new Font("DialogInput", PLAIN, 12);
    public static final Font SANS_SERIF = new Font("SansSerif", PLAIN, 12);
    public static final Font SERIF = new Font("Serif", PLAIN, 12);
    public static final Font MONOSPACED = new Font("Monospaced", PLAIN, 12);
}
