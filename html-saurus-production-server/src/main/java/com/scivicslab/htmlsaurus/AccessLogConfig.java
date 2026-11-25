package com.scivicslab.htmlsaurus;

import org.apache.commons.cli.Option;
import org.apache.commons.cli.Options;

import java.nio.file.Path;
import java.util.function.UnaryOperator;

/**
 * Where the access log goes and how it is written, resolved from the command line, then the system properties, then the
 * defaults.
 *
 * <table>
 * <caption>Options</caption>
 * <tr><th>Option</th><th>System property</th><th>Default</th></tr>
 * <tr><td>{@code --no-access-log}</td><td>{@code htmlsaurus.access.log=off}</td><td>the log is on</td></tr>
 * <tr><td>{@code --log-dir <dir>}</td><td>{@code htmlsaurus.log.dir}</td><td>{@code logs} in the working directory</td></tr>
 * <tr><td>{@code --access-log <file|->}</td><td>{@code htmlsaurus.access.log}</td><td>{@code access.log}</td></tr>
 * <tr><td>{@code --access-log-response-time}</td><td>{@code htmlsaurus.access.log.response.time=true}</td><td>off</td></tr>
 * <tr><td>{@code --access-log-trusted-proxy <list>}</td><td>{@code htmlsaurus.access.log.trusted.proxies}</td><td>none</td></tr>
 * </table>
 *
 * @param enabled        whether requests are logged
 * @param file           the log file; {@code null} when it is standard output
 * @param responseTime   whether the time each request took, in microseconds, is appended as a last field
 * @param trustedProxies the proxies whose {@code X-Forwarded-For} names the client
 */
public record AccessLogConfig(boolean enabled, Path file, boolean responseTime, TrustedProxies trustedProxies) {

    static final String DEFAULT_DIR = "logs";
    static final String DEFAULT_NAME = "access.log";

    /** @return whether the log goes to standard output */
    public boolean toStdout() {
        return file == null;
    }

    /** Adds the access log's options to the {@code serve} command. */
    static void addOptions(Options opts) {
        opts.addOption(Option.builder().longOpt("no-access-log")
                .desc("Do not write an access log (default: one line per request, Apache combined format).").build());
        opts.addOption(Option.builder().longOpt("log-dir").hasArg().argName("dir")
                .desc("Directory of the access log (default: logs in the working directory; system property htmlsaurus.log.dir).")
                .build());
        opts.addOption(Option.builder().longOpt("access-log").hasArg().argName("file")
                .desc("File name of the access log, in --log-dir unless absolute; '-' writes to standard output "
                        + "(default: access.log; system property htmlsaurus.access.log).").build());
        opts.addOption(Option.builder().longOpt("access-log-response-time")
                .desc("Append the time each request took, in microseconds, to every line. Off by default, so a line is "
                        + "exactly Apache's combined format.").build());
        opts.addOption(Option.builder().longOpt("access-log-trusted-proxy").hasArg().argName("list")
                .desc("Comma separated addresses or CIDR ranges of the reverse proxies whose X-Forwarded-For is believed "
                        + "(default: none; the TCP peer is logged). System property htmlsaurus.access.log.trusted.proxies.")
                .build());
    }

    /**
     * @param cl       the parsed {@code serve} command line
     * @param property reads a system property (or {@code null})
     * @param workDir  the directory a relative {@code --log-dir} is taken from
     */
    static AccessLogConfig from(org.apache.commons.cli.CommandLine cl, UnaryOperator<String> property, Path workDir) {
        return resolve(cl.hasOption("no-access-log"), cl.getOptionValue("log-dir"), cl.getOptionValue("access-log"),
                cl.hasOption("access-log-response-time") ? "true" : null, cl.getOptionValue("access-log-trusted-proxy"),
                property, workDir);
    }

    static AccessLogConfig resolve(boolean disabled, String dirOption, String fileOption, String responseTimeOption,
                                   String proxyOption, UnaryOperator<String> property, Path workDir) {
        String fileSetting = first(fileOption, property.apply("htmlsaurus.access.log"));
        boolean off = disabled || "off".equalsIgnoreCase(property.apply("htmlsaurus.access.log")) && fileOption == null;
        String dir = first(dirOption, property.apply("htmlsaurus.log.dir"));
        Path logDir = workDir.resolve(dir == null ? DEFAULT_DIR : dir).toAbsolutePath().normalize();
        String name = fileSetting == null || "off".equalsIgnoreCase(fileSetting) ? DEFAULT_NAME : fileSetting;
        Path file = "-".equals(name) ? null : logDir.resolve(name).normalize();
        boolean rt = "true".equalsIgnoreCase(first(responseTimeOption, property.apply("htmlsaurus.access.log.response.time")));
        TrustedProxies proxies = TrustedProxies.parse(first(proxyOption, property.apply("htmlsaurus.access.log.trusted.proxies")));
        return new AccessLogConfig(!off, file, rt, proxies);
    }

    private static String first(String a, String b) {
        if (a != null && !a.isBlank()) return a.strip();
        return b == null || b.isBlank() ? null : b.strip();
    }
}
