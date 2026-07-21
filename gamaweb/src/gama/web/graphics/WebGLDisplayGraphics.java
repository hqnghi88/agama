/*******************************************************************************************************
 *
 * WebGLDisplayGraphics.java, in gama.web, is part of the source code of the GAMA modeling and simulation platform
 * (v.2025-03).
 *
 * (c) 2007-2026 UMI 209 UMMISCO IRD/SU & Partners (IRIT, MIAT, ESPACE-DEV, CTU)
 *
 * Visit https://github.com/gama-platform/gama for license information and contacts.
 *
 ********************************************************************************************************/
package gama.web.graphics;

import java.awt.geom.Rectangle2D;
import java.awt.image.BufferedImage;
import java.util.ArrayList;
import java.util.List;

import org.locationtech.jts.geom.Coordinate;
import org.locationtech.jts.geom.Geometry;
import org.locationtech.jts.geom.GeometryCollection;
import org.locationtech.jts.geom.Lineal;
import org.locationtech.jts.geom.Puntal;

import gama.api.types.color.IColor;
import gama.api.types.geometry.IPoint;
import gama.api.types.matrix.IField;
import gama.api.ui.displays.IAsset;
import gama.api.ui.displays.IDisplaySurface;
import gama.api.ui.layers.IDrawingAttributes;
import gama.api.ui.layers.ILayer;
import gama.core.outputs.display.AbstractDisplayGraphics;
import gama.core.outputs.layers.OverlayLayer;
import gama.dev.DEBUG;

/**
 * An IGraphics implementation that captures all drawing commands as JSON objects
 * instead of rendering to a graphics context. The captured JSON can then be sent
 * to JavaScript for WebGL rendering.
 *
 * Each drawing command is serialized as a JSON object with:
 * - type: "shape", "string", "image", "chart", "field", "background"
 * - geometry: GeoJSON geometry (for shapes)
 * - color: fill color as hex
 * - borderColor: stroke color as hex
 * - lineWidth: stroke width
 * - alpha: transparency
 * - text: text content (for strings)
 * - position: {x, y} (for images, strings)
 * - size: {width, height} (for images)
 *
 * @author GAMA Team
 */
public class WebGLDisplayGraphics extends AbstractDisplayGraphics {

    /** The current frame's drawing commands. */
    private final List<String> drawingCommands = new ArrayList<>();

    /** Frame dimensions. */
    private int frameWidth;
    private int frameHeight;

    /** Whether we're in overlay mode. */
    private boolean inOverlay = false;

    /**
     * Creates a new WebGL display graphics context.
     * @param width the frame width
     * @param height the frame height
     */
    public WebGLDisplayGraphics(final int width, final int height) {
        this.frameWidth = width;
        this.frameHeight = height;
    }

    /**
     * Resizes the frame.
     * @param width new width
     * @param height new height
     */
    public void resize(final int width, final int height) {
        this.frameWidth = width;
        this.frameHeight = height;
    }

    /**
     * Begins a new frame by clearing all previous drawing commands.
     * @param bgColor the background color
     */
    public void beginFrame(final IColor bgColor) {
        drawingCommands.clear();
        drawingCommands.add(buildBackgroundJson(bgColor));
    }

    /**
     * Returns the complete JSON for the current frame.
     * @return JSON string containing all drawing commands
     */
    public String getFrameJson() {
        StringBuilder sb = new StringBuilder();
        sb.append("{\"width\":").append(frameWidth);
        sb.append(",\"height\":").append(frameHeight);
        sb.append(",\"frame\":").append(drawingCommands.size());
        sb.append(",\"commands\":[");
        for (int i = 0; i < drawingCommands.size(); i++) {
            if (i > 0) sb.append(",");
            sb.append(drawingCommands.get(i));
        }
        sb.append("]}");
        return sb.toString();
    }

    @Override
    public void setDisplaySurface(final IDisplaySurface surface) {
        this.surface = surface;
        this.data = surface.getData();
    }

    @Override
    public void dispose() {
        drawingCommands.clear();
        super.dispose();
    }

    @Override
    public boolean beginDrawingLayers() {
        return true;
    }

    @Override
    public void beginDrawingLayer(final ILayer layer) {
        super.beginDrawingLayer(layer);
    }

