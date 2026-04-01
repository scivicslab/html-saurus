package com.scivicslab.htmlsaurus;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Verifies URL generation rules as specified in DocPageUrl_260401_oo01.
 *
 * <p>Rules under test:
 * <ul>
 *   <li>Numeric prefix is stripped from every segment of the path (directories AND filenames).</li>
 *   <li>Dev mode: {@code .html} suffix — e.g. {@code /project/page.html}</li>
 *   <li>Production mode: clean URL with trailing slash — e.g. {@code /project/page/},
 *       internally stored as {@code page/index.html}</li>
 * </ul>
 */
class UrlRuleTest {

    @TempDir
    Path tempDir;

    // ---- Helpers -------------------------------------------------------

    private Path createProject(String name) throws IOException {
        Path projectDir = tempDir.resolve(name);
        Files.createDirectories(projectDir.resolve("docs"));
        Files.writeString(projectDir.resolve("docusaurus.config.js"), "module.exports = {};");
        return projectDir;
    }

    private void writeDoc(Path docsDir, String relPath, String title) throws IOException {
        Path file = docsDir.resolve(relPath);
        Files.createDirectories(file.getParent());
        Files.writeString(file, "---\ntitle: " + title + "\n---\n\n# " + title + "\n\nContent.");
    }

    // ---- Example 1: Flat file structure --------------------------------

    @Nested
    @DisplayName("Example 1 - Flat file structure")
    class FlatFileStructure {

        @Nested
        @DisplayName("Dev mode (.html suffix)")
        class DevMode {

            @Test
            @DisplayName("docs/intro.md -> static-html/intro.html")
            void introMd_producesIntroHtml() throws IOException {
                Path proj = createProject("proj");
                Path docsDir = proj.resolve("docs");
                writeDoc(docsDir, "intro.md", "Introduction");

                Main.build(docsDir, proj.resolve("static-html"), false);

                assertTrue(Files.exists(proj.resolve("static-html/intro.html")),
                        "intro.md must produce static-html/intro.html in dev mode");
            }

            @Test
            @DisplayName("docs/010_terms_and_policies/user_account_issurance_criteria.md"
                    + " -> static-html/terms_and_policies/user_account_issurance_criteria.html")
            void numericPrefixDirStripped_devMode() throws IOException {
                Path proj = createProject("proj");
                Path docsDir = proj.resolve("docs");
                writeDoc(docsDir,
                        "010_terms_and_policies/user_account_issurance_criteria.md",
                        "User Account Issurance Criteria");

                Main.build(docsDir, proj.resolve("static-html"), false);

                Path expected = proj.resolve(
                        "static-html/terms_and_policies/user_account_issurance_criteria.html");
                assertTrue(Files.exists(expected),
                        "Numeric prefix of directory must be stripped; expected: " + expected);
            }

            @Test
            @DisplayName("numeric prefix is not present in wrong location (no 010_ prefix retained)")
            void numericPrefixNotRetained_devMode() throws IOException {
                Path proj = createProject("proj");
                Path docsDir = proj.resolve("docs");
                writeDoc(docsDir,
                        "010_terms_and_policies/user_account_issurance_criteria.md",
                        "User Account Issurance Criteria");

                Main.build(docsDir, proj.resolve("static-html"), false);

                Path unexpected = proj.resolve(
                        "static-html/010_terms_and_policies/user_account_issurance_criteria.html");
                assertFalse(Files.exists(unexpected),
                        "Numeric prefix must be stripped; file must not exist at: " + unexpected);
            }
        }

        @Nested
        @DisplayName("Production mode (clean URL / index.html)")
        class ProductionMode {

            @Test
            @DisplayName("docs/intro.md -> static-html/intro/index.html")
            void introMd_producesIndexHtml() throws IOException {
                Path proj = createProject("proj");
                Path docsDir = proj.resolve("docs");
                writeDoc(docsDir, "intro.md", "Introduction");

                Main.build(docsDir, proj.resolve("static-html"), true);

                assertTrue(Files.exists(proj.resolve("static-html/intro/index.html")),
                        "intro.md must produce static-html/intro/index.html in production mode");
            }

            @Test
            @DisplayName("docs/010_terms_and_policies/user_account_issurance_criteria.md"
                    + " -> static-html/terms_and_policies/user_account_issurance_criteria/index.html")
            void numericPrefixDirStripped_productionMode() throws IOException {
                Path proj = createProject("proj");
                Path docsDir = proj.resolve("docs");
                writeDoc(docsDir,
                        "010_terms_and_policies/user_account_issurance_criteria.md",
                        "User Account Issurance Criteria");

                Main.build(docsDir, proj.resolve("static-html"), true);

                Path expected = proj.resolve(
                        "static-html/terms_and_policies/user_account_issurance_criteria/index.html");
                assertTrue(Files.exists(expected),
                        "Numeric prefix of directory must be stripped; expected: " + expected);
            }

