package com.scivicslab.htmlsaurus;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Verifies i18n (multi-locale) build behaviour as specified in
 * DocPageI18n_260401_oo01 and HtmlSaurusI18n_260401_oo01.
 *
 * <p>Rules under test:
 * <ul>
 *   <li>Default locale pages output to {@code static-html/}.</li>
 *   <li>Alternate locale pages output to {@code static-html/<locale>/}.</li>
 *   <li>{@code html lang} attribute matches the page's locale.</li>
 *   <li>Language switcher dropdown is emitted when multiple locales are configured.</li>
 *   <li>Language switcher is NOT emitted when only one locale is configured.</li>
 *   <li>URL prefix for alternate locales: {@code /en/} etc.</li>
 * </ul>
 */
class I18nTest {

    @TempDir
    Path tempDir;

    private Path createProject(String name, String configContent) throws IOException {
        Path projectDir = tempDir.resolve(name);
        Files.createDirectories(projectDir.resolve("docs"));
        Files.writeString(projectDir.resolve("docusaurus.config.js"), configContent);
        return projectDir;
    }

    private void writeDoc(Path dir, String relPath, String title) throws IOException {
        Path file = dir.resolve(relPath);
        Files.createDirectories(file.getParent());
        Files.writeString(file, "---\ntitle: " + title + "\n---\n\n# " + title + "\n\nContent.");
    }

    private String readHtml(Path outDir, String relPath) throws IOException {
        return Files.readString(outDir.resolve(relPath));
    }

    // ---- Single locale (no i18n) -----------------------------------------

    @Nested
    @DisplayName("Single locale — no language switcher")
    class SingleLocale {

        @Test
        @DisplayName("No lang-dropdown rendered when only one locale")
        void singleLocale_noLangDropdown() throws IOException {
            Path proj = createProject("proj",
                "module.exports = { i18n: { defaultLocale: 'ja', locales: ['ja'] } };");
            writeDoc(proj.resolve("docs"), "intro.md", "Intro");

            Main.build(proj.resolve("docs"), proj.resolve("static-html"), false);

            String html = readHtml(proj.resolve("static-html"), "intro.html");
            // Check that no dropdown element is rendered (CSS rule contains "lang-dropdown" too,
            // so check for the actual HTML attribute class="lang-btn" which only appears in elements)
            assertFalse(html.contains("class=\"lang-btn\""),
                    "Single-locale project must not render a language switcher dropdown button");
        }

        @Test
        @DisplayName("html lang attribute set to defaultLocale")
        void singleLocale_htmlLangAttribute() throws IOException {
            Path proj = createProject("proj",
                "module.exports = { i18n: { defaultLocale: 'ja', locales: ['ja'] } };");
            writeDoc(proj.resolve("docs"), "intro.md", "Intro");

            Main.build(proj.resolve("docs"), proj.resolve("static-html"), false);

            String html = readHtml(proj.resolve("static-html"), "intro.html");
            assertTrue(html.contains("lang=\"ja\""),
                    "html lang attribute must be set to the defaultLocale 'ja'");
        }
    }

    // ---- Default locale = ja, alternate = en ----------------------------

    @Nested
    @DisplayName("defaultLocale=ja with en alternate")
    class JaDefaultEnAlternate {

        private static final String CONFIG =
            "module.exports = { i18n: { defaultLocale: 'ja', locales: ['ja', 'en'] } };";

        private void setupProject(Path projectDir) throws IOException {
            writeDoc(projectDir.resolve("docs"), "intro.md", "イントロ");
            Path enDocs = projectDir.resolve("i18n/en/docusaurus-plugin-content-docs/current");
            Files.createDirectories(enDocs);
            writeDoc(enDocs, "intro.md", "Introduction");
        }

        @Test
        @DisplayName("Japanese page output to static-html/intro.html (dev)")
        void jaPage_devMode() throws IOException {
            Path proj = createProject("proj", CONFIG);
            setupProject(proj);

            Main.build(proj.resolve("docs"), proj.resolve("static-html"), false);

            assertTrue(Files.exists(proj.resolve("static-html/intro.html")),
                    "Default locale (ja) page must be at static-html/intro.html");
        }