    @Override
    public void endDrawingLayer(final ILayer layer) {
        super.endDrawingLayer(layer);
    }

    @Override
    public void endDrawingLayers() {}

    @Override
    public void fillBackground(final IColor bgColor) {
        // Background is already added in beginFrame
    }

    @Override
    public Rectangle2D drawShape(final Geometry geometry, final IDrawingAttributes attributes) {
        if (geometry == null) return null;

        // Handle geometry collections recursively
        if (geometry instanceof GeometryCollection) {
            final Rectangle2D.Double result = new Rectangle2D.Double();
            gama.api.utils.geometry.GeometryUtils.applyToInnerGeometries(geometry,
                g -> { result.add(drawShape(g, attributes)); });
            return result;
        }

        boolean isLine = geometry instanceof Lineal || geometry instanceof Puntal;
        IColor fillColor = isLine ? null : attributes.getColor();
        IColor borderColor = isLine ? attributes.getColor() : attributes.getBorder();
        if (borderColor == null && attributes.isEmpty()) { borderColor = attributes.getColor(); }

        StringBuilder sb = new StringBuilder();
        sb.append("{\"type\":\"shape\"");
        sb.append(",\"geometry\":").append(geometryToGeoJson(geometry));
        if (fillColor != null) {
            sb.append(",\"color\":\"").append(colorToHex(fillColor)).append("\"");
        }
        if (borderColor != null) {
            sb.append(",\"borderColor\":\"").append(colorToHex(borderColor)).append("\"");
        }
        sb.append(",\"lineWidth\":").append(attributes.getLineWidth() != null ? attributes.getLineWidth() : (isLine ? 1 : 0));
        // Alpha/transparency is set at the IGraphics level via setAlpha(), not per-draw-call
        if (attributes.getDepth() != null) {
            sb.append(",\"depth\":").append(attributes.getDepth());
        }
        sb.append("}");

        drawingCommands.add(sb.toString());
        return geometry.getEnvelopeInternal();
    }

    @Override
    public Rectangle2D drawString(final String string, final IDrawingAttributes attributes) {
        if (string == null || string.isEmpty()) return null;

        double curX = attributes.getLocation() == null ? 0 : attributes.getLocation().getX();
        double curY = attributes.getLocation() == null ? 0 : attributes.getLocation().getY();

        StringBuilder sb = new StringBuilder();
        sb.append("{\"type\":\"string\"");
        sb.append(",\"text\":\"").append(escapeJson(string)).append("\"");
        sb.append(",\"position\":{").append("\"x\":").append(curX).append(",\"y\":").append(curY).append("}");
        sb.append(",\"color\":\"").append(colorToHex(attributes.getColor())).append("\"");
        if (attributes.getAngle() != null) {
            sb.append(",\"angle\":").append(attributes.getAngle());
        }
        if (attributes.getAnchor() != null) {
            sb.append(",\"anchor\":{").append("\"x\":").append(attributes.getAnchor().getX())
              .append(",\"y\":").append(attributes.getAnchor().getY()).append("}");
        }
        sb.append("}");

        drawingCommands.add(sb.toString());
        return new Rectangle2D.Double(curX, curY, string.length() * 10, 20);
    }

    @Override
    public Rectangle2D drawImage(final BufferedImage img, final IDrawingAttributes attributes) {
        double curX = attributes.getLocation() == null ? 0 : attributes.getLocation().getX();
        double curY = attributes.getLocation() == null ? 0 : attributes.getLocation().getY();
        double curW = attributes.getSize() == null ? img.getWidth() : attributes.getSize().getX();
        double curH = attributes.getSize() == null ? img.getHeight() : attributes.getSize().getY();

        StringBuilder sb = new StringBuilder();
        sb.append("{\"type\":\"image\"");
        sb.append(",\"position\":{").append("\"x\":").append(curX).append(",\"y\":").append(curY).append("}");
        sb.append(",\"size\":{").append("\"width\":").append(curW).append(",\"height\":").append(curH).append("}");
        if (attributes.getAngle() != null) {
            sb.append(",\"angle\":").append(attributes.getAngle());
        }
        // Note: actual image data would need to be base64 encoded and sent separately
        sb.append(",\"imageWidth\":").append(img.getWidth());
        sb.append(",\"imageHeight\":").append(img.getHeight());
        sb.append("}");

        drawingCommands.add(sb.toString());
        return new Rectangle2D.Double(curX, curY, curW, curH);
    }

