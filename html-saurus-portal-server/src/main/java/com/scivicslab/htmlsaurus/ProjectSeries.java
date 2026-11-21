package com.scivicslab.htmlsaurus;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * The series a doc project belongs to, for the two-level list of the Projects tab (ProjectSeries_261121_oo01). A
 * project's series is its name without the digits at its end: {@code doc_Base010} and {@code doc_Base011} are in
 * {@code doc_Base}. The whole rest of the name must match, so {@code doc_SCI003} and {@code doc_SCIVICS002} are in
 * two different series, not one inside the other.
 */
final class ProjectSeries {

    private ProjectSeries() {}

    /**
     * One entry of the first level of the list.
     *
     * @param series   the series, or the project's own name when it is shown alone
     * @param projects the projects under it, in name order; one for a project shown alone
     */
    record Group(String series, List<String> projects) {

        /** @return true when the entry opens to its projects; a series of one project is shown as that project */
        boolean isSeries() {
            return projects.size() > 1;
        }

        /** @return what the first level shows: the series, or the name of the project shown alone */
        String label() {
            return isSeries() ? series : projects.get(0);
        }
    }

    /**
     * @param projectName a doc project's name, for example {@code doc_Base010}
     * @return the name without the digits at its end, for example {@code doc_Base}; the name itself when it ends in no
     *         digit, or is made of digits only
     */
    static String seriesOf(String projectName) {
        int end = projectName.length();
        while (end > 0 && Character.isDigit(projectName.charAt(end - 1))) end--;
        return end == 0 || end == projectName.length() ? projectName : projectName.substring(0, end);
    }

    /**
     * Groups projects into the entries of the first level.
     *
     * @param projectNames the projects, in any order
     * @return the entries in the order of what they show ({@link Group#label()}), each with its projects in name order
     */
    static List<Group> group(List<String> projectNames) {
        Map<String, List<String>> bySeries = new TreeMap<>();
        for (String name : projectNames) bySeries.computeIfAbsent(seriesOf(name), s -> new ArrayList<>()).add(name);
        List<Group> groups = new ArrayList<>();
        for (Map.Entry<String, List<String>> entry : bySeries.entrySet()) {
            List<String> sorted = new ArrayList<>(entry.getValue());
            sorted.sort(null);
            groups.add(new Group(entry.getKey(), List.copyOf(sorted)));
        }
        groups.sort((a, b) -> a.label().compareTo(b.label()));
        return List.copyOf(groups);
    }
}
