package com.gama.nativeapp.display;

import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;

import java.awt.geom.Rectangle2D;
import java.awt.image.BufferedImage;
import java.util.List;

import org.locationtech.jts.geom.Coordinate;
import org.locationtech.jts.geom.Geometry;
import org.locationtech.jts.geom.GeometryCollection;
import org.locationtech.jts.geom.Lineal;
import org.locationtech.jts.geom.LinearRing;
import org.locationtech.jts.geom.MultiLineString;
import org.locationtech.jts.geom.MultiPolygon;
import org.locationtech.jts.geom.Puntal;

import gama.core.common.interfaces.IAsset;
import gama.core.common.interfaces.ILayer;
import gama.core.common.interfaces.IImageProvider;
import gama.core.metamodel.agent.IAgent;
import gama.core.outputs.display.AbstractDisplayGraphics;
import gama.core.outputs.layers.OverlayLayer;
import gama.core.outputs.layers.charts.ChartOutput;
import gama.core.runtime.IScope;
import gama.core.util.GamaColor;
import gama.core.util.file.GamaGeometryFile;
import gama.core.util.matrix.GamaField;
import gama.core.util.matrix.IField;
import gama.gaml.operators.Cast;
import gama.gaml.operators.Maths;
import gama.gaml.statements.draw.DrawingAttributes;
import gama.gaml.statements.draw.MeshDrawingAttributes;
import gama.gaml.statements.draw.ShapeDrawingAttributes;
import gama.gaml.statements.draw.TextDrawingAttributes;

public class AndroidDisplayGraphics extends AbstractDisplayGraphics {

    private Canvas canvas;
    private final Paint fillPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint strokePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint textPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint bgPaint = new Paint();
    private final Path workPath = new Path();
    private final RectF workRect = new RectF();

    private float currentAlpha = 1f;
    private Rectangle2D.Double rect = new Rectangle2D.Double();
    private int framesLogged = 0;
    private int drawnShapesCount = 0;

    public int getDrawnShapesCount() { return drawnShapesCount; }
    public void resetDrawnShapesCount() { drawnShapesCount = 0; }

    public AndroidDisplayGraphics() {
        fillPaint.setStyle(Paint.Style.FILL);
        strokePaint.setStyle(Paint.Style.STROKE);
        strokePaint.setStrokeWidth(1f);
        textPaint.setTypeface(android.graphics.Typeface.create("Helvetica", android.graphics.Typeface.BOLD));
        textPaint.setTextSize(24f);
    }

    public void setCanvas(Canvas c) { this.canvas = c; }
    public Canvas getCanvas() { return canvas; }

    private int gamaColorToArgb(GamaColor c) {
        if (c == null) return 0xFF000000;
        return 0xFF000000 | (c.getRGB() & 0xFFFFFF);
    }

    private int awtColorToArgb(java.awt.Color c) {
        if (c == null) return 0xFF000000;
        return c.getRGB();
    }

    private int colorWithAlpha(GamaColor c, double alpha) {
        int argb = gamaColorToArgb(c);
        int a = (int) (alpha * 255);
        return (argb & 0x00FFFFFF) | (a << 24);
    }

    private float toPixelX(double modelX) { return (float) xFromModelUnitsToPixels(modelX); }
    private float toPixelY(double modelY) { return (float) yFromModelUnitsToPixels(modelY); }
    private float toPixelW(double modelW) { return (float) wFromModelUnitsToPixels(modelW); }
    private float toPixelH(double modelH) { return (float) hFromModelUnitsToPixels(modelH); }

