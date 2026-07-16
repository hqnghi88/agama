package java.awt;

public class Rectangle implements Shape {
    public int x, y, width, height;

    public Rectangle() { this(0, 0, 0, 0); }
    public Rectangle(int x, int y, int width, int height) { this.x = x; this.y = y; this.width = width; this.height = height; }
    public Rectangle(int width, int height) { this(0, 0, width, height); }
    public Rectangle(java.awt.Point p) { this(p.x, p.y, 0, 0); }
    public Rectangle(java.awt.Point p, java.awt.Dimension d) { this(p.x, p.y, d.width, d.height); }
    public Rectangle(Rectangle r) { this(r.x, r.y, r.width, r.height); }

    public boolean isEmpty() { return width <= 0 || height <= 0; }
    public void setBounds(int x, int y, int width, int height) { this.x = x; this.y = y; this.width = width; this.height = height; }
    public void setBounds(Rectangle r) { setBounds(r.x, r.y, r.width, r.height); }
    public java.awt.Rectangle getBounds() { return new java.awt.Rectangle(this); }
    public java.awt.geom.Rectangle2D getBounds2D() { return new java.awt.geom.Rectangle2D.Double(x, y, width, height); }
    public void setRect(double x, double y, double w, double h) { this.x = (int) x; this.y = (int) y; this.width = (int) w; this.height = (int) h; }
    public boolean contains(double x, double y) { return x >= this.x && y >= this.y && x < this.x + this.width && y < this.y + this.height; }
    public boolean contains(double x, double y, double w, double h) { return false; }
    public boolean contains(java.awt.geom.Point2D p) { return contains(p.getX(), p.getY()); }
    public boolean contains(java.awt.geom.Rectangle2D r) { return false; }
    public boolean intersects(double x, double y, double w, double h) { return false; }
    public boolean intersects(java.awt.geom.Rectangle2D r) { return false; }
    public java.awt.geom.PathIterator getPathIterator(java.awt.geom.AffineTransform at) { return null; }
    public java.awt.geom.PathIterator getPathIterator(java.awt.geom.AffineTransform at, double flatness) { return null; }
    public boolean equals(Object obj) { if (obj instanceof Rectangle) { Rectangle r = (Rectangle) obj; return r.x == x && r.y == y && r.width == width && r.height == height; } return false; }
    public int hashCode() { return java.util.Objects.hash(x, y, width, height); }
    public String toString() { return "java.awt.Rectangle[x=" + x + ",y=" + y + ",width=" + width + ",height=" + height + "]"; }
}