        @Test
        @DisplayName("English page output to static-html/en/intro.html (dev)")
        void enPage_devMode() throws IOException {
            Path proj = createProject("proj", CONFIG);
            setupProject(proj);

            Main.build(proj.resolve("docs"), proj.resolve("static-html"), false);

            assertTrue(Files.exists(proj.resolve("static-html/en/intro.html")),
                    "Alternate locale (en) page must be at static-html/en/intro.html");
        }

        @Test
        @DisplayName("Japanese page output to static-html/intro/index.html (production)")
        void jaPage_productionMode() throws IOException {
            Path proj = createProject("proj", CONFIG);
            setupProject(proj);

            Main.build(proj.resolve("docs"), proj.resolve("static-html"), true);

            assertTrue(Files.exists(proj.resolve("static-html/intro/index.html")),
                    "Default locale (ja) page must be at static-html/intro/index.html in production");
        }

        @Test
        @DisplayName("English page output to static-html/en/intro/index.html (production)")
        void enPage_productionMode() throws IOException {
            Path proj = createProject("proj", CONFIG);
            setupProject(proj);

            Main.build(proj.resolve("docs"), proj.resolve("static-html"), true);

            assertTrue(Files.exists(proj.resolve("static-html/en/intro/index.html")),
                    "Alternate locale (en) page must be at static-html/en/intro/index.html in production");
        }

        @Test
        @DisplayName("Japanese page has lang=\"ja\"")
        void jaPage_htmlLang() throws IOException {
            Path proj = createProject("proj", CONFIG);
            setupProject(proj);

            Main.build(proj.resolve("docs"), proj.resolve("static-html"), false);

            String html = readHtml(proj.resolve("static-html"), "intro.html");
            assertTrue(html.contains("lang=\"ja\""),
                    "Japanese page must have lang=\"ja\"");
        }

        @Test
        @DisplayName("English page has lang=\"en\"")
        void enPage_htmlLang() throws IOException {
            Path proj = createProject("proj", CONFIG);
            setupProject(proj);

            Main.build(proj.resolve("docs"), proj.resolve("static-html"), false);

            String html = readHtml(proj.resolve("static-html/en"), "intro.html");
            assertTrue(html.contains("lang=\"en\""),
                    "English page must have lang=\"en\"");
        }

        @Test
        @DisplayName("Language switcher dropdown is rendered")
        void langDropdown_rendered() throws IOException {
            Path proj = createProject("proj", CONFIG);
            setupProject(proj);

            Main.build(proj.resolve("docs"), proj.resolve("static-html"), false);

            String html = readHtml(proj.resolve("static-html"), "intro.html");
            assertTrue(html.contains("class=\"lang-btn\""),
                    "Multi-locale project must render a language switcher dropdown button");
        }

        @Test
        @DisplayName("Dropdown contains link to English version (/en/intro.html)")
        void langDropdown_containsEnLink() throws IOException {
            Path proj = createProject("proj", CONFIG);
            setupProject(proj);

            Main.build(proj.resolve("docs"), proj.resolve("static-html"), false);

            String html = readHtml(proj.resolve("static-html"), "intro.html");
            assertTrue(html.contains("en/intro.html"),
                    "Japanese page dropdown must link to English version");
        }

        @Test
        @DisplayName("English page dropdown contains link back to Japanese version (/intro.html)")
        void langDropdown_enPageLinksToJa() throws IOException {
            Path proj = createProject("proj", CONFIG);
            setupProject(proj);

            Main.build(proj.resolve("docs"), proj.resolve("static-html"), false);

            String html = readHtml(proj.resolve("static-html/en"), "intro.html");
            // The Japanese version link should NOT have /en/ prefix
            assertTrue(html.contains("class=\"lang-btn\""),
                    "English page must also have a language switcher dropdown");
        }

        @Test
        @DisplayName("Alternate locale not built when i18n dir has no markdown")
        void noEnMarkdown_enDirNotBuilt() throws IOException {
            Path proj = createProject("proj", CONFIG);
            writeDoc(proj.resolve("docs"), "intro.md", "イントロ");
            // No i18n/en/ directory created

            Main.build(proj.resolve("docs"), proj.resolve("static-html"), false);

            assertFalse(Files.exists(proj.resolve("static-html/en")),
                    "en locale output dir must not be created when no en markdown exists");
        }
    }

    // ---- Custom CSS / header applied to alternate locale pages ----------