    @Override
    public Rectangle2D drawShape(Geometry gg, DrawingAttributes attributes) {
        if (gg == null || canvas == null) return null;
        drawnShapesCount++;
        
        if (framesLogged < 10) {
            android.util.Log.d("AndroidDisplayGraphics", "drawShape called! geomType=" + gg.getClass().getSimpleName() 
                + ", numCoords=" + gg.getCoordinates().length 
                + ", envW=" + getSurface().getEnvWidth() + ", envH=" + getSurface().getEnvHeight()
                + ", dispW=" + getDisplayWidth() + ", dispH=" + getDisplayHeight()
                + ", canvas=" + (canvas != null ? canvas.getWidth() + "x" + canvas.getHeight() : "null"));
            framesLogged++;
        }
        
        Geometry geometry = gg;

        if (geometry instanceof GeometryCollection && !(geometry instanceof MultiPolygon) && !(geometry instanceof MultiLineString)) {
            Rectangle2D.Double result = new Rectangle2D.Double();
            for (int i = 0; i < geometry.getNumGeometries(); i++) {
                Rectangle2D r = drawShape(geometry.getGeometryN(i), attributes);
                if (r != null) result.add(r);
            }
            return result;
        }

        boolean isLine = geometry instanceof Lineal || geometry instanceof Puntal;

        GamaColor border = isLine ? attributes.getColor() : attributes.getBorder();
        if (border == null && attributes.isEmpty()) border = attributes.getColor();
        if (highlight) {
            if (border != null) border = attributes.getColor();
        }

        workPath.reset();
        geometryToPath(geometry, workPath);

        try {
            float left = toPixelX(geometry.getEnvelopeInternal().getMinX());
            float top = toPixelY(geometry.getEnvelopeInternal().getMaxY());
            float right = toPixelX(geometry.getEnvelopeInternal().getMaxX());
            float bottom = toPixelY(geometry.getEnvelopeInternal().getMinY());
            rect.setRect(left, top, right - left, bottom - top);

            if (!isLine && !attributes.isEmpty()) {
                fillPaint.setColor(colorWithAlpha(attributes.getColor(), currentAlpha));
                canvas.drawPath(workPath, fillPaint);
            }
            if (isLine || border != null || attributes.isEmpty()) {
                strokePaint.setColor(colorWithAlpha(border != null ? border : attributes.getColor(), currentAlpha));
                canvas.drawPath(workPath, strokePaint);
            }
            return rect;
        } catch (Exception e) {
            return null;
        }
    }

    private void geometryToPath(Geometry geom, Path path) {
        if (geom instanceof LinearRing || "Polygon".equals(geom.getGeometryType())) {
            LinearRing shell = (geom instanceof LinearRing) ? (LinearRing) geom :
                    ((org.locationtech.jts.geom.Polygon) geom).getExteriorRing();
            coordsToPath(shell.getCoordinates(), path, true);
            if (geom instanceof org.locationtech.jts.geom.Polygon) {
                org.locationtech.jts.geom.Polygon poly = (org.locationtech.jts.geom.Polygon) geom;
                for (int i = 0; i < poly.getNumInteriorRing(); i++) {
                    coordsToPath(poly.getInteriorRingN(i).getCoordinates(), path, true);
                }
            }
        } else if ("MultiPolygon".equals(geom.getGeometryType())) {
            for (int i = 0; i < geom.getNumGeometries(); i++) {
                geometryToPath(geom.getGeometryN(i), path);
            }
        } else if ("MultiLineString".equals(geom.getGeometryType())) {
            for (int i = 0; i < geom.getNumGeometries(); i++) {
                geometryToPath(geom.getGeometryN(i), path);
            }
        } else if ("LineString".equals(geom.getGeometryType()) || "LinearRing".equals(geom.getGeometryType())) {
            coordsToPath(geom.getCoordinates(), path, false);
        } else if ("Point".equals(geom.getGeometryType())) {
            Coordinate c = geom.getCoordinate();
            path.addCircle(toPixelX(c.x), toPixelY(c.y), 3f, Path.Direction.CW);
        } else if (geom instanceof GeometryCollection) {
            for (int i = 0; i < geom.getNumGeometries(); i++) {
                geometryToPath(geom.getGeometryN(i), path);
            }
        } else {
            Coordinate[] coords = geom.getCoordinates();
            if (coords != null && coords.length > 0) {
                coordsToPath(coords, path, false);
            }
        }
    }

    private void coordsToPath(Coordinate[] coords, Path path, boolean close) {
        if (coords == null || coords.length == 0) return;
        path.moveTo(toPixelX(coords[0].x), toPixelY(coords[0].y));
        for (int i = 1; i < coords.length; i++) {
            path.lineTo(toPixelX(coords[i].x), toPixelY(coords[i].y));
        }
        if (close) path.close();
    }

