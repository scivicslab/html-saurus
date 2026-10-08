package com.scivicslab.htmlsaurus;

import com.microsoft.playwright.Browser;
import com.microsoft.playwright.BrowserType;
import com.microsoft.playwright.Page;
import com.microsoft.playwright.Playwright;

/**
 * Asks a real browser whether it can get an icon for these pages, against a running deployment.
 *
 * <p>A browser has two ways to find one: the {@code <link rel="icon">} a page states inline as a
 * {@code data:} URL, and {@code /favicon.ico} at the origin, which it asks for when it cannot use
 * the statement. Serving neither leaves the tab blank, and the two failures look identical from
 * outside — so both are checked here. That the declared bytes are well formed is checked without a
 * browser by {@code FaviconTest}; what only a browser can answer is whether it will actually
 * decode them, which is what this program asks.</p>
 *
 * <p>A {@code main()} program rather than a JUnit test, per {@code TestingStandard_260404_oo01}:
 * an E2E test drives an environment that is already running and is not the build's business.</p>
 *
 * <pre>
 * mvn test-compile
 * java -cp target/test-classes:target/classes:$(cat cp.txt) \
 *      com.scivicslab.htmlsaurus.FaviconE2E [baseUrl] [docPagePath]
 * </pre>
 */
public class FaviconE2E {

    private static int passed = 0;
    private static int failed = 0;

    /**
     * @param args optional base URL (default {@code http://localhost:28001}) and a document page
     *             path to check alongside the portal page
     */
    public static void main(String[] args) {
        String base = args.length > 0 ? args[0].replaceAll("/+$", "") : "http://localhost:28001";
        String docPath = args.length > 1 ? args[1] : null;

        System.out.println("=== Favicon E2E: " + base + " ===");
        try (Playwright playwright = Playwright.create()) {
            Browser browser = playwright.chromium().launch(
                    new BrowserType.LaunchOptions().setHeadless(true));
            Page page = browser.newContext().newPage();

            page.navigate(base + "/");
            checkDeclaredIcon(page, "portal page");
            checkFaviconIco(page);

            if (docPath != null) {
                page.navigate(base + "/" + docPath.replaceAll("^/+", ""));
                checkDeclaredIcon(page, "document page " + docPath);
            } else {
                System.out.println("SKIP: no document page given; pass one as the second argument");
            }
            browser.close();
        }

        System.out.println("Results: " + passed + " passed, " + failed + " failed");
        if (failed > 0) {
            System.exit(1);
        }
    }

    /** Hands the page's declared icon URL to the browser's own image decoder. */
    private static void checkDeclaredIcon(Page page, String where) {
        int links = page.locator("link[rel~=\"icon\"]").count();
        if (links != 1) {
            fail(where + ": states " + links + " icons, expected exactly 1");
            return;
        }
        Object result = page.evaluate(
                "async () => {"
              + "  const link = document.querySelector('link[rel~=\"icon\"]');"
              + "  const href = link.getAttribute('href');"
              + "  if (!href || href === 'data:,') return 'empty href: ' + href;"
              + "  return await new Promise(res => {"
              + "    const im = new Image();"
              + "    im.onload = () => res('ok ' + im.naturalWidth + 'x' + im.naturalHeight);"
              + "    im.onerror = () => res('the browser could not decode it');"
              + "    im.src = href;"
              + "  });"
              + "}");
        if (String.valueOf(result).startsWith("ok ")) {
            pass(where + ": declared icon decodes (" + result + ")");
        } else {
            fail(where + ": " + result);
        }
    }

    /** Fetches {@code /favicon.ico} from the page, the route a browser falls back to. */
    private static void checkFaviconIco(Page page) {
        Object result = page.evaluate(
                "async () => {"
              + "  const r = await fetch('/favicon.ico');"
              + "  if (!r.ok) return 'HTTP ' + r.status;"
              + "  const type = r.headers.get('content-type') || '(none)';"
              + "  const bytes = new Uint8Array(await r.arrayBuffer());"
              + "  if (!type.startsWith('image/')) return 'served as ' + type;"
              + "  if (bytes.length === 0) return 'served empty';"
              + "  const png = bytes[0] === 0x89 && bytes[1] === 0x50;"
              + "  const ico = bytes[0] === 0x00 && bytes[1] === 0x00 && bytes[2] === 0x01;"
              + "  if (!png && !ico) return 'neither PNG nor ICO bytes';"
              + "  return 'ok ' + type + ' ' + bytes.length + ' bytes';"
              + "}");
        if (String.valueOf(result).startsWith("ok ")) {
            pass("/favicon.ico: " + result);
        } else {
            fail("/favicon.ico: " + result);
        }
    }

    private static void pass(String what) {
        passed++;
        System.out.println("PASS: " + what);
    }

    private static void fail(String what) {
        failed++;
        System.out.println("FAIL: " + what);
    }
}
