package javax.imageio.event;

public interface IIOReadProgressListener extends java.util.EventListener {
    default void imageStarted(Object source, int imageIndex) {}
    default void imageProgress(Object source, float percentageDone) {}
    default void imageComplete(Object source) {}
    default void thumbnailStarted(Object source, int imageIndex, int thumbnailIndex) {}
    default void thumbnailProgress(Object source, float percentageDone) {}
    default void thumbnailComplete(Object source) {}
    default void readAborted(Object source) {}
}
