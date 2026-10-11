package com.scivicslab.htmlsaurus;

import java.nio.file.Path;

/**
 * Files and directories whose name starts with a dot are not part of a documentation site:
 * {@code .gitkeep}, or a tool's working directory such as {@code .rst-on-lisp}. They get no
 * sidebar entry, no page, no search-index entry, and are not resolved as documents.
 */
public final class HiddenPaths {

    private HiddenPaths() {}

    /** Whether this one file or directory is hidden (its own name starts with a dot). */
    public static boolean isHidden(Path entry) {
        Path name = entry.getFileName();
        return name != null && name.toString().startsWith(".");
    }

    /** Whether {@code path} is hidden or lies inside a hidden directory below {@code root}. */
    public static boolean isHiddenUnder(Path root, Path path) {
        Path rel;
        try {
            rel = root.toAbsolutePath().normalize().relativize(path.toAbsolutePath().normalize());
        } catch (IllegalArgumentException e) {
            return isHidden(path);
        }
        for (Path part : rel) {
            String s = part.toString();
            if (s.startsWith(".") && !s.equals(".") && !s.equals("..")) return true;
        }
        return false;
    }
}
