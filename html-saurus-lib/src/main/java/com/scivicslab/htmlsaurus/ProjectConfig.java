package com.scivicslab.htmlsaurus;

/**
 * Everything a site's own files say about how to render it, read once per project.
 *
 * <p>The three components group the values by where they come from, because that is what decides
 * how a value is written and what happens when it is absent. Nothing else about a value depends on
 * the order in which it was read, and the reads are independent of one another.
 *
 * @param docusaurus what the Docusaurus configuration states
 * @param files      files whose whole content is the value
 * @param properties what {@code html-saurus.properties} states
 */
record ProjectConfig(DocusaurusSettings docusaurus,
                     EmbeddedFiles files,
                     HtmlSaurusSettings properties) {

    /**
     * Values named by a key in {@code docusaurus.config.ts} (or {@code docusaurus.config.js}), the
     * file Docusaurus itself reads. The site name is the one value a translation may override:
     * {@code title.message} in {@code i18n/<locale>/docusaurus-theme-classic/navbar.json} wins over
     * {@code navbar.title}. The favicon and the logo are named there but stored under
     * {@code static/}, and are carried here as inline {@code data:} URLs.
     *
     * @param siteName       name shown in the navbar; the caller's fallback when neither file says
     * @param logoDataUrl    navbar logo, from {@code navbar.logo.src}; null if not configured
     * @param logoAlt        alternative text for that logo, from {@code navbar.logo.alt}
     * @param faviconDataUrl tab icon, from {@code favicon}; null if not configured
     * @param siteUrl        the site's own absolute URL, from {@code url}, needed to state absolute
     *                       links in the sitemap and the feeds; null if not configured
     */
    record DocusaurusSettings(String siteName,
                              String logoDataUrl,
                              String logoAlt,
                              String faviconDataUrl,
                              String siteUrl) {}

    /**
     * Files a project may place beside its Docusaurus configuration, whose whole content is the
     * value. They carry no keys. All four are read in production mode only, so every component is
     * null in portal mode. A locale-specific variant wins where one exists, for example
     * {@code html-saurus-footer.en.html}.
     *
     * @param customCss       {@code html-saurus.css}, appended to every page's stylesheet
     * @param customHeader    {@code html-saurus-header.html}, placed above the navbar
     * @param customFooter    {@code html-saurus-footer.html}, placed below the content
     * @param customTocFooter {@code html-saurus-toc-footer.html}, placed at the foot of the
     *                        right-hand table of contents
     */
    record EmbeddedFiles(String customCss,
                         String customHeader,
                         String customFooter,
                         String customTocFooter) {}

    /**
     * Values named by a key in {@code html-saurus.properties}, the one configuration file Docusaurus
     * does not read. Each has a default, so a project that omits the file still builds.
     *
     * @param navPrimaryItems {@code navbar.primaryItems}: navbar sections shown inline before the
     *                        rest collapse into a "More" dropdown; 0 or less shows every section
     */
    record HtmlSaurusSettings(int navPrimaryItems) {}
}
