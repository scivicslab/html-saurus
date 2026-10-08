package com.scivicslab.htmlsaurus;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * The command line one of html-saurus's three programs accepts, declared rather than deduced.
 *
 * <p>Each program used to walk its own chain of {@code else if}s, and an option the chain did not
 * name fell off the end in silence. That is how {@code --port 80} broke the build program: the
 * option matched nothing, and the {@code 80} after it matched "does not begin with --", so the
 * directory to build became {@code ./80}. Nothing was printed.
 *
 * <p>Here a program says which options it takes, which it takes an argument for, and which it only
 * tolerates because a stored command line still passes them. Anything else is reported, and a
 * second bare word — which is what a swallowed option's argument becomes — is reported too.
 *
 * <p>An option that was removed or renamed is declared as well, so the reason reaches whoever typed
 * it instead of the behaviour quietly changing underneath them.
 */
public final class Options {

    private final String program;
    private final Set<String> flags = new LinkedHashSet<>();
    private final Set<String> values = new LinkedHashSet<>();
    /** name -> the name it now goes by; the old name keeps working. */
    private final Map<String, String> renamed = new LinkedHashMap<>();
    /** name -> why it no longer does anything. */
    private final Map<String, String> obsolete = new LinkedHashMap<>();
    /** The obsolete names that are followed by an argument, which has to be stepped over. */
    private final Set<String> obsoleteWithValue = new LinkedHashSet<>();

    private final Set<String> given = new LinkedHashSet<>();
    private final Map<String, String> argOf = new LinkedHashMap<>();
    private final List<String> bare = new ArrayList<>();

    public Options(String program) {
        this.program = program;
    }

    /** An option that is either present or absent. */
    public Options flag(String name) {
        flags.add(name);
        return this;
    }

    /** An option followed by one argument. */
    public Options value(String name) {
        values.add(name);
        return this;
    }

    /** An option that now goes by another name. The old name keeps doing what it did. */
    public Options renamed(String oldName, String newName) {
        renamed.put(oldName, newName);
        return this;
    }

    /**
     * An option this program no longer acts on. {@code takesValue} says whether an argument follows
     * it, which has to be stepped over so it is not mistaken for the directory.
     */
    public Options obsolete(String name, boolean takesValue, String why) {
        obsolete.put(name, why);
        if (takesValue) obsoleteWithValue.add(name);
        return this;
    }

    /** Reads the command line, reporting on standard error anything this program did not declare. */
    public Options parse(String[] args) {
        for (int i = 0; i < args.length; i++) {
            String a = args[i];
            if (renamed.containsKey(a)) {
                String now = renamed.get(a);
                System.err.println(program + ": " + a + " is now called " + now + "; acting on it");
                given.add(now);
            } else if (obsolete.containsKey(a)) {
                System.err.println(program + ": " + a + " no longer does anything — " + obsolete.get(a));
                if (obsoleteWithValue.contains(a) && i + 1 < args.length) i++;
            } else if (values.contains(a)) {
                if (i + 1 >= args.length) {
                    System.err.println(program + ": " + a + " needs an argument; ignoring it");
                } else {
                    argOf.put(a, args[++i]);
                    given.add(a);
                }
            } else if (flags.contains(a)) {
                given.add(a);
            } else if (a.startsWith("--")) {
                System.err.println(program + ": unknown option " + a + "; ignoring it");
            } else {
                bare.add(a);
            }
        }
        for (int i = 1; i < bare.size(); i++) {
            System.err.println(program + ": unexpected argument " + bare.get(i) + "; ignoring it");
        }
        return this;
    }

    /** Whether the option was given. */
    public boolean is(String name) {
        return given.contains(name);
    }

    /** The number after a value option, or {@code fallback} when it was not given or is not a number. */
    public int number(String name, int fallback) {
        String raw = argOf.get(name);
        if (raw == null) return fallback;
        try {
            return Integer.parseInt(raw);
        } catch (NumberFormatException e) {
            System.err.println(program + ": " + name + " " + raw + " is not a number; using " + fallback);
            return fallback;
        }
    }

    /** The one bare word on the command line as an absolute path, or the working directory. */
    public Path path() {
        return (bare.isEmpty() ? Path.of("") : Path.of(bare.get(0))).toAbsolutePath();
    }
}