    @Override
    public Rectangle2D drawChart(final BufferedImage chart) {
        if (chart == null) return null;

        StringBuilder sb = new StringBuilder();
        sb.append("{\"type\":\"chart\"");
        sb.append(",\"position\":{").append("\"x\":0,\"y\":0}");
        sb.append(",\"size\":{").append("\"width\":").append(chart.getWidth())
          .append(",\"height\":").append(chart.getHeight()).append("}");
        sb.append("}");

        drawingCommands.add(sb.toString());
        return new Rectangle2D.Double(0, 0, chart.getWidth(), chart.getHeight());
    }

    @Override
    public Rectangle2D drawField(final IField values, final IDrawingAttributes attributes) {
        if (values == null) return null;

        StringBuilder sb = new StringBuilder();
        sb.append("{\"type\":\"field\"");
        sb.append(",\"cols\":").append(values.getNumCols());
        sb.append(",\"rows\":").append(values.getNumRows());
        if (attributes.getLocation() != null) {
            sb.append(",\"position\":{").append("\"x\":").append(attributes.getLocation().getX())
              .append(",\"y\":").append(attributes.getLocation().getY()).append("}");
        }
        if (attributes.getSize() != null) {
            sb.append(",\"size\":{").append("\"width\":").append(attributes.getSize().getX())
              .append(",\"height\":").append(attributes.getSize().getY()).append("}");
        }
        sb.append("}");

        drawingCommands.add(sb.toString());
        return new Rectangle2D.Double(0, 0, values.getNumCols(), values.getNumRows());
    }

    @Override
    public Rectangle2D drawAsset(final IAsset asset, final IDrawingAttributes attributes) {
        // For now, treat assets as generic shapes
        return drawString("[asset]", attributes);
    }

    @Override
    public void beginOverlay(final ILayer layer) {
        inOverlay = true;
        if (layer instanceof OverlayLayer overlay) {
            StringBuilder sb = new StringBuilder();
            sb.append("{\"type\":\"overlay\"");
            sb.append(",\"position\":{").append("\"x\":").append(getXOffsetInPixels())
              .append(",\"y\":").append(getYOffsetInPixels()).append("}");
            sb.append(",\"size\":{").append("\"width\":").append(getLayerWidth())
              .append(",\"height\":").append(getLayerHeight()).append("}");
            if (overlay.getData().getBackgroundColor(surface.getScope()) != null) {
                sb.append(",\"backgroundColor\":\"")
                  .append(colorToHex(overlay.getData().getBackgroundColor(surface.getScope())))
                  .append("\"");
            }
            sb.append(",\"rounded\":").append(overlay.getData().isRounded());
            sb.append("}");
            drawingCommands.add(sb.toString());
        }
    }

    @Override
    public void endOverlay() {
        inOverlay = false;
    }

    @Override
    public void setAlpha(final double alpha) {
        super.setAlpha(alpha);
    }

    @Override
    public void beginHighlight() {
        super.beginHighlight();
    }

    @Override
    public void endHighlight() {
        super.endHighlight();
    }

    // --- Helper methods ---

