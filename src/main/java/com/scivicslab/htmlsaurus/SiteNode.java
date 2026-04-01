package com.scivicslab.htmlsaurus;

import java.util.List;

/**
 * Represents a node in the site navigation tree.
 *
 * @param label    display label for this node
 * @param href     URL path used for navigation: the same-name page URL for Pattern 2 categories,
 *                 the first reachable leaf URL for plain categories, or the page URL for leaves
 * @param isDir    {@code true} if this node represents a category (directory)
 * @param children child nodes (empty for leaf pages)
 * @param catLink  non-null only for Pattern 2 categories (dir/dir.md + subdirs); holds the
 *                 clickable link URL shown on the sidebar category header
 */
record SiteNode(String label, String href, boolean isDir, List<SiteNode> children, String catLink) {}
