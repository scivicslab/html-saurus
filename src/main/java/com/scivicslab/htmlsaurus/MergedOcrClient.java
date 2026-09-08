package com.scivicslab.htmlsaurus;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import com.scivicslab.gpubroker.client.GpuBrokerClient;

/**
 * OCR client that reads one page with BOTH YomiToku and Marker and merges the two by page
 * position: the blocks where Marker read mathematics take Marker's LaTeX, everything else takes
 * YomiToku's Japanese text — see {@code MixedJapaneseMathOcr_260907_oo01} and
 * {@code MergedOcrBackend_260909_oo01}.
 *
 * <p>Marker is called with {@code force_ocr=true}. A scanned book often carries the text layer
 * its scanner's own OCR embedded at scan time, and without the flag Marker reads that layer
 * instead of the page image — the garbled Japanese the design document first attributed to
 * Marker was, measured again, that embedded layer's text verbatim. With the flag, Marker reads
 * the image itself: display equations become {@code Equation} blocks, formulas inside a sentence
 * become {@code <math display="inline">} elements inside {@code Text}/{@code ListItem} blocks,
 * and both are taken from Marker. YomiToku still reads the pure prose better (and reads
 * multi-column layouts in the right order), so paragraphs without mathematics stay YomiToku's.</p>
 *
 * <p>Which output to take is decided by where a block sits on the page, never by what its text
 * looks like — Marker's bounding boxes (PDF points) are scaled by {@code dpi/72} into the pixel
 * coordinate system of YomiToku's paragraph boxes, and every YomiToku paragraph that a
 * math-bearing Marker block mostly covers is replaced by that block's Markdown.</p>
 *
 * <p>Both backends are called through the same two request shapes the single-engine clients send
 * ({@link YomiTokuOcrClient#buildRequest} to {@code /ocr/markdown}, whose response now also
 * carries {@code blocks}+{@code dpi}, and a Marker request with {@code output_format=json}), so
 * this client works identically through {@code quarkus-gpu-broker} — the broker's fixed
 * per-queue request paths are exactly the ones used here.</p>
 *
 * <p>Tables are out of scope (unverified on table pages), and no images are returned — same as
 * the plain YomiToku backend today. See the design document for both decisions.</p>
 */
class MergedOcrClient implements OcrClient {

    /** One backend call: sends one single-page PDF, returns the raw response body. Bound at
     *  construction to either a direct HTTP call or a gpu-broker submission. */
    @FunctionalInterface
    interface RawPageCall {
        String call(byte[] onePagePdfBytes) throws IOException, InterruptedException;
    }

    /** One YomiToku paragraph with its layout metadata, from the {@code blocks} response field.
     *  {@code box} is [x1, y1, x2, y2] in pixels of the page image rendered at the response's
     *  {@code dpi}. */
    record YomiTokuBlock(String contents, double x1, double y1, double x2, double y2, String role) {}

    /** YomiToku's whole answer for one page: its paragraphs with coordinates, and the DPI those
     *  pixel coordinates are relative to. */
    record YomiTokuBlocks(List<YomiTokuBlock> blocks, double dpi) {}

    /** One math-bearing Marker block — an {@code Equation}, or a {@code Text}/{@code ListItem}
     *  with {@code <math>} inside — as its bounding box in PDF points and its content already
     *  converted to Markdown (display math as {@code $$...$$} lines, inline math as
     *  {@code $...$} within the surrounding text, other markup dropped). */
    record MarkerBlock(double x1, double y1, double x2, double y2, String markdown) {}

    /** A YomiToku paragraph counts as covered by a Marker block's rectangle when at least this
     *  share of the paragraph's area lies inside it. The rectangles come from two different layout
     *  analyses of the same scan, so they never agree exactly; the design document's own measured
     *  example overlaps at 0.83. */
    static final double COVERAGE_THRESHOLD = 0.5;

    private final RawPageCall yomiTokuCall;
    private final RawPageCall markerCall;

    MergedOcrClient(RawPageCall yomiTokuCall, RawPageCall markerCall) {
        this.yomiTokuCall = yomiTokuCall;
        this.markerCall = markerCall;
    }

