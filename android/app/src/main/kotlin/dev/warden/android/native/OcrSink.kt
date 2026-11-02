package dev.warden.android.native

/**
 * The OCR backend the Rust reader calls back into, on its own worker
 * thread (attached to the JVM there). One call carries all the regions of a
 * single scan, so the client can run them in parallel — the reader OCRs
 * fourteen regions per read, and ML Kit takes about a second each.
 *
 * Each image comes as RGBA bytes with its size; the answer is one JSON
 * array per input, holding that region's lines as `["text", [x, y, w, h]]`
 * with the box in the input's own pixels. An empty input (zero size) has no
 * lines.
 */
interface OcrSink {
    /** One scan's regions; see [MlKitOcrSink] for the parallel implementation. */
    fun recognizeBatch(images: Array<ByteArray>, widths: IntArray, heights: IntArray): String
}
