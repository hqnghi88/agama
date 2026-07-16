package java.awt.geom;

public abstract class Point2D {
    public abstract double getX();
    public abstract double getY();
    public abstract void setLocation(double x, double y);
    public double distance(double x, double y) { double dx = getX() - x; double dy = getY() - y; return Math.sqrt(dx * dx + dy * dy); }
    public boolean equals(Object obj) { if (obj instanceof Point2D) { Point2D p = (Point2D) obj; return p.getX() == getX() && p.getY() == getY(); } return false; }

    public static class Double extends Point2D {
        protected double x, y;
        public Double() { this(0, 0); }
        public Double(double x, double y) { this.x = x; this.y = y; }
        public double getX() { return x; }
        public double getY() { return y; }
        public void setLocation(double x, double y) { this.x = x; this.y = y; }
    }

    public static class Float extends Point2D {
        protected float x, y;
        public Float() { this(0, 0); }
        public Float(float x, float y) { this.x = x; this.y = y; }
        public double getX() { return x; }
        public double getY() { return y; }
        public void setLocation(double x, double y) { this.x = (float) x; this.y = (float) y; }
    }
}