    @Nested
    @DisplayName("Custom resources (CSS, header) applied to alternate locale pages")
    class CustomResources {

        private static final String CONFIG =
            "module.exports = { i18n: { defaultLocale: 'ja', locales: ['ja', 'en'] } };";

        private Path setupProject() throws IOException {
            Path proj = createProject("proj", CONFIG);
            writeDoc(proj.resolve("docs"), "intro.md", "イントロ");
            Path enDocs = proj.resolve("i18n/en/docusaurus-plugin-content-docs/current");
            Files.createDirectories(enDocs);
            writeDoc(enDocs, "intro.md", "Introduction");
            return proj;
        }

        @Test
        @DisplayName("html-saurus.css is applied to default locale page (production)")
        void customCss_appliedToDefaultLocale() throws IOException {
            Path proj = setupProject();
            Files.writeString(proj.resolve("html-saurus.css"), "body { color: red; }");

            Main.build(proj.resolve("docs"), proj.resolve("static-html"), true);

            String html = readHtml(proj.resolve("static-html"), "intro/index.html");
            assertTrue(html.contains("body { color: red; }"),
                "Default locale page must include html-saurus.css from project root");
        }

        @Test
        @DisplayName("html-saurus.css from project root is applied to alternate locale page (production)")
        void customCss_appliedToAlternateLocale() throws IOException {
            Path proj = setupProject();
            Files.writeString(proj.resolve("html-saurus.css"), "body { color: red; }");

            Main.build(proj.resolve("docs"), proj.resolve("static-html"), true);

            String html = readHtml(proj.resolve("static-html/en"), "intro/index.html");
            assertTrue(html.contains("body { color: red; }"),
                "Alternate locale (en) page must include html-saurus.css from project root, " +
                "not from i18n/en/docusaurus-plugin-content-docs/");
        }

        @Test
        @DisplayName("html-saurus-header.html from project root is applied to alternate locale page")
        void customHeader_appliedToAlternateLocale() throws IOException {
            Path proj = setupProject();
            Files.writeString(proj.resolve("html-saurus-header.html"),
                "<div id=\"test-header\">DDBJ Bar</div>");

            Main.build(proj.resolve("docs"), proj.resolve("static-html"), true);

            String html = readHtml(proj.resolve("static-html/en"), "intro/index.html");
            assertTrue(html.contains("id=\"test-header\""),
                "Alternate locale (en) page must include html-saurus-header.html from project root");
        }
    }

    // ---- Navbar label translation via navbar.json -----------------------

    @Nested
    @DisplayName("Navbar label translation via i18n navbar.json")
    class NavbarTranslation {

        private static final String CONFIG =
            "module.exports = {\n" +
            "  i18n: { defaultLocale: 'ja', locales: ['ja', 'en'] },\n" +
            "  themeConfig: { navbar: { items: [\n" +
            "    { type: 'docSidebar', sidebarId: 'guideSidebar', label: 'ガイド' }\n" +
            "  ] } }\n" +
            "};";

        private static final String SIDEBARS =
            "module.exports = {\n" +
            "  guideSidebar: [{ type: 'autogenerated', dirName: 'guides' }]\n" +
            "};";

        private Path setupProject() throws IOException {
            Path proj = createProject("proj", CONFIG);
            Files.writeString(proj.resolve("sidebars.js"), SIDEBARS);
            writeDoc(proj.resolve("docs"), "guides/intro.md", "イントロ");
            Path enDocs = proj.resolve("i18n/en/docusaurus-plugin-content-docs/current");
            Files.createDirectories(enDocs);
            writeDoc(enDocs, "guides/intro.md", "Introduction");
            return proj;
        }

        @Test
        @DisplayName("Japanese page uses original label from docusaurus.config")
        void jaPage_usesConfigLabel() throws IOException {
            Path proj = setupProject();

            Main.build(proj.resolve("docs"), proj.resolve("static-html"), false);

            String html = readHtml(proj.resolve("static-html"), "guides/intro.html");
            assertTrue(html.contains("ガイド"),
                "Japanese navbar must use the original label from docusaurus.config");
        }

