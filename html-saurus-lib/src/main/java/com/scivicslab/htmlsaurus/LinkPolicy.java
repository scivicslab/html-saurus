package com.scivicslab.htmlsaurus;

import org.apache.commons.cli.CommandLine;
import org.apache.commons.cli.Option;
import org.apache.commons.cli.Options;

/**
 * What a build does when a page of the built site holds a same-site link that resolves to nothing.
 *
 * <p>Three behaviours exist, and two options choose between them. With neither option the build
 * prints the first findings and goes on, which is {@link #WARN}. {@code --strict-links} makes
 * the build fail, which is {@link #FAIL}. {@code --ignore-links} skips the check, which is
 * {@link #IGNORE}. The two options exclude each other.</p>
 */
public enum LinkPolicy {

    /** Do not look for broken links at all. */
    IGNORE,

    /** Print the first broken links to the log and carry on. The default. */
    WARN,

    /** Print every broken link and fail the build when there is one. */
    FAIL;

    public static final String FAIL_OPTION = "strict-links";
    public static final String IGNORE_OPTION = "ignore-links";

    /** Adds the two options to a command's option set. */
    public static void addOptions(Options opts) {
        opts.addOption(Option.builder()
                .longOpt(FAIL_OPTION)
                .hasArg(false)
                .desc("""
                        Fail when a built page holds a same-site link that resolves to nothing. \
                        Every broken link is printed, the exit status is 1, and the stages that \
                        follow the pages do not run.""")
                .required(false)
                .build());
        opts.addOption(Option.builder()
                .longOpt(IGNORE_OPTION)
                .hasArg(false)
                .desc("Do not check the built pages for broken same-site links.")
                .required(false)
                .build());
    }

    /**
     * Reads the choice from a parsed command line.
     *
     * @throws IllegalArgumentException when both options are given
     */
    public static LinkPolicy from(CommandLine cl) {
        boolean fail = cl.hasOption(FAIL_OPTION);
        boolean ignore = cl.hasOption(IGNORE_OPTION);
        if (fail && ignore) {
            throw new IllegalArgumentException(
                    "--" + FAIL_OPTION + " and --" + IGNORE_OPTION + " cannot be given together");
        }
        return fail ? FAIL : ignore ? IGNORE : WARN;
    }
}
