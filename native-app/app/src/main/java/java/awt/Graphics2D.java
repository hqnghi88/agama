package java.awt;

import java.awt.image.BufferedImage;

public class Graphics2D extends Graphics {
    public Graphics2D() {}
    public void dispose() {}
    public void drawString(String str, int x, int y) {}
    public void drawString(String str, float x, float y) {}
    public void drawString(java.text.AttributedString as, float x, float y) {}
    public void fillRect(int x, int y, int width, int height) {}
    public void drawRect(int x, int y, int width, int height) {}
    public void drawOval(int x, int y, int width, int height) {}
    public void fillOval(int x, int y, int width, int height) {}
    public void drawLine(int x1, int y1, int x2, int y2) {}
    public void setRenderingHint(RenderingHints.Key key, Object value) {}
    public Object getRenderingHint(RenderingHints.Key key) { return null; }
    public void setRenderingHints(java.util.Map<?, ?> hints) {}
    public RenderingHints getRenderingHints() { return new RenderingHints(null); }
    public void setColor(Color c) {}
    public Color getColor() { return Color.BLACK; }
    public void setFont(Font font) {}
    public Font getFont() { return Font.DIALOG; }
    public void setStroke(Stroke s) {}
    public Stroke getStroke() { return null; }
    public void setComposite(Composite comp) {}
    public void setPaint(Paint paint) {}
    public Paint getPaint() { return null; }
    public void draw(Shape s) {}
    public void fill(Shape s) {}
    public java.awt.geom.AffineTransform getTransform() { return new java.awt.geom.AffineTransform(); }
    public void setTransform(java.awt.geom.AffineTransform Tx) {}
    public void transform(java.awt.geom.AffineTransform Tx) {}
    public void rotate(double theta) {}
    public void rotate(double theta, double x, double y) {}
    public void scale(double sx, double sy) {}
    public void translate(int x, int y) {}
    public void translate(double tx, double ty) {}
    public void clip(Shape s) {}
    public java.awt.Shape getClip() { return null; }
    public void setClip(Shape clip) {}
    public java.awt.Rectangle getClipBounds() { return new java.awt.Rectangle(); }
    public java.awt.image.ColorModel getColorModel() { return null; }
    public java.awt.FontMetrics getFontMetrics() { return null; }
    public java.awt.FontMetrics getFontMetrics(Font f) { return null; }
    public void copyArea(int x, int y, int width, int height, int dx, int dy) {}
    public void clearRect(int x, int y, int width, int height) {}
    public void fill3DRect(int x, int y, int width, int height, boolean raised) {}
    public void draw3DRect(int x, int y, int width, int height, boolean raised) {}
    public void drawArc(int x, int y, int width, int height, int startAngle, int arcAngle) {}
    public void fillArc(int x, int y, int width, int height, int startAngle, int arcAngle) {}
    public void drawPolyline(int[] xPoints, int[] yPoints, int nPoints) {}
    public void drawPolygon(int[] xPoints, int[] yPoints, int nPoints) {}
    public void fillPolygon(int[] xPoints, int[] yPoints, int nPoints) {}
    public void drawRoundRect(int x, int y, int width, int height, int arcWidth, int arcHeight) {}
    public void fillRoundRect(int x, int y, int width, int height, int arcWidth, int arcHeight) {}
    public void setBackground(Color color) {}
    public Color getBackground() { return Color.WHITE; }
    public BufferedImage createCompatibleImage(int width, int height, int transparency) { return new BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB); }
    public BufferedImage createCompatibleImage(int width, int height) { return new BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB); }
    public java.awt.Rectangle getBounds() { return new java.awt.Rectangle(); }
    public java.awt.Rectangle getClipBounds(java.awt.Rectangle r) { return r; }
    public boolean hit(java.awt.Rectangle rect, Shape s, boolean onStroke) { return false; }
}
