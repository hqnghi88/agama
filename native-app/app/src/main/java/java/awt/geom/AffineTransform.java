package java.awt.geom;

public class AffineTransform {
    public AffineTransform() {}
    public AffineTransform(double m00, double m10, double m01, double m11, double m02, double m12) {}
    public AffineTransform(AffineTransform Tx) {}
    public void setToIdentity() {}
    public void setToTranslation(double tx, double ty) {}
    public void setToRotation(double theta) {}
    public void setToRotation(double theta, double x, double y) {}
    public void setToScale(double sx, double sy) {}
    public void setToShear(double shx, double shy) {}
    public void concatenate(AffineTransform Tx) {}
    public void preConcatenate(AffineTransform Tx) {}
    public void transform(double[] srcPts, int srcOff, double[] dstPts, int dstOff, int numPts) {}
    public void transform(float[] srcPts, int srcOff, double[] dstPts, int dstOff, int numPts) {}
    public void transform(double[] srcPts, int srcOff, float[] dstPts, int dstOff, int numPts) {}
    public void transform(float[] srcPts, int srcOff, float[] dstPts, int dstOff, int numPts) {}
    public Point2D transform(Point2D ptSrc, Point2D ptDst) { return ptDst; }
    public void transform(java.awt.geom.Point2D[] srcPts, int srcOff, java.awt.geom.Point2D[] dstPts, int dstOff, int numPts) {}
    public Point2D inverseTransform(Point2D ptSrc, Point2D ptDst) { return ptDst; }
    public AffineTransform createInverse() { return new AffineTransform(); }
    public double getDeterminant() { return 1; }
    public boolean isIdentity() { return true; }
    public int getType() { return 0; }
    public double getScaleX() { return 1; }
    public double getScaleY() { return 1; }
    public double getShearX() { return 0; }
    public double getShearY() { return 0; }
    public double getTranslateX() { return 0; }
    public double getTranslateY() { return 0; }
    public void scale(double sx, double sy) {}
    public void rotate(double theta) {}
    public void rotate(double theta, double x, double y) {}
    public void translate(double tx, double ty) {}
    public void shear(double shx, double shy) {}
    public Object clone() { return new AffineTransform(); }

    public static final int TYPE_IDENTITY = 0;
    public static final int TYPE_TRANSLATION = 1;
    public static final int TYPE_UNIFORM_SCALE = 2;
    public static final int TYPE_GENERAL_SCALE = 4;
    public static final int TYPE_FLIP = 64;
    public static final int TYPE_QUADRANT_ROTATION = 8;
    public static final int TYPE_GENERAL_ROTATION = 16;
    public static final int TYPE_GENERAL_TRANSFORM = 32;
    public static final int TYPE_MASK_SCALE = 6;
    public static final int TYPE_MASK_ROTATION = 24;
}
