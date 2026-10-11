package editor.analysishost.web;

import editor.analysishost.AnalysisHost;
import org.graalvm.webimage.api.JS;
import org.graalvm.webimage.api.JSString;

import java.nio.file.Path;

/**
 * Entry point of the WebAssembly image: publishes the host to JavaScript as
 * `globalThis.kotlinAnalysisHost.call(operation, first, second)` and returns, leaving the image alive.
 */
public final class WebMain {
    private WebMain() {}

    /** Arguments come as JavaScript values; only untyped parameters match what a JavaScript caller passes. */
    @FunctionalInterface
    public interface Handler {
        Object handle(Object operation, Object first, Object second);
    }

    @JS.Coerce
    @JS("globalThis.kotlinAnalysisHost = { call: (operation, first, second) => handler(operation, first, second) };")
    private static native void install(Handler handler);

    private static String text(Object value) {
        return value instanceof JSString string ? string.asString() : String.valueOf(value);
    }

    public static void main(String[] args) {
        // The file system of the image is in memory, so the root is as good a place as any.
        AnalysisHost host = new AnalysisHost(Path.of("/kotlin"));
        install((operation, first, second) -> JSString.of(host.handle(text(operation), text(first), text(second))));
    }
}
