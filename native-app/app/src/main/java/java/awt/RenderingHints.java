package java.awt;

public class RenderingHints extends java.util.AbstractMap<RenderingHints.Key, Object> {
    public static final Key KEY_ANTIALIASING = new Key(1);
    public static final Key KEY_TEXT_ANTIALIASING = new Key(2);
    public static final Key KEY_RENDERING = new Key(3);
    public static final Key KEY_INTERPOLATION = new Key(4);
    public static final Key KEY_COLOR_RENDERING = new Key(5);
    public static final Key KEY_DITHERING = new Key(6);
    public static final Key KEY_FRACTIONALMETRICS = new Key(7);
    public static final Key KEY_STROKE_CONTROL = new Key(8);
    public static final Key KEY_RESOLUTION_VARIANT = new Key(9);
    public static final Object VALUE_ANTIALIAS_ON = "ON";
    public static final Object VALUE_ANTIALIAS_OFF = "OFF";
    public static final Object VALUE_TEXT_ANTIALIAS_ON = "ON";
    public static final Object VALUE_TEXT_ANTIALIAS_OFF = "OFF";
    public static final Object VALUE_RENDER_QUALITY = "QUALITY";
    public static final Object VALUE_RENDER_SPEED = "SPEED";
    public static final Object VALUE_INTERPOLATION_BICUBIC = "BICUBIC";
    public static final Object VALUE_INTERPOLATION_BILINEAR = "BILINEAR";
    public static final Object VALUE_INTERPOLATION_NEAREST_NEIGHBOR = "NEAREST_NEIGHBOR";

    public RenderingHints(java.util.Map<Key, Object> init) {}

    @Override public int size() { return 0; }
    @Override public boolean isEmpty() { return true; }
    @Override public boolean containsKey(Object key) { return false; }
    @Override public Object get(Object key) { return null; }
    @Override public Object put(Key key, Object value) { return null; }
    @Override public Object remove(Object key) { return null; }
    @Override public java.util.Set<Entry<Key, Object>> entrySet() { return new java.util.HashSet<>(); }

    public void add(RenderingHints.Key key, Object value) {}
    public void add(RenderingHints hints) {}
    public boolean isKey(Object key) { return false; }

    public static class Key {
        private int key;
        public Key(int key) { this.key = key; }
        public int getKey() { return key; }
    }
}