    /** The direct-HTTP route, used when {@code GPU_BROKER_URL} is not set — same fixed-node
     *  convention as the single-engine clients. */
    static MergedOcrClient direct(String yomiTokuBaseUrl, String markerBaseUrl) {
        HttpClient http = HttpClient.newBuilder()
                .version(HttpClient.Version.HTTP_1_1)
                .connectTimeout(Duration.ofSeconds(10))
                .build();
        String yomiUrl = normalizeBaseUrl(yomiTokuBaseUrl, YomiTokuOcrClient.DEFAULT_BASE_URL)
                + "/ocr/markdown";
        String markerUrl = normalizeBaseUrl(markerBaseUrl, MarkerOcrClient.DEFAULT_BASE_URL)
                + "/marker/upload";
        return new MergedOcrClient(
                bytes -> postMultipart(http, yomiUrl, YomiTokuOcrClient.buildRequest(bytes), "YomiToku"),
                bytes -> postMultipart(http, markerUrl, buildMarkerJsonRequest(bytes), "Marker"));
    }

    /** The gpu-broker route, used when {@code GPU_BROKER_URL} is set: the same two queues the
     *  single-engine backends submit to, one submission each per page. */
    static MergedOcrClient viaGpuBroker(GpuBrokerClient client) {
        return new MergedOcrClient(
                bytes -> GpuBrokerOcrClient.submitRaw(client, "yomitoku-ocr", "yomitoku",
                        YomiTokuOcrClient.buildRequest(bytes)),
                bytes -> GpuBrokerOcrClient.submitRaw(client, "marker-ocr", "marker",
                        buildMarkerJsonRequest(bytes)));
    }

    private static String normalizeBaseUrl(String baseUrl, String defaultUrl) {
        return baseUrl == null || baseUrl.isBlank() ? defaultUrl : baseUrl.replaceAll("/+$", "");
    }