            @Test
            @DisplayName("numeric prefix not retained in production output")
            void numericPrefixNotRetained_productionMode() throws IOException {
                Path proj = createProject("proj");
                Path docsDir = proj.resolve("docs");
                writeDoc(docsDir,
                        "010_terms_and_policies/user_account_issurance_criteria.md",
                        "User Account Issurance Criteria");

                Main.build(docsDir, proj.resolve("static-html"), true);

                Path unexpected = proj.resolve(
                        "static-html/010_terms_and_policies/user_account_issurance_criteria/index.html");
                assertFalse(Files.exists(unexpected),
                        "Numeric prefix must be stripped; file must not exist at: " + unexpected);
            }

            @Test
            @DisplayName("production mode does not produce .html file (only index.html inside subdir)")
            void productionMode_noDirectHtmlFile() throws IOException {
                Path proj = createProject("proj");
                Path docsDir = proj.resolve("docs");
                writeDoc(docsDir,
                        "010_terms_and_policies/user_account_issurance_criteria.md",
                        "User Account Issurance Criteria");

                Main.build(docsDir, proj.resolve("static-html"), true);

                Path notExpected = proj.resolve(
                        "static-html/terms_and_policies/user_account_issurance_criteria.html");
                assertFalse(Files.exists(notExpected),
                        "Production mode must not produce a .html file directly; "
                                + "clean URL uses index.html inside a subdirectory");
            }
        }
    }

    // ---- Example 2: Documentation standard dir structure (dir/dir.md) --

    @Nested
    @DisplayName("Example 2 - Documentation standard directory structure (dir/dir.md pattern)")
    class DocStandardDirStructure {

        /**
         * Structure:
         * docs/
         *   010_terms_and_policies/
         *     010_UserAccountIssuanceCriteria_260401_oo01/
         *       010_UserAccountIssuanceCriteria_260401_oo01.md
         *
         * Docusaurus convention: dir/dir.md collapses to the directory URL.
         * Dev:  terms_and_policies/UserAccountIssuanceCriteria_260401_oo01.html
         * Prod: terms_and_policies/UserAccountIssuanceCriteria_260401_oo01/  (index.html inside)
         */
        @Nested
        @DisplayName("Dev mode (.html suffix)")
        class DevMode {

            @Test
            @DisplayName("dir/dir.md collapses to directory path: terms_and_policies/UserAccountIssuanceCriteria_260401_oo01.html")
            void dirDirMd_collapsesToDirPath_devMode() throws IOException {
                Path proj = createProject("proj");
                Path docsDir = proj.resolve("docs");
                writeDoc(docsDir,
                        "010_terms_and_policies/"
                                + "010_UserAccountIssuanceCriteria_260401_oo01/"
                                + "010_UserAccountIssuanceCriteria_260401_oo01.md",
                        "User Account Issuance Criteria");

                Main.build(docsDir, proj.resolve("static-html"), false);

                Path expected = proj.resolve(
                        "static-html/terms_and_policies/"
                                + "UserAccountIssuanceCriteria_260401_oo01.html");
                assertTrue(Files.exists(expected),
                        "dir/dir.md must collapse to directory URL in dev mode; expected: " + expected);
            }

            @Test
            @DisplayName("dir/dir.md collapsed: deep path (dir/file.html) must NOT exist")
            void dirDirMd_deepPathDoesNotExist_devMode() throws IOException {
                Path proj = createProject("proj");
                Path docsDir = proj.resolve("docs");
                writeDoc(docsDir,
                        "010_terms_and_policies/"
                                + "010_UserAccountIssuanceCriteria_260401_oo01/"
                                + "010_UserAccountIssuanceCriteria_260401_oo01.md",
                        "User Account Issuance Criteria");

                Main.build(docsDir, proj.resolve("static-html"), false);

                Path notExpected = proj.resolve(
                        "static-html/terms_and_policies/"
                                + "UserAccountIssuanceCriteria_260401_oo01/"
                                + "UserAccountIssuanceCriteria_260401_oo01.html");
                assertFalse(Files.exists(notExpected),
                        "dir/dir.md must collapse; deep path must not exist: " + notExpected);
            }
        }

        @Nested
        @DisplayName("Production mode (clean URL / index.html)")
        class ProductionMode {

