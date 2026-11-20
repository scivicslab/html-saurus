package com.scivicslab.htmlsaurus;

import com.microsoft.playwright.Browser;
import com.microsoft.playwright.BrowserType;
import com.microsoft.playwright.Frame;
import com.microsoft.playwright.Page;
import com.microsoft.playwright.Playwright;
import com.microsoft.playwright.options.WaitForSelectorState;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

/**
 * E2E test for the portal's Revise panel (RevisionPanel_261120_oo01): selects a paragraph in the right pane, takes it
 * in, asks the local LLM for proposals under the person's own instruction, takes the first proposal, makes the
 * revised text, writes it back, and checks the Markdown file and the record file.
 *
 * <p>Requires an already-running dev-mode portal over a <b>copy</b> of the works directory, since the test writes a
 * Markdown file, and a local LLM the portal can reach (its revision model, or {@code TRANSLATION_MODEL}).
 * <pre>
 *   TRANSLATION_MODEL=GLM-5.3-Flash-Uncensored java -jar html-saurus-portal-server-*-shaded.jar serve -d &lt;copy&gt; -p 18410
 *   PORTAL_URL=http://localhost:18410 DOC_PATH=/doc_x/.../Page/ PARA_KEY='a few words of the paragraph' \
 *   MD_FILE=&lt;copy&gt;/doc_x/docs/.../page.md WORKS=&lt;copy&gt; \
 *   java -cp &lt;a jar with Playwright&gt; RevisePanelE2E.java
 * </pre>
 * Prints one line per check and exits 1 when any fails.
 */
public class RevisePanelE2E {

    static int failures = 0;

    static void check(boolean ok, String what, String detail) {
        System.out.println((ok ? "PASS " : "FAIL ") + what + (ok ? "" : ": " + detail));
        if (!ok) failures++;
    }