    @Override
    public Rectangle2D drawImage(BufferedImage img, DrawingAttributes attributes) {
        if (img == null || canvas == null) return null;
        drawnShapesCount++;

        float curX, curY;
        if (attributes.getLocation() == null) {
            curX = (float) getXOffsetInPixels();
            curY = (float) getYOffsetInPixels();
        } else {
            curX = toPixelX(attributes.getLocation().getX());
            curY = toPixelY(attributes.getLocation().getY());
        }

        int curWidth, curHeight;
        if (attributes.getSize() == null) {
            curWidth = getLayerWidth();
            curHeight = getLayerHeight();
        } else {
            curWidth = (int) toPixelW(attributes.getSize().getX());
            curHeight = (int) toPixelH(attributes.getSize().getY());
        }

        Bitmap bitmap = bufferedImageToBitmap(img);
        if (bitmap == null) return null;

        canvas.save();
        if (attributes.getAngle() != null) {
            canvas.rotate((float) (Maths.toRad * attributes.getAngle()),
                    curX + curWidth / 2f, curY + curHeight / 2f);
        }
        canvas.drawBitmap(bitmap, null, new RectF(curX, curY, curX + curWidth, curY + curHeight), null);
        canvas.restore();

        rect.setRect(curX, curY, curWidth, curHeight);
        return rect;
    }