            @Test
            @DisplayName("dir/dir.md collapses to directory path: terms_and_policies/UserAccountIssuanceCriteria_260401_oo01/index.html")
            void dirDirMd_collapsesToDirPath_productionMode() throws IOException {
                Path proj = createProject("proj");
                Path docsDir = proj.resolve("docs");
                writeDoc(docsDir,
                        "010_terms_and_policies/"
                                + "010_UserAccountIssuanceCriteria_260401_oo01/"
                                + "010_UserAccountIssuanceCriteria_260401_oo01.md",
                        "User Account Issuance Criteria");

                Main.build(docsDir, proj.resolve("static-html"), true);

                Path expected = proj.resolve(
                        "static-html/terms_and_policies/"
                                + "UserAccountIssuanceCriteria_260401_oo01/index.html");
                assertTrue(Files.exists(expected),
                        "dir/dir.md must collapse to directory URL in production mode; expected: " + expected);
            }

            @Test
            @DisplayName("dir/dir.md collapsed: deep path (dir/dir/index.html) must NOT exist")
            void dirDirMd_deepPathDoesNotExist_productionMode() throws IOException {
                Path proj = createProject("proj");
                Path docsDir = proj.resolve("docs");
                writeDoc(docsDir,
                        "010_terms_and_policies/"
                                + "010_UserAccountIssuanceCriteria_260401_oo01/"
                                + "010_UserAccountIssuanceCriteria_260401_oo01.md",
                        "User Account Issuance Criteria");

                Main.build(docsDir, proj.resolve("static-html"), true);

                Path notExpected = proj.resolve(
                        "static-html/terms_and_policies/"
                                + "UserAccountIssuanceCriteria_260401_oo01/"
                                + "UserAccountIssuanceCriteria_260401_oo01/index.html");
                assertFalse(Files.exists(notExpected),
                        "dir/dir.md must collapse; deep path must not exist: " + notExpected);
            }
        }
    }

    // ---- Example 3: dir/dir.md + subdirs (Pattern 2) -------------------

    @Nested
    @DisplayName("Example 3 - dir/dir.md with subdirectories (Pattern 2)")
    class DirDirMdWithSubdirs {

        /**
         * Structure:
         * docs/
         *   010_guide/
         *     010_guide.md          ← same-name .md (index page)
         *     020_subpage/
         *       020_subpage.md
         *
         * Pattern 2 rule: same-name .md collapses to directory URL, even when subdirs exist.
         */
        @Nested
        @DisplayName("Dev mode (.html suffix)")
        class DevMode {

            @Test
            @DisplayName("same-name .md collapses to guide.html even when subdirs present")
            void dirDirMd_withSubdirs_collapsesToDir_devMode() throws IOException {
                Path proj = createProject("proj");
                Path docsDir = proj.resolve("docs");
                writeDoc(docsDir, "010_guide/010_guide.md", "Guide");
                writeDoc(docsDir, "010_guide/020_subpage/020_subpage.md", "Subpage");

                Main.build(docsDir, proj.resolve("static-html"), false);

                Path expected = proj.resolve("static-html/guide.html");
                assertTrue(Files.exists(expected),
                        "Pattern 2: same-name .md must collapse to guide.html; expected: " + expected);
            }

            @Test
            @DisplayName("deep path guide/guide.html must NOT exist")
            void dirDirMd_withSubdirs_deepPathAbsent_devMode() throws IOException {
                Path proj = createProject("proj");
                Path docsDir = proj.resolve("docs");
                writeDoc(docsDir, "010_guide/010_guide.md", "Guide");
                writeDoc(docsDir, "010_guide/020_subpage/020_subpage.md", "Subpage");

                Main.build(docsDir, proj.resolve("static-html"), false);

                Path notExpected = proj.resolve("static-html/guide/guide.html");
                assertFalse(Files.exists(notExpected),
                        "Pattern 2: deep path must not exist: " + notExpected);
            }

            @Test
            @DisplayName("sibling subpage renders at guide/subpage.html")
            void subpage_rendersAtCorrectPath_devMode() throws IOException {
                Path proj = createProject("proj");
                Path docsDir = proj.resolve("docs");
                writeDoc(docsDir, "010_guide/010_guide.md", "Guide");
                writeDoc(docsDir, "010_guide/020_subpage/020_subpage.md", "Subpage");

                Main.build(docsDir, proj.resolve("static-html"), false);

                Path expected = proj.resolve("static-html/guide/subpage.html");
                assertTrue(Files.exists(expected),
                        "Subpage (Pattern 2 sibling) must render at guide/subpage.html; expected: " + expected);
            }
        }

        @Nested
        @DisplayName("Production mode (clean URL / index.html)")
        class ProductionMode {

