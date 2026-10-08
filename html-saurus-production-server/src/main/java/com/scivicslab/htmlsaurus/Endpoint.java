package com.scivicslab.htmlsaurus;

/**
 * One path the server answers, together with the decision of whether a public site answers it.
 *
 * <p>A server declares every path it knows about, including the ones a public site must not
 * answer, and {@link SearchServer#start()} registers only those a public site may have. The
 * declaration is what the production endpoint surface test reads, so a path added to the server
 * is a path that test covers from the moment it is written. Before this, the test carried its own
 * hand-written copy of the list, and {@code /api/build-html} was added to the server and never
 * added to the copy: the test went on passing while saying nothing about it.
 *
 * <p>{@link Visibility} has no default on purpose. Whoever adds a path has to write down which
 * one it is, at the line that adds it, rather than get an answer from which {@code if} block the
 * line happened to land in.
 */
record Endpoint(String path, Endpoint.Visibility visibility) {

    enum Visibility {
        /** A public site answers this path. */
        PUBLIC,
        /**
         * Only a development server answers this path. These reach into the machine the server
         * runs on — {@code /mcp} reads and writes files and rebuilds the site, and it asks for no
         * credential — so a public deployment must not register them at all.
         */
        DEV_ONLY
    }

    /** Whether a server running a public site registers this path. */
    boolean servedInProduction() {
        return visibility == Visibility.PUBLIC;
    }
}
