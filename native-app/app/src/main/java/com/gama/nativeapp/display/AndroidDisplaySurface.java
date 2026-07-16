package com.gama.nativeapp.display;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.graphics.PointF;
import android.graphics.Rect;
import android.graphics.RectF;
import android.os.Handler;
import android.os.Looper;
import android.util.AttributeSet;
import android.view.MotionEvent;
import android.view.ScaleGestureDetector;
import android.view.View;

import java.awt.Font;
import java.awt.Point;
import java.awt.Rectangle;
import java.awt.geom.Rectangle2D;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;

import org.locationtech.jts.geom.Envelope;

import gama.core.common.interfaces.GeneralSynchronizer;
import gama.core.common.interfaces.IDisplaySurface;
import gama.core.common.interfaces.IGraphics;
import gama.core.common.interfaces.IKeyword;
import gama.core.common.interfaces.ILayer;
import gama.core.common.interfaces.ILayerManager;
import gama.core.metamodel.agent.IAgent;
import gama.core.metamodel.shape.GamaPoint;
import gama.core.metamodel.shape.IShape;
import gama.core.outputs.LayeredDisplayData;
import gama.core.outputs.LayeredDisplayOutput;
import gama.core.outputs.display.LayerManager;
import gama.core.outputs.layers.IEventLayerListener;
import gama.core.outputs.layers.OverlayLayer;
import gama.core.runtime.GAMA;
import gama.core.runtime.IScope.IGraphicsScope;

public class AndroidDisplaySurface extends View implements IDisplaySurface {

    private final LayeredDisplayOutput output;
    private final ILayerManager layerManager;
    private AndroidDisplayGraphics androidGraphics;
    private IGraphicsScope scope;

    private final Rect viewPort = new Rect();
    private int displayWidth, displayHeight;
    private boolean zoomFit = true;
    private double zoomLevel = 1.0;
    private volatile boolean disposed = false;
    private boolean isLocked = false;
    private int frames = 0;
    private boolean rendered = false;

    private PointF mousePosition = new PointF(-1, -1);
    private final Set<IEventLayerListener> listeners = new HashSet<>();
    private final Handler uiHandler = new Handler(Looper.getMainLooper());

    private float lastTouchX, lastTouchY;
    private ScaleGestureDetector scaleDetector;
    private final Paint bgPaint = new Paint();

    public AndroidDisplaySurface(Context context, LayeredDisplayOutput output) {
        super(context);
        this.output = output;
        output.setSurface(this);
        setDisplayScope(output.getScope().copyForGraphics("in android2d display"));
        output.getData().addListener(this);
        this.layerManager = new LayerManager(this, output);
        this.androidGraphics = new AndroidDisplayGraphics();
        this.androidGraphics.setDisplaySurface(this);

        bgPaint.setColor(output.getData().getBackgroundColor().getRGB() | 0xFF000000);
        bgPaint.setStyle(Paint.Style.FILL);

        scaleDetector = new ScaleGestureDetector(context, new ScaleGestureDetector.SimpleOnScaleGestureListener() {
            @Override
            public boolean onScale(ScaleGestureDetector detector) {
                float factor = detector.getScaleFactor();
                if (factor > 1.0f) zoomIn();
                else zoomOut();
                return true;
            }
        });

        setClickable(true);
        setFocusable(true);
    }

    public AndroidDisplaySurface(Context context, AttributeSet attrs) {
        super(context, attrs);
        this.output = null;
        this.layerManager = null;
    }

    @Override
    protected void onSizeChanged(int w, int h, int oldw, int oldh) {
        super.onSizeChanged(w, h, oldw, oldh);
        if (w > 0 && h > 0) {
            if (zoomFit) {
                zoomFit();
            }
        }
    }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        if (disposed || output == null) return;

        canvas.drawColor(bgPaint.getColor());

        if (androidGraphics == null) {
            androidGraphics = new AndroidDisplayGraphics();
            androidGraphics.setDisplaySurface(this);
        }