            @Test
            @DisplayName("same-name .md collapses to guide/index.html even when subdirs present")
            void dirDirMd_withSubdirs_collapsesToDir_productionMode() throws IOException {
                Path proj = createProject("proj");
                Path docsDir = proj.resolve("docs");
                writeDoc(docsDir, "010_guide/010_guide.md", "Guide");
                writeDoc(docsDir, "010_guide/020_subpage/020_subpage.md", "Subpage");

                Main.build(docsDir, proj.resolve("static-html"), true);

                Path expected = proj.resolve("static-html/guide/index.html");
                assertTrue(Files.exists(expected),
                        "Pattern 2: same-name .md must collapse to guide/index.html; expected: " + expected);
            }

            @Test
            @DisplayName("deep path guide/guide/index.html must NOT exist")
            void dirDirMd_withSubdirs_deepPathAbsent_productionMode() throws IOException {
                Path proj = createProject("proj");
                Path docsDir = proj.resolve("docs");
                writeDoc(docsDir, "010_guide/010_guide.md", "Guide");
                writeDoc(docsDir, "010_guide/020_subpage/020_subpage.md", "Subpage");

                Main.build(docsDir, proj.resolve("static-html"), true);

                Path notExpected = proj.resolve("static-html/guide/guide/index.html");
                assertFalse(Files.exists(notExpected),
                        "Pattern 2: deep path must not exist: " + notExpected);
            }

            @Test
            @DisplayName("sibling subpage renders at guide/subpage/index.html")
            void subpage_rendersAtCorrectPath_productionMode() throws IOException {
                Path proj = createProject("proj");
                Path docsDir = proj.resolve("docs");
                writeDoc(docsDir, "010_guide/010_guide.md", "Guide");
                writeDoc(docsDir, "010_guide/020_subpage/020_subpage.md", "Subpage");

                Main.build(docsDir, proj.resolve("static-html"), true);

                Path expected = proj.resolve("static-html/guide/subpage/index.html");
                assertTrue(Files.exists(expected),
                        "Subpage (Pattern 2 sibling) must render at guide/subpage/index.html; expected: " + expected);
            }
        }
    }

    // ---- Numeric prefix stripping: edge cases --------------------------

    @Nested
    @DisplayName("Numeric prefix stripping edge cases")
    class NumericPrefixEdgeCases {

        @Test
        @DisplayName("filename with numeric prefix is stripped")
        void filenameNumericPrefix_stripped_devMode() throws IOException {
            Path proj = createProject("proj");
            Path docsDir = proj.resolve("docs");
            writeDoc(docsDir, "010_intro.md", "Intro");

            Main.build(docsDir, proj.resolve("static-html"), false);

            assertTrue(Files.exists(proj.resolve("static-html/intro.html")),
                    "Numeric prefix on filename must be stripped; expected static-html/intro.html");
            assertFalse(Files.exists(proj.resolve("static-html/010_intro.html")),
                    "File with numeric prefix must not exist; must be stripped");
        }

        @Test
        @DisplayName("filename without numeric prefix is unchanged")
        void filenameWithoutPrefix_unchanged_devMode() throws IOException {
            Path proj = createProject("proj");
            Path docsDir = proj.resolve("docs");
            writeDoc(docsDir, "about.md", "About");

            Main.build(docsDir, proj.resolve("static-html"), false);

            assertTrue(Files.exists(proj.resolve("static-html/about.html")),
                    "Filename without numeric prefix must remain unchanged");
        }

        @Test
        @DisplayName("deeply nested path: every segment stripped")
        void deeplyNestedPath_everySegmentStripped_devMode() throws IOException {
            Path proj = createProject("proj");
            Path docsDir = proj.resolve("docs");
            writeDoc(docsDir, "010_section/020_subsection/030_page.md", "Deep Page");

            Main.build(docsDir, proj.resolve("static-html"), false);

            assertTrue(Files.exists(proj.resolve("static-html/section/subsection/page.html")),
                    "All segments must have numeric prefix stripped");
            assertFalse(Files.exists(proj.resolve("static-html/010_section/020_subsection/030_page.html")),
                    "No segment should retain its numeric prefix");
        }

        @Test
        @DisplayName("deeply nested path in production mode: every segment stripped")
        void deeplyNestedPath_everySegmentStripped_productionMode() throws IOException {
            Path proj = createProject("proj");
            Path docsDir = proj.resolve("docs");
            writeDoc(docsDir, "010_section/020_subsection/030_page.md", "Deep Page");

            Main.build(docsDir, proj.resolve("static-html"), true);

            assertTrue(Files.exists(proj.resolve("static-html/section/subsection/page/index.html")),
                    "All segments must have numeric prefix stripped in production");
        }
    }
}
