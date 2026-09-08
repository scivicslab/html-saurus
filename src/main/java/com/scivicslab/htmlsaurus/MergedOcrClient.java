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
 * position: the regions Marker classified as {@code Equation} take Marker's LaTeX, everything
 * else takes YomiToku's Japanese text — see {@code MixedJapaneseMathOcr_260907_oo01}.
 *
 * <p>Neither engine alone can read a Japanese book with mathematics: YomiToku reads the prose
 * correctly but garbles every formula (it has no math support), Marker reads the formulas as
 * clean LaTeX but garbles the Japanese prose. Which output to take is decided by where a block
 * sits on the page, never by what its text looks like — Marker's {@code Equation} bounding boxes
 * (PDF points) are scaled by {@code dpi/72} into the pixel coordinate system of YomiToku's
 * paragraph boxes, and every YomiToku paragraph that an {@code Equation} rectangle mostly covers
 * is replaced by that equation's LaTeX.</p>
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

    /** One Marker {@code Equation} block: its bounding box in PDF points, and its content already
     *  converted to Markdown (math as {@code $$...$$}, kept together with any trailing plain text
     *  such as an equation number). */
    record MarkerEquation(double x1, double y1, double x2, double y2, String markdown) {}

    /** A YomiToku paragraph counts as covered by an Equation rectangle when at least this share
     *  of the paragraph's area lies inside it. The rectangles come from two different layout
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
        List<MarkerEquation> equations = parseMarkerEquations(markerCall.call(onePagePdfBytes));
        return new Result(merge(yomi, equations), Map.of());
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
     *  Markdown string. */
    static GpuBrokerOcrClient.MultipartRequest buildMarkerJsonRequest(byte[] onePagePdfBytes)
            throws IOException {
        String boundary = "----htmlsaurus" + System.nanoTime();
        var fields = new java.util.LinkedHashMap<String, String>();
        fields.put("page_range", "0");
        fields.put("output_format", "json");
        byte[] body = HttpUtils.buildMultipart(boundary, fields, "file", "page.pdf", onePagePdfBytes);
        return new GpuBrokerOcrClient.MultipartRequest(body, "multipart/form-data; boundary=" + boundary);
    }

    /**
     * The {@code Equation} blocks of a Marker {@code output_format=json} response, in reading
     * order. The response's {@code output} is a JSON string holding a {@code Document} whose
     * {@code Page} children each carry {@code block_type}, {@code bbox} (PDF points) and
     * {@code html}; only single pages are ever sent, so the first page is the whole answer.
     */
    @SuppressWarnings("unchecked")
    static List<MarkerEquation> parseMarkerEquations(String responseBody) {
        Map<String, Object> root = McpJsonParser.parseObject(responseBody);
        Object output = root.get("output");
        if (output == null) return List.of();
        Object tree = output instanceof String s ? McpJsonParser.parse(s) : output;
        if (!(tree instanceof Map<?, ?> doc)) return List.of();

        List<Map<String, Object>> pages = new ArrayList<>();
        if ("Document".equals(doc.get("block_type")) && doc.get("children") instanceof List<?> kids) {
            for (Object k : kids) {
                if (k instanceof Map<?, ?> km) pages.add((Map<String, Object>) km);
            }
        } else {
            pages.add((Map<String, Object>) doc);
        }

        List<MarkerEquation> equations = new ArrayList<>();
        for (Map<String, Object> page : pages) {
            if (!(page.get("children") instanceof List<?> children)) continue;
            for (Object child : children) {
                if (!(child instanceof Map<?, ?> block)) continue;
                if (!"Equation".equals(block.get("block_type"))) continue;
                Object bbox = block.get("bbox");
                Object html = block.get("html");
                if (!(bbox instanceof List<?> b) || b.size() != 4 || html == null) continue;
                String markdown = equationHtmlToMarkdown(html.toString());
                if (markdown.isBlank()) continue;
                equations.add(new MarkerEquation(
                        toDouble(b.get(0)), toDouble(b.get(1)), toDouble(b.get(2)), toDouble(b.get(3)),
                        markdown));
            }
        }
        return equations;
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
     * Marker {@code Equation} rectangle mostly covers replaced by that equation's Markdown. An
     * equation that covers several paragraphs replaces them all at the first one's position; an
     * equation that covers none (YomiToku read nothing there at all) is inserted before the first
     * paragraph that starts below it.
     */
    static List<String> merge(YomiTokuBlocks yomi, List<MarkerEquation> equations) {
        double scale = yomi.dpi() / 72.0;
        List<YomiTokuBlock> paragraphs = yomi.blocks();

        // For each equation: the indices of the paragraphs it covers.
        List<Set<Integer>> covered = new ArrayList<>();
        for (MarkerEquation eq : equations) {
            double ex1 = eq.x1() * scale, ey1 = eq.y1() * scale;
            double ex2 = eq.x2() * scale, ey2 = eq.y2() * scale;
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

        // Which equation (if any) is anchored at each paragraph index, and which paragraph
        // indices disappear into an equation.
        String[] anchored = new String[paragraphs.size()];
        Set<Integer> replaced = new LinkedHashSet<>();
        List<MarkerEquation> unanchored = new ArrayList<>();
        for (int e = 0; e < equations.size(); e++) {
            Set<Integer> hits = covered.get(e);
            if (hits.isEmpty()) {
                unanchored.add(equations.get(e));
                continue;
            }
            int first = hits.iterator().next();
            anchored[first] = anchored[first] == null ? equations.get(e).markdown()
                    : anchored[first] + "\n" + equations.get(e).markdown();
            replaced.addAll(hits);
        }

        List<String> out = new ArrayList<>();
        for (int i = 0; i < paragraphs.size(); i++) {
            for (var it = unanchored.iterator(); it.hasNext(); ) {
                MarkerEquation eq = it.next();
                if (eq.y1() * scale < paragraphs.get(i).y1()) {
                    out.add(eq.markdown());
                    it.remove();
                }
            }
            if (anchored[i] != null) {
                out.add(anchored[i]);
            } else if (!replaced.contains(i)) {
                out.add(paragraphs.get(i).contents());
            }
        }
        for (MarkerEquation eq : unanchored) {
            out.add(eq.markdown());
        }
        return out;
    }
}