    public static void main(String[] args) throws Exception {
        String portal = System.getenv("PORTAL_URL");
        String docPath = System.getenv("DOC_PATH");
        String key = System.getenv("PARA_KEY");
        Path md = Path.of(System.getenv("MD_FILE"));
        Path record = Path.of(System.getenv("WORKS")).resolve(".html-saurus/revisions.jsonl");
        String before = Files.readString(md, StandardCharsets.UTF_8);
        long recordsBefore = Files.exists(record) ? Files.readAllLines(record).size() : 0;

        try (Playwright pw = Playwright.create()) {
            Browser browser = pw.chromium().launch(new BrowserType.LaunchOptions().setHeadless(true));
            Page page = browser.newPage();
            page.setDefaultTimeout(30_000);
            // What the panel sent and got, and what the page logged, so a stuck step can be read afterwards.
            page.onRequest(r -> { if (r.url().contains("/api/revise/")) System.out.println("request  " + r.method() + " " + r.url()); });
            page.onResponse(r -> { if (r.url().contains("/api/revise/")) System.out.println("response " + r.status() + " " + r.url() + " " + r.headers()); });
            page.onRequestFinished(r -> { if (r.url().contains("/api/revise/")) System.out.println("finished " + r.url()); });
            page.onRequestFailed(r -> { if (r.url().contains("/api/revise/")) System.out.println("failed   " + r.url() + " " + r.failure()); });
            page.onConsoleMessage(m -> System.out.println("console  " + m.type() + " " + m.text()));
            page.navigate(portal + "/#" + docPath);
            page.waitForFunction("() => { const f = document.getElementById('doc-frame');"
                    + " return f && f.contentDocument && f.contentDocument.getElementById('view-format'); }");
            Frame frame = page.frame("doc-frame");

            check(page.locator("#pane-menu option").allInnerTexts().containsAll(
                    List.of("Projects", "Import", "Batch Job", "Export", "Revise")),
                    "the left pane's menu lists the five panels", page.locator("#pane-menu option").allInnerTexts().toString());

            Object selected = frame.evaluate("key => { const p = Array.from(document.querySelectorAll('p'))"
                    + ".find(e => e.textContent.includes(key)); if (!p) return false;"
                    + " const r = document.createRange(); r.selectNodeContents(p);"
                    + " const s = window.getSelection(); s.removeAllRanges(); s.addRange(r); return true; }", key);
            check(Boolean.TRUE.equals(selected), "the paragraph is selected in the right pane", String.valueOf(selected));

            page.selectOption("#pane-menu", "revise");
            page.click("#revise-take");
            page.waitForSelector("#revise-original", new Page.WaitForSelectorOptions().setState(WaitForSelectorState.VISIBLE));
            String original = page.textContent("#revise-original");
            check(original.contains(key), "the Markdown of the paragraph is taken in", original);
            String target = page.textContent("#revise-target");
            check(target.matches(".*lines \\d+-\\d+.*"), "the lines of the file are shown", target);

            page.selectOption("#revise-kind", "free");
            page.fill("#revise-free", "この段落のうち、短くできる文をすべて挙げ、短くする案を出してください。");
            // With ASK_CHAT_NODE and ASK_CHAT_TARGET, the proposals come from a chat-ui conversation instead.
            String chatNode = System.getenv("ASK_CHAT_NODE");
            if (chatNode != null && !chatNode.isBlank()) {
                page.selectOption("#revise-asker", "chat");
                // A locator, not waitForFunction with an argument: the portal's CSP refuses the eval that needs.
                page.locator("#revise-chat-node option[value='" + chatNode + "']").waitFor(
                        new com.microsoft.playwright.Locator.WaitForOptions().setState(WaitForSelectorState.ATTACHED));
                page.selectOption("#revise-chat-node", chatNode);
                page.fill("#revise-chat-target", System.getenv("ASK_CHAT_TARGET"));
            }
            page.click("#revise-propose");
            page.waitForSelector("#revise-proposals-card", new Page.WaitForSelectorOptions()
                    .setState(WaitForSelectorState.VISIBLE).setTimeout(300_000));
            int proposals = page.locator("#revise-proposals input[type=checkbox]").count();
            System.out.println("proposals: " + page.textContent("#revise-proposals"));
            check(proposals > 0, "the proposals are listed with check boxes", page.textContent("#revise-status"));
            String expected = System.getenv("EXPECT_PROPOSAL");
            if (expected != null && !expected.isBlank()) {
                check(page.textContent("#revise-proposals").contains(expected),
                        "the proposals came from the asked conversation", page.textContent("#revise-proposals"));
            }

            page.locator("#revise-proposals input[type=checkbox]").first().check();
            page.click("#revise-make");
            page.waitForSelector("#revise-diff-card", new Page.WaitForSelectorOptions()
                    .setState(WaitForSelectorState.VISIBLE).setTimeout(300_000));
            int changed = page.locator("#revise-diff .del, #revise-diff .add").count();
            System.out.println("diff: " + page.textContent("#revise-diff"));
            check(changed > 0, "the difference from the original is shown", page.textContent("#revise-diff"));
            String revised = page.inputValue("#revise-revised");

            page.click("#revise-write");
            // Read the status once a second. Waiting with waitForFunction here, the answer to the write never reached
            // the page in three runs, and the panel stayed at "Writing back..."; read this way, it arrives at once.
            String status = "";
            for (int i = 0; i < 120 && !"Written back.".equals(status); i++) {
                page.waitForTimeout(1000);
                status = page.textContent("#revise-status");
            }
            check("Written back.".equals(status), "the panel says the text was written back", status);
            String after = Files.readString(md, StandardCharsets.UTF_8);
            check(!after.equals(before), "the Markdown file changed", "unchanged");
            check(after.contains(revised.strip()), "the file holds the revised text", revised);
            check(after.replace(revised.strip(), "").length() < before.length(),
                    "only the taken-in lines were replaced", "lengths " + before.length() + " -> " + after.length());
            List<String> lines = Files.readAllLines(record);
            check(lines.size() == recordsBefore + 1 && lines.get(lines.size() - 1).contains("\"written\":true"),
                    "one record of the revision was added", lines.isEmpty() ? "none" : lines.get(lines.size() - 1));
            browser.close();
        }
        System.out.println(failures == 0 ? "ALL PASSED" : failures + " FAILED");
        System.exit(failures == 0 ? 0 : 1);
    }
}