    public static Bitmap bufferedImageToBitmap(BufferedImage img) {
        if (img == null) return null;
        int w = img.getWidth();
        int h = img.getHeight();
        if (w <= 0 || h <= 0) return null;
        int[] pixels = new int[w * h];
        img.getRGB(0, 0, w, h, pixels, 0, w);
        for (int i = 0; i < pixels.length; i++) {
            pixels[i] = 0xFF000000 | pixels[i];
        }
        Bitmap bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888);
        bmp.setPixels(pixels, 0, w, 0, 0, w, h);
        return bmp;
    }

    @Override
    public Rectangle2D drawString(String string, TextDrawingAttributes attributes) {
        if (string == null || canvas == null) return null;

        if (string.contains("\n")) {
            Rectangle2D.Double result = new Rectangle2D.Double();
            for (String s : string.split("\n")) {
                Rectangle2D r = drawString(s, attributes);
                if (r != null) {
                    attributes.getLocation().setY(attributes.getLocation().getY() + r.getHeight());
                    result.add(r);
                }
            }
            return result;
        }

        textPaint.setColor(highlight ? gamaColorToArgb(data.getHighlightColor()) : gamaColorToArgb(attributes.getColor()));

        float curX, curY;
        if (attributes.getLocation() == null) {
            curX = (float) getXOffsetInPixels();
            curY = (float) getYOffsetInPixels();
        } else {
            curX = toPixelX(attributes.getLocation().getX());
            curY = toPixelY(attributes.getLocation().getY());
        }

        if (attributes.getFont() != null) {
            textPaint.setTextSize(attributes.getFont().getSize());
        }

        Paint.FontMetrics fm = textPaint.getFontMetrics();
        float textWidth = textPaint.measureText(string);
        float textHeight = fm.descent - fm.ascent;

        curX -= textWidth * attributes.anchor.x;
        curY += (textHeight - fm.descent) * attributes.anchor.y;

        canvas.save();
        if (attributes.getAngle() != null) {
            canvas.rotate((float) (Maths.toRad * attributes.getAngle()),
                    curX + textWidth / 2, curY + textHeight / 2);
        }
        canvas.drawText(string, curX, curY - fm.ascent, textPaint);
        canvas.restore();

        rect.setRect(curX, curY - textHeight, textWidth, textHeight);
        return rect;
    }

    @Override
    public Rectangle2D drawChart(ChartOutput chart) {
        if (chart == null || canvas == null) return null;
        try {
            BufferedImage im = chart.getImage(getLayerWidth(), getLayerHeight(), data.isAntialias());
            if (im != null) {
                drawImage(im, new DrawingAttributes(null, null, null, null, null, null));
            }
        } catch (Throwable ignored) {}
        return rect;
    }

    @Override
    public Rectangle2D drawAsset(IAsset file, DrawingAttributes attributes) {
        IScope scope = getSurface().getScope();
        if (file instanceof IImageProvider im) {
            return drawImage(im.getImage(scope, attributes.useCache()), attributes);
        }
        if (!(file instanceof GamaGeometryFile)) return null;
        gama.core.metamodel.shape.IShape shape = Cast.asGeometry(scope, file);
        if (shape == null) return null;
        return drawShape(shape.getInnerGeometry(), new ShapeDrawingAttributes(
                shape.getLocation(), attributes.getColor(), attributes.getColor(), (gama.core.metamodel.shape.IShape.Type) null));
    }

    @Override
    public Rectangle2D drawField(IField fieldValues, MeshDrawingAttributes attributes) {
        drawnShapesCount++;
        List<?> textures = attributes.getTextures();
        if (textures != null) {
            Object image = textures.get(0);
            if (image instanceof IImageProvider im) return drawAsset(im, attributes);
            if (image instanceof BufferedImage bi) return drawImage(bi, attributes);
        }
        if (!(fieldValues instanceof GamaField gf)) return null;
        GamaField flatten = gf.flatten(getSurface().getScope(), attributes.getColorProvider());
        attributes.setSize(null);
        return drawImage(flatten.getImage(getSurface().getScope()), attributes);
    }

    @Override
    public void fillBackground(java.awt.Color bgColor) {
        if (canvas == null) return;
        setAlpha(1);
        bgPaint.setColor(awtColorToArgb(bgColor));
        bgPaint.setStyle(Paint.Style.FILL);
        canvas.drawRect(0, 0, (float) getSurface().getDisplayWidth(),
                (float) getSurface().getDisplayHeight(), bgPaint);
    }

    @Override
    public void setAlpha(double alpha) {
        super.setAlpha(alpha);
        this.currentAlpha = (float) alpha;
        fillPaint.setAlpha((int) (alpha * 255));
        strokePaint.setAlpha((int) (alpha * 255));
        textPaint.setAlpha((int) (alpha * 255));
    }

    @Override
    public boolean beginDrawingLayers() {
        return true;
    }

    @Override
    public void beginDrawingLayer(final ILayer layer) {
        currentLayer = layer;
    }

    public void manuallyDrawAgents(IAgent[] agents) {
        if (canvas == null || agents == null) return;
        fillPaint.setColor(0xFF0000FF);
        fillPaint.setStyle(Paint.Style.FILL);
        for (IAgent a : agents) {
            if (a == null || a.dead()) continue;
            float x = toPixelX(a.getLocation().getX());
            float y = toPixelY(a.getLocation().getY());
            float r = (float) toPixelW(3.0);
            canvas.drawCircle(x, y, r, fillPaint);
        }
    }

    @Override
    public void beginOverlay(OverlayLayer layer) {
        if (canvas == null) return;
        int x = (int) getXOffsetInPixels();
        int y = (int) getYOffsetInPixels();
        int w = getLayerWidth();
        int h = getLayerHeight();

        bgPaint.setColor(awtColorToArgb(layer.getData().getBackgroundColor(getSurface().getScope())));
        bgPaint.setStyle(Paint.Style.FILL);
        if (layer.getData().isRounded()) {
            canvas.drawRoundRect(new RectF(x, y, x + w, y + h), 10, 10, bgPaint);
        } else {
            canvas.drawRect(x, y, x + w, y + h, bgPaint);
        }
        if (layer.getData().getBorderColor() != null) {
            bgPaint.setColor(awtColorToArgb(layer.getData().getBorderColor()));
            bgPaint.setStyle(Paint.Style.STROKE);
            if (layer.getData().isRounded()) {
                canvas.drawRoundRect(new RectF(x, y, x + w, y + h), 10, 10, bgPaint);
            } else {
                canvas.drawRect(x, y, x + w, y + h, bgPaint);
            }
        }
    }

    @Override
    public void endOverlay() {}

    @Override
    public boolean is2D() { return true; }

    @Override
    public void dispose() {
        super.dispose();
        canvas = null;
    }
}
