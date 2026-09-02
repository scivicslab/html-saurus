package com.scivicslab.htmlsaurus;

import java.nio.file.Path;

/**
 * Everything a site's own files say about how to render it, read once per project.
 *
 * <p>The values come from three places: the Docusaurus configuration
 * ({@code docusaurus.config.ts/js} and {@code i18n/<locale>/docusaurus-theme-classic/navbar.json}),
 * the {@code html-saurus-*} files a project may place beside it, and
 * {@code html-saurus.properties}. Which file each value comes from is stated by
 * {@link ConfigReader#read}, and nothing outside that method reads project configuration.
 *
 * <p>Grouping them means adding a setting touches the reader and this record, not every class
 * the value travels through: {@link SiteBuilder}, {@link PageRenderer} and {@link BlogBuilder}
 * take this one object.
 *
 * @param siteName        name shown in the navbar
 * @param customCss       extra stylesheet appended to every page; null in portal mode
 * @param customHeader    markup placed above the navbar; null in portal mode
 * @param customFooter    markup placed below the content; null in portal mode
 * @param customTocFooter markup placed at the foot of the right-hand table of contents;
 *                        null in portal mode
 * @param faviconDataUrl  the tab icon as an inline {@code data:} URL; null if the project has none
 * @param logoDataUrl     the navbar logo as an inline {@code data:} URL; null if the project has none
 * @param logoAlt         alternative text for that logo
 * @param siteUrl         the site's own absolute URL, needed to state absolute links in the feeds;
 *                        null if the project does not declare one
 * @param navPrimaryItems navbar sections shown inline before the rest collapse into a "More"
 *                        dropdown; 0 or less shows every section
 */
record ProjectConfig(String siteName,
                     String customCss,
                     String customHeader,
                     String customFooter,
                     String customTocFooter,
                     String faviconDataUrl,
                     String logoDataUrl,
                     String logoAlt,
                     String siteUrl,
                     int navPrimaryItems) {
}
