package org.eclipse.core.runtime;

public interface IPath {
    IPath append(String child);
    IPath removeLastSegments(int count);
    IPath makeRelative();
    IPath makeAbsolute();
    IPath removeFileExtension();
    IPath getFileExtension();
    IPath removeTrailingSeparator();
    IPath addTrailingSeparator();
    IPath segment(int index);
    int segmentCount();
    boolean isAbsolute();
    boolean isEmpty();
    boolean isRoot();
    boolean hasTrailingSeparator();
    String toString();
    String toOSString();
    String toFileString();
    String lastSegment();
    IPath upLevels(int count);
    IPath prefix(IPath base);
    String[] segments();
    boolean isPrefixOf(IPath another);
    boolean isPrefixOf(String another);
    IPath setDevice(String device);
    IPath addFileExtension(String extension);
}