        androidGraphics.setCanvas(canvas);
        layerManager.drawLayersOn(androidGraphics);
        frames++;
        rendered = true;
    }

    @Override
    public boolean onTouchEvent(MotionEvent event) {
        if (disposed || output == null) return false;

        scaleDetector.onTouchEvent(event);

        float x = event.getX();
        float y = event.getY();

        switch (event.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                lastTouchX = x;
                lastTouchY = y;
                mousePosition.set(x, y);
                dispatchMouseEvent(16, (int) x, (int) y);
                return true;

            case MotionEvent.ACTION_MOVE:
                if (event.getPointerCount() > 1) return scaleDetector.onTouchEvent(event);
                mousePosition.set(x, y);
                dispatchMouseEvent(6, (int) x, (int) y);
                if (!isLocked) {
                    float dx = x - lastTouchX;
                    float dy = y - lastTouchY;
                    viewPort.offset((int) dx, (int) dy);
                    invalidate();
                }
                lastTouchX = x;
                lastTouchY = y;
                return true;

            case MotionEvent.ACTION_UP:
                mousePosition.set(x, y);
                dispatchMouseEvent(17, (int) x, (int) y);
                return true;
        }
        return super.onTouchEvent(event);
    }

    // -- IDisplaySurface implementation --

    @Override
    public java.awt.image.BufferedImage getImage(int width, int height) {
        int w = width > 0 ? width : super.getWidth();
        int h = height > 0 ? height : super.getHeight();
        if (w <= 0 || h <= 0) return null;
        Bitmap bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888);
        Canvas c = new Canvas(bmp);
        androidGraphics.setCanvas(c);
        layerManager.drawLayersOn(androidGraphics);
        androidGraphics.setCanvas(null);

        int[] pixels = new int[w * h];
        bmp.getPixels(pixels, 0, w, 0, 0, w, h);
        java.awt.image.BufferedImage image = new java.awt.image.BufferedImage(w, h, java.awt.image.BufferedImage.TYPE_INT_ARGB);
        image.setRGB(0, 0, w, h, pixels, 0, w);
        bmp.recycle();
        return image;
    }

    @Override
    public void updateDisplay(boolean force, GeneralSynchronizer synchronizer) {
        if (disposed) return;
        uiHandler.post(() -> {
            invalidate();
            if (synchronizer != null) synchronizer.release();
        });
    }

    @Override
    public void setMenuManager(Object displaySurfaceMenu) {}

    @Override
    public void zoomIn() {
        if (isLocked) return;
        float factor = 1.2f;
        int newW = (int) (getDisplayWidth() * factor);
        int newH = (int) (getDisplayHeight() * factor);
        if (resizeImage(newW, newH, false)) {
            zoomFit = false;
            updateZoomLevel();
            invalidate();
        }
    }

    @Override
    public void zoomOut() {
        if (isLocked) return;
        float factor = 0.8f;
        int newW = (int) Math.max(10, getDisplayWidth() * factor);
        int newH = (int) Math.max(10, getDisplayHeight() * factor);
        if (resizeImage(newW, newH, false)) {
            zoomFit = false;
            updateZoomLevel();
            invalidate();
        }
    }

    @Override
    public void zoomFit() {
        int w = getWidth();
        int h = getHeight();
        if (w <= 0 || h <= 0) return;
        mousePosition.set(w / 2f, h / 2f);
        if (resizeImage(w, h, false)) {
            this.zoomLevel = 1.0;
            this.zoomFit = true;
            viewPort.set(0, 0, w, h);
            invalidate();
        }
    }

    @Override
    public void toggleLock() { isLocked = !isLocked; }

    @Override
    public ILayerManager getManager() { return layerManager; }

    @Override
    public void focusOn(IShape geometry) {
        if (geometry == null) return;
        Rectangle2D r = layerManager.focusOn(geometry, this);
        if (r == null) return;
        float xScale = (float) (getWidth() / r.getWidth());
        float yScale = (float) (getHeight() / r.getHeight());
        float zf = Math.min(xScale, yScale);
        viewPort.set(0, 0, (int) (getDisplayWidth() * zf), (int) (getDisplayHeight() * zf));
        invalidate();
    }

    @Override
    public void runAndUpdate(Runnable r) {
        new Thread(() -> {
            r.run();
            uiHandler.post(this::invalidate);
        }).start();
    }

    @Override
    public void outputReloaded() {
        setDisplayScope(output.getScope().copyForGraphics("in android2d display"));
        layerManager.outputChanged();
        if (zoomFit) zoomFit();
        invalidate();
    }

    @Override
    public double getEnvWidth() { return output.getData().getEnvWidth(); }

    @Override
    public double getEnvHeight() { return output.getData().getEnvHeight(); }

    @Override
    public double getDisplayWidth() { return viewPort.width(); }

    @Override
    public double getDisplayHeight() { return viewPort.height(); }

    @Override
    public Collection<IAgent> selectAgent(int x, int y) {
        int xc = x - viewPort.left;
        int yc = y - viewPort.top;
        List<IAgent> result = new ArrayList<>();
        List<ILayer> layers = layerManager.getLayersIntersecting(xc, yc);
        for (ILayer layer : layers) {
            Collection<IAgent> agents = layer.collectAgentsAt(xc, yc, this);
            if (agents != null && !agents.isEmpty()) result.addAll(agents);
        }
        return result;
    }

    @Override
    public double getZoomLevel() { return zoomLevel; }

    @Override
    public void setSize(int x, int y) {
        viewPort.set(0, 0, x, y);
    }

    @Override
    public LayeredDisplayOutput getOutput() { return output; }

    @Override
    public LayeredDisplayData getData() { return output != null ? output.getData() : null; }

    @Override
    public void layersChanged() { invalidate(); }

    @Override
    public void addListener(IEventLayerListener e) { listeners.add(e); }

    @Override
    public void removeListener(IEventLayerListener e) { listeners.remove(e); }

    @Override
    public Collection<IEventLayerListener> getLayerListeners() { return listeners; }

    @Override
    public Envelope getVisibleRegionForLayer(ILayer currentLayer) {
        if (currentLayer instanceof OverlayLayer && scope != null) {
            return scope.getSimulation().getEnvelope();
        }
        Envelope e = new Envelope();
        int xc = -viewPort.left;
        int yc = -viewPort.top;
        e.expandToInclude(currentLayer.getModelCoordinatesFrom(xc, yc, this));
        xc += (int) getDisplayWidth();
        yc += (int) getDisplayHeight();
        e.expandToInclude(currentLayer.getModelCoordinatesFrom(xc, yc, this));
        return e;
    }

    @Override
    public int getFPS() { int r = frames; frames = 0; return r; }

    @Override
    public boolean isDisposed() { return disposed; }

    @Override
    public void getModelCoordinatesInfo(StringBuilder receiver) {
        receiver.append("Model coordinates: ").append(mousePosition.x).append(", ").append(mousePosition.y);
    }

    @Override
    public void dispatchKeyEvent(char character) {
        for (IEventLayerListener gl : listeners) gl.keyPressed(String.valueOf(character));
    }

    @Override
    public void dispatchSpecialKeyEvent(int keyCode) {
        for (IEventLayerListener gl : listeners) gl.specialKeyPressed(keyCode);
    }

    @Override
    public void dispatchMouseEvent(int swtEventType, int x, int y) {
        for (IEventLayerListener gl : listeners) {
            switch (swtEventType) {
                case 16: gl.mouseDown(x, y, 1); break;
                case 17: gl.mouseUp(x, y, 1); break;
                case 6: gl.mouseMove(x, y); break;
                case 5: gl.mouseDrag(x, y, 1); break;
                case 7: gl.mouseEnter(x, y); break;
                case 8: gl.mouseExit(x, y); break;
            }
        }
    }

    @Override
    public void setMousePosition(int x, int y) { mousePosition.set(x, y); }

    @Override
    public void draggedTo(int x, int y) {
        if (!isLocked) {
            float dx = x - mousePosition.x;
            float dy = y - mousePosition.y;
            viewPort.offset((int) dx, (int) dy);
            invalidate();
        }
        mousePosition.set(x, y);
    }

    @Override
    public void selectAgentsAroundMouse() {}

    @Override
    public IGraphicsScope getScope() { return scope; }

    @Override
    public boolean isVisible() { return getVisibility() == VISIBLE; }

    @Override
    public IGraphics getIGraphics() { return androidGraphics; }

    @Override
    public Rectangle getBoundsForRobotSnapshot() { return new Rectangle(getWidth(), getHeight()); }

    @Override
    public GamaPoint getModelCoordinates() {
        List<ILayer> layers = layerManager.getLayersIntersecting((int) mousePosition.x, (int) mousePosition.y);
        for (ILayer layer : layers) {
            if (layer.isProvidingWorldCoordinates()) {
                return layer.getModelCoordinatesFrom((int) mousePosition.x, (int) mousePosition.y, this);
            }
        }
        return new GamaPoint();
    }

    @Override
    public void changed(LayeredDisplayData.Changes property, Object value) {
        if (property == LayeredDisplayData.Changes.BACKGROUND) {
            bgPaint.setColor(((java.awt.Color) value).getRGB() | 0xFF000000);
        }
    }

    @Override
    public Font computeFont(Font f) { return f; }

    private void setDisplayScope(IGraphicsScope scope) {
        if (this.scope != null) GAMA.releaseScope(this.scope);
        this.scope = scope;
    }

    private void updateZoomLevel() {
        if (getEnvWidth() > 0 && getEnvHeight() > 0) {
            zoomLevel = Math.min(getDisplayWidth() / getEnvWidth(), getDisplayHeight() / getEnvHeight());
        }
    }

    private boolean resizeImage(int x, int y, boolean force) {
        if (!force && x == getDisplayWidth() && y == getDisplayHeight()) return true;
        if (x < 10 || y < 10) return false;

        int[] dim = computeBoundsFrom(x, y);
        displayWidth = Math.max(1, dim[0]);
        displayHeight = Math.max(1, dim[1]);
        viewPort.set(0, 0, displayWidth, displayHeight);

        if (androidGraphics == null) {
            androidGraphics = new AndroidDisplayGraphics();
            androidGraphics.setDisplaySurface(this);
        }
        return true;
    }

    private int[] computeBoundsFrom(int vwidth, int vheight) {
        if (!layerManager.stayProportional()) return new int[]{vwidth, vheight};
        double ratio = getEnvHeight() / getEnvWidth();
        int[] dim = new int[2];
        if (ratio < 1) {
            dim[1] = Math.min(vheight, (int) Math.round(vwidth * ratio));
            dim[0] = Math.min(vwidth, (int) Math.round(dim[1] / ratio));
        } else {
            dim[0] = Math.min(vwidth, (int) Math.round(vheight / ratio));
            dim[1] = Math.min(vheight, (int) Math.round(dim[0] * ratio));
        }
        return dim;
    }

    public void dispose() {
        if (disposed) return;
        disposed = true;
        getData().removeListener(this);
        if (layerManager != null) layerManager.dispose();
        GAMA.releaseScope(getScope());
        setDisplayScope(null);
    }
}