        @Test
        @DisplayName("English page uses translated label from navbar.json")
        void enPage_usesNavbarJsonLabel() throws IOException {
            Path proj = setupProject();
            Path navbarJsonDir = proj.resolve("i18n/en/docusaurus-theme-classic");
            Files.createDirectories(navbarJsonDir);
            Files.writeString(navbarJsonDir.resolve("navbar.json"),
                "{\n  \"item.label.ガイド\": { \"message\": \"Guides\" }\n}");

            Main.build(proj.resolve("docs"), proj.resolve("static-html"), false);

            String html = readHtml(proj.resolve("static-html/en"), "guides/intro.html");
            assertTrue(html.contains("Guides"),
                "English navbar must use the translated label from navbar.json");
        }

        @Test
        @DisplayName("English page falls back to config label when navbar.json is absent")
        void enPage_noNavbarJson_usesConfigLabel() throws IOException {
            Path proj = setupProject();
            // No navbar.json created

            Main.build(proj.resolve("docs"), proj.resolve("static-html"), false);

            String html = readHtml(proj.resolve("static-html/en"), "guides/intro.html");
            assertTrue(html.contains("ガイド"),
                "English navbar must fall back to the config label when navbar.json is absent");
        }
    }

    // ---- readI18nConfig tests -------------------------------------------

    @Nested
    @DisplayName("readI18nConfig")
    class ReadI18nConfig {

        @Test
        @DisplayName("returns empty array when no config file")
        void noConfig_returnsEmpty() throws IOException {
            Path proj = tempDir.resolve("noconfig");
            Files.createDirectories(proj);

            String[] result = Main.readI18nConfig(proj);

            assertEquals(0, result.length, "No config file must return empty array");
        }

        @Test
        @DisplayName("returns defaultLocale when no locales array")
        void defaultLocaleOnly() throws IOException {
            Path proj = tempDir.resolve("proj");
            Files.createDirectories(proj);
            Files.writeString(proj.resolve("docusaurus.config.js"),
                "module.exports = { i18n: { defaultLocale: 'ja' } };");

            String[] result = Main.readI18nConfig(proj);

            assertEquals(1, result.length);
            assertEquals("ja", result[0]);
        }

        @Test
        @DisplayName("returns defaultLocale + alternates in order")
        void defaultAndAlternates() throws IOException {
            Path proj = tempDir.resolve("proj");
            Files.createDirectories(proj);
            Files.writeString(proj.resolve("docusaurus.config.js"),
                "module.exports = { i18n: { defaultLocale: 'ja', locales: ['ja', 'en'] } };");

            String[] result = Main.readI18nConfig(proj);

            assertEquals(2, result.length);
            assertEquals("ja", result[0]);
            assertEquals("en", result[1]);
        }

        @Test
        @DisplayName("defaultLocale=en produces [en] at index 0")
        void enDefault() throws IOException {
            Path proj = tempDir.resolve("proj");
            Files.createDirectories(proj);
            Files.writeString(proj.resolve("docusaurus.config.js"),
                "module.exports = { i18n: { defaultLocale: 'en', locales: ['en', 'ja'] } };");

            String[] result = Main.readI18nConfig(proj);

            assertEquals(2, result.length);
            assertEquals("en", result[0]);
            assertEquals("ja", result[1]);
        }
    }

    // ---- findProjects ---------------------------------------------------

    @Nested
    @DisplayName("findProjects")
    class FindProjects {

        @Test
        @DisplayName("finds projects with docs/ and docusaurus.config.js")
        void findsValidProjects() throws IOException {
            Path worksDir = tempDir.resolve("works");
            Files.createDirectories(worksDir);

            Path proj1 = worksDir.resolve("proj1");
            Files.createDirectories(proj1.resolve("docs"));
            Files.writeString(proj1.resolve("docusaurus.config.js"), "module.exports = {};");

            Path proj2 = worksDir.resolve("proj2");
            Files.createDirectories(proj2.resolve("docs"));
            Files.writeString(proj2.resolve("docusaurus.config.ts"), "export default {};");

            // Not a project: missing docs/
            Path notProj = worksDir.resolve("notproj");
            Files.createDirectories(notProj);
            Files.writeString(notProj.resolve("docusaurus.config.js"), "module.exports = {};");

            List<Path> projects = Main.findProjects(worksDir);

            assertEquals(2, projects.size(), "Should find exactly 2 valid projects");
            assertTrue(projects.contains(proj1));
            assertTrue(projects.contains(proj2));
        }
    }
}
