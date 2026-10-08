package com.scivicslab.htmlsaurus;

/**
 * What a build left behind, carried on the job so the browser can read it after the fact.
 *
 * @param message      what to show for a finished build: empty when there is nothing to say beyond
 *                     that it finished, and a count for a run over every project
 * @param listChanged  {@code true} when the run changed which projects the portal knows, so a page
 *                     showing the old list reloads it
 */
record BuildOutcome(String message, boolean listChanged) {
    static final BuildOutcome NOTHING_YET = new BuildOutcome("", false);
}