    private static String postMultipart(HttpClient http, String url,
                                         GpuBrokerOcrClient.MultipartRequest req, String backendName)
            throws IOException, InterruptedException {
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(url))
                .header("Content-Type", req.contentType())
                .POST(HttpRequest.BodyPublishers.ofByteArray(req.body()))
                .timeout(Duration.ofSeconds(120))
                .build();
        HttpResponse<String> response = http.send(request, HttpResponse.BodyHandlers.ofString());
        if (response.statusCode() != 200) {
            throw new IOException(backendName + " OCR failed with status " + response.statusCode());
        }
        return response.body();
    }

    @Override
    public String backendId() {
        return "yomitoku-marker";
    }

    @Override
    public Result ocrPage(byte[] onePagePdfBytes) throws IOException, InterruptedException {
        YomiTokuBlocks yomi = parseYomiTokuBlocks(yomiTokuCall.call(onePagePdfBytes));
        List<MarkerBlock> mathBlocks = parseMarkerMathBlocks(markerCall.call(onePagePdfBytes));
        return new Result(merge(yomi, mathBlocks), Map.of());
    }

    /**
     * Parses YomiToku's {@code blocks}+{@code dpi} response fields. Fails loudly when they are
     * absent: that means the yomitoku-ocr-server deployment predates the fields, and silently
     * degrading to a text-only merge would produce exactly the broken-formula pages this client
     * exists to prevent.
     */
    static YomiTokuBlocks parseYomiTokuBlocks(String responseBody) throws IOException {
        Map<String, Object> root = McpJsonParser.parseObject(responseBody);
        Object blocksField = root.get("blocks");
        Number dpi = McpJsonParser.getNumber(root, "dpi");
        if (!(blocksField instanceof List<?> list) || dpi == null) {
            throw new IOException("YomiToku response has no blocks/dpi fields - "
                    + "the yomitoku-ocr-server deployment is too old for the merged backend");
        }
        List<YomiTokuBlock> blocks = new ArrayList<>();
        for (Object item : list) {
            if (!(item instanceof Map<?, ?> m)) continue;
            Object contents = m.get("contents");
            Object box = m.get("box");
            if (contents == null || !(box instanceof List<?> b) || b.size() != 4) continue;
            Object role = m.get("role");
            blocks.add(new YomiTokuBlock(contents.toString(),
                    toDouble(b.get(0)), toDouble(b.get(1)), toDouble(b.get(2)), toDouble(b.get(3)),
                    role == null ? null : role.toString()));
        }
        return new YomiTokuBlocks(blocks, dpi.doubleValue());
    }

    private static double toDouble(Object o) {
        return o instanceof Number n ? n.doubleValue() : Double.parseDouble(o.toString());
    }

    /** Builds the Marker request this client sends: same multipart shape as
     *  {@link MarkerOcrClient#buildRequest} but with {@code output_format=json}, which returns
     *  the page as a block tree with a type and a bounding box per block instead of one
     *  Markdown string, and {@code force_ocr=true}, which makes Marker read the page image
     *  instead of any text layer the scanner's own OCR embedded at scan time — without it, a
     *  scanned book answers with that (old, garbled) layer and no inline math at all. */
    static GpuBrokerOcrClient.MultipartRequest buildMarkerJsonRequest(byte[] onePagePdfBytes)
            throws IOException {
        String boundary = "----htmlsaurus" + System.nanoTime();
        var fields = new java.util.LinkedHashMap<String, String>();
        fields.put("page_range", "0");
        fields.put("output_format", "json");
        fields.put("force_ocr", "true");
        byte[] body = HttpUtils.buildMultipart(boundary, fields, "file", "page.pdf", onePagePdfBytes);
        return new GpuBrokerOcrClient.MultipartRequest(body, "multipart/form-data; boundary=" + boundary);
    }

    /**
     * The math-bearing blocks of a Marker {@code output_format=json} response, in reading order:
     * every {@code Equation} block, and every other block (a {@code Text}, a {@code ListItem})
     * whose {@code html} contains a {@code <math>} element. The tree is walked depth-first; a
     * collected block's children are not descended into, because its {@code html} already carries
     * the nested content (a {@code ListItem} holds its sub-items' text), and collecting a child
     * again would duplicate it. The response's {@code output} is a JSON string holding a
     * {@code Document} whose {@code Page} children carry {@code block_type}, {@code bbox}
     * (PDF points) and {@code html}; only single pages are ever sent.
     */
    static List<MarkerBlock> parseMarkerMathBlocks(String responseBody) {
        Map<String, Object> root = McpJsonParser.parseObject(responseBody);
        Object output = root.get("output");
        if (output == null) return List.of();
        Object tree = output instanceof String s ? McpJsonParser.parse(s) : output;
        if (!(tree instanceof Map<?, ?> doc)) return List.of();

        List<MarkerBlock> mathBlocks = new ArrayList<>();
        collectMathBlocks(doc, mathBlocks);
        return mathBlocks;
    }

    /** Depth-first collection for {@link #parseMarkerMathBlocks}: collect and stop, or descend. */
    private static void collectMathBlocks(Map<?, ?> block, List<MarkerBlock> out) {
        Object type = block.get("block_type");
        Object bbox = block.get("bbox");
        Object html = block.get("html");
        boolean container = "Document".equals(type) || "Page".equals(type) || "ListGroup".equals(type);
        if (!container && bbox instanceof List<?> b && b.size() == 4 && html != null
                && ("Equation".equals(type) || html.toString().contains("<math"))) {
            String markdown = equationHtmlToMarkdown(html.toString());
            if (!markdown.isBlank() && !hasDegenerateRepetition(markdown)) {
                out.add(new MarkerBlock(
                        toDouble(b.get(0)), toDouble(b.get(1)), toDouble(b.get(2)), toDouble(b.get(3)),
                        markdown));
            }
            return;
        }
        if (block.get("children") instanceof List<?> children) {
            for (Object child : children) {
                if (child instanceof Map<?, ?> m) collectMathBlocks(m, out);
            }
        }
    }

    private static final Pattern DEGENERATE_REPETITION = Pattern.compile("(\\S{1,3})( \\1){4,}");

    /**
     * Whether a converted block shows the repetition failure OCR models fall into on a line they
     * cannot read — the same short token emitted over and over ({@code で で で で で …}, observed
     * live on a real page). Such a block is not collected: the YomiToku paragraphs under it stay,
     * which loses that block's inline LaTeX but keeps the sentence a human can read.
     */
    static boolean hasDegenerateRepetition(String markdown) {
        return DEGENERATE_REPETITION.matcher(markdown).find();
    }

    private static final Pattern MATH_ELEMENT = Pattern.compile(
            "<math(\\s[^>]*)?>(.*?)</math>", Pattern.DOTALL);
    private static final Pattern ANY_TAG = Pattern.compile("<[^>]+>");

    /**
     * One Equation block's {@code html} as Markdown. Each {@code <math display="block">} element
     * becomes a {@code $$...$$} block on a line of its own ({@code display="inline"} becomes
     * {@code $...$}); text outside the math elements is kept in place, attached to the block it
     * follows — Marker leaves equation numbers such as {@code (2)}, and sometimes a short prose
     * line it lumped into the Equation region, there as plain text. All other tags are dropped.
     */
    static String equationHtmlToMarkdown(String html) {
        List<String> parts = new ArrayList<>();
        Matcher m = MATH_ELEMENT.matcher(html);
        int at = 0;
        while (m.find()) {
            appendText(parts, stripTags(html.substring(at, m.start())));
            String attributes = m.group(1) == null ? "" : m.group(1);
            String latex = unescapeHtml(m.group(2)).strip();
            if (attributes.contains("display=\"inline\"")) {
                appendText(parts, "$" + latex + "$");
            } else {
                parts.add("$$" + latex + "$$");
            }
            at = m.end();
        }
        appendText(parts, stripTags(html.substring(at)));
        return String.join("\n", parts);
    }

    /** Attaches non-blank text to the previous part (an equation number stays on its equation's
     *  line), or starts the first part with it. */
    private static void appendText(List<String> parts, String text) {
        String stripped = text.strip();
        if (stripped.isEmpty()) return;
        if (parts.isEmpty()) {
            parts.add(stripped);
        } else {
            parts.set(parts.size() - 1, parts.get(parts.size() - 1) + " " + stripped);
        }
    }

    private static String stripTags(String html) {
        return unescapeHtml(ANY_TAG.matcher(html).replaceAll(""))
                .replace("\n", " ").replaceAll("\\s+", " ");
    }

    private static String unescapeHtml(String s) {
        return s.replace("&lt;", "<").replace("&gt;", ">").replace("&quot;", "\"")
                .replace("&#39;", "'").replace("&amp;", "&");
    }

    /**
     * Merges one page: YomiToku's paragraphs in their reading order, with every paragraph that a
     * math-bearing Marker block's rectangle mostly covers replaced by that block's Markdown. A
     * block that covers several paragraphs replaces them all at the first one's position; a
     * block that covers none (YomiToku read nothing there at all) is inserted before the first
     * paragraph that starts below it.
     */
    static List<String> merge(YomiTokuBlocks yomi, List<MarkerBlock> mathBlocks) {
        double scale = yomi.dpi() / 72.0;
        List<YomiTokuBlock> paragraphs = yomi.blocks();

        // For each math block: the indices of the paragraphs it covers.
        List<Set<Integer>> covered = new ArrayList<>();
        for (MarkerBlock mb : mathBlocks) {
            double ex1 = mb.x1() * scale, ey1 = mb.y1() * scale;
            double ex2 = mb.x2() * scale, ey2 = mb.y2() * scale;
            Set<Integer> hits = new LinkedHashSet<>();
            for (int i = 0; i < paragraphs.size(); i++) {
                YomiTokuBlock p = paragraphs.get(i);
                double ix = Math.max(0, Math.min(ex2, p.x2()) - Math.max(ex1, p.x1()));
                double iy = Math.max(0, Math.min(ey2, p.y2()) - Math.max(ey1, p.y1()));
                double paragraphArea = (p.x2() - p.x1()) * (p.y2() - p.y1());
                if (paragraphArea > 0 && ix * iy / paragraphArea >= COVERAGE_THRESHOLD) {
                    hits.add(i);
                }
            }
            covered.add(hits);
        }

        // Which math block (if any) is anchored at each paragraph index, and which paragraph
        // indices disappear into one.
        String[] anchored = new String[paragraphs.size()];
        Set<Integer> replaced = new LinkedHashSet<>();
        List<MarkerBlock> unanchored = new ArrayList<>();
        for (int e = 0; e < mathBlocks.size(); e++) {
            Set<Integer> hits = covered.get(e);
            if (hits.isEmpty()) {
                unanchored.add(mathBlocks.get(e));
                continue;
            }
            int first = hits.iterator().next();
            anchored[first] = anchored[first] == null ? mathBlocks.get(e).markdown()
                    : anchored[first] + "\n" + mathBlocks.get(e).markdown();
            replaced.addAll(hits);
        }

        List<String> out = new ArrayList<>();
        for (int i = 0; i < paragraphs.size(); i++) {
            for (var it = unanchored.iterator(); it.hasNext(); ) {
                MarkerBlock mb = it.next();
                if (mb.y1() * scale < paragraphs.get(i).y1()) {
                    out.add(mb.markdown());
                    it.remove();
                }
            }
            if (anchored[i] != null) {
                out.add(anchored[i]);
            } else if (!replaced.contains(i)) {
                out.add(paragraphs.get(i).contents());
            }
        }
        for (MarkerBlock mb : unanchored) {
            out.add(mb.markdown());
        }
        return out;
    }
}
