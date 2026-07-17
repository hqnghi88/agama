package com.gama.nativeapp;

import java.util.ArrayList;
import java.util.List;

public class ModelTreeItem {

    public enum Type { CATEGORY, MODEL_FILE }

    private final String name;
    private final String fullPath;
    private final Type type;
    private int depth;
    private ModelTreeItem parent;
    private final List<ModelTreeItem> children = new ArrayList<>();
    private boolean expanded;

    public ModelTreeItem(String name, String fullPath, Type type, int depth, ModelTreeItem parent) {
        this.name = name;
        this.fullPath = fullPath;
        this.type = type;
        this.depth = depth;
        this.parent = parent;
    }

    public String getName() { return name; }
    public String getFullPath() { return fullPath; }
    public Type getType() { return type; }
    public int getDepth() { return depth; }
    public void setDepth(int depth) { this.depth = depth; }
    public ModelTreeItem getParent() { return parent; }
    public void setParent(ModelTreeItem parent) { this.parent = parent; }
    public List<ModelTreeItem> getChildren() { return children; }
    public boolean isExpanded() { return expanded; }
    public void setExpanded(boolean expanded) { this.expanded = expanded; }
    public boolean isDirectory() { return type == Type.CATEGORY; }
}
