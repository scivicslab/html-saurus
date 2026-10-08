package com.scivicslab.htmlsaurus;

import com.scivicslab.pluggablecli.CommandRepository;

import org.apache.commons.cli.CommandLine;
import org.apache.commons.cli.ParseException;

/**
 * Reads a command line against a {@link CommandRepository} and runs what it names, or says why it
 * cannot. The three programs here differ in the commands they register, not in how they dispatch,
 * so they share this.
 *
 * <p>The ordering is the part worth having in one place. {@code CommandRepository.parse} reads the
 * first word as the command and then parses the rest against that command's options — and against
 * an empty set when the command is unknown, so an unknown command carrying options raises a
 * ParseException naming the first option rather than the command. {@code html-saurus-build-only
 * rebuild -d /x} answered "Unrecognized option: -d", which sends the reader to look at {@code -d}.
 * Asking whether the command exists before reporting the parse failure puts the blame where it
 * belongs.
 */
public final class CliRunner {

    private CliRunner() {}

    /**
     * @param cmds     the commands this program registered
     * @param synopsis the one-line usage shown above the command list
     * @param args     the command line as given to {@code main}
     * @return the exit status: 0 when a command ran or help was shown, 1 otherwise
     */
    public static int run(CommandRepository cmds, String synopsis, String[] args) {
        try {
            CommandLine cl = cmds.parse(args);
            String command = cmds.getGivenCommand();

            if (command == null) {
                cmds.printCommandList(synopsis);
                return 0;
            }
            if (cmds.isHelpRequested()) {
                cmds.printCommandHelp(command);
                return 0;
            }
            if (!cmds.hasCommand(command)) {
                System.err.println("Error: Unknown command: " + command);
                cmds.printCommandList(synopsis);
                return 1;
            }
            cmds.execute(command, cl);
            return 0;
        } catch (ParseException e) {
            String command = cmds.getGivenCommand();
            if (command != null && !cmds.hasCommand(command)) {
                System.err.println("Error: Unknown command: " + command);
                cmds.printCommandList(synopsis);
            } else {
                System.err.println("Error: " + e.getMessage());
                cmds.printCommandHelp(command);
            }
            return 1;
        }
    }
}