    /**
     * Converts a JTS Geometry to GeoJSON format.
     */
    private String geometryToGeoJson(final Geometry geometry) {
        if (geometry == null) return "null";

        StringBuilder sb = new StringBuilder();
        String geomType = geometry.getGeometryType();

        switch (geomType) {
            case "Point" -> {
                Coordinate c = geometry.getCoordinate();
                sb.append("{\"type\":\"Point\",\"coordinates\":[").append(c.x).append(",").append(c.y);
                if (!Double.isNaN(c.z)) sb.append(",").append(c.z);
                sb.append("]}");
            }
            case "LineString" -> {
                sb.append("{\"type\":\"LineString\",\"coordinates\":[");
                Coordinate[] coords = geometry.getCoordinates();
                for (int i = 0; i < coords.length; i++) {
                    if (i > 0) sb.append(",");
                    sb.append("[").append(coords[i].x).append(",").append(coords[i].y).append("]");
                }
                sb.append("]}");
            }
            case "Polygon" -> {
                sb.append("{\"type\":\"Polygon\",\"coordinates\":[");
                for (int r = 0; r < geometry.getNumGeometries(); r++) {
                    if (r > 0) sb.append(",");
                    Coordinate[] ring = geometry.getGeometryN(r).getCoordinates();
                    sb.append("[");
                    for (int i = 0; i < ring.length; i++) {
                        if (i > 0) sb.append(",");
                        sb.append("[").append(ring[i].x).append(",").append(ring[i].y).append("]");
                    }
                    sb.append("]");
                }
                sb.append("]}");
            }
            case "MultiPoint" -> {
                sb.append("{\"type\":\"MultiPoint\",\"coordinates\":[");
                for (int i = 0; i < geometry.getNumGeometries(); i++) {
                    if (i > 0) sb.append(",");
                    Coordinate c = geometry.getGeometryN(i).getCoordinate();
                    sb.append("[").append(c.x).append(",").append(c.y).append("]");
                }
                sb.append("]}");
            }
            case "MultiLineString" -> {
                sb.append("{\"type\":\"MultiLineString\",\"coordinates\":[");
                for (int i = 0; i < geometry.getNumGeometries(); i++) {
                    if (i > 0) sb.append(",");
                    Coordinate[] coords = geometry.getGeometryN(i).getCoordinates();
                    sb.append("[");
                    for (int j = 0; j < coords.length; j++) {
                        if (j > 0) sb.append(",");
                        sb.append("[").append(coords[j].x).append(",").append(coords[j].y).append("]");
                    }
                    sb.append("]");
                }
                sb.append("]}");
            }
            case "MultiPolygon" -> {
                sb.append("{\"type\":\"MultiPolygon\",\"coordinates\":[");
                for (int i = 0; i < geometry.getNumGeometries(); i++) {
                    if (i > 0) sb.append(",");
                    Geometry poly = geometry.getGeometryN(i);
                    sb.append("[");
                    for (int r = 0; r < poly.getNumGeometries(); r++) {
                        if (r > 0) sb.append(",");
                        Coordinate[] ring = poly.getGeometryN(r).getCoordinates();
                        sb.append("[");
                        for (int j = 0; j < ring.length; j++) {
                            if (j > 0) sb.append(",");
                            sb.append("[").append(ring[j].x).append(",").append(ring[j].y).append("]");
                        }
                        sb.append("]");
                    }
                    sb.append("]");
                }
                sb.append("]}");
            }
            default -> {
                // Fallback: use envelope
                if (geometry.getEnvelopeInternal() != null) {
                    var env = geometry.getEnvelopeInternal();
                    sb.append("{\"type\":\"Envelope\",\"minX\":").append(env.getMinX())
                      .append(",\"minY\":").append(env.getMinY())
                      .append(",\"maxX\":").append(env.getMaxX())
                      .append(",\"maxY\":").append(env.getMaxY()).append("}");
                } else {
                    sb.append("null");
                }
            }
        }
        return sb.toString();
    }

    /**
     * Converts a GAMA color to a hex string.
     */
    private String colorToHex(final IColor color) {
        if (color == null) return "#000000";
        int r = color.getRed();
        int g = color.getGreen();
        int b = color.getBlue();
        int a = color.getAlpha();
        if (a < 255) {
            return String.format("#%02x%02x%02x%02x", r, g, b, a);
        }
        return String.format("#%02x%02x%02x", r, g, b);
    }

    /**
     * Builds a JSON background command.
     */
    private String buildBackgroundJson(final IColor bgColor) {
        return "{\"type\":\"background\",\"color\":\"" + colorToHex(bgColor) + "\"}";
    }

    /**
     * Escapes a string for JSON.
     */
    private String escapeJson(final String s) {
        if (s == null) return "";
        return s.replace("\\", "\\\\")
                .replace("\"", "\\\"")
                .replace("\n", "\\n")
                .replace("\r", "\\r")
                .replace("\t", "\\t");
    }

    @Override
    public void accumulateTemporaryEnvelope(final Rectangle2D env) {}

    @Override
    public Rectangle2D getAndWipeTemporaryEnvelope() { return null; }

}
