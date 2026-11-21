package com.scivicslab.htmlsaurus;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.List;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * How the Projects tab groups projects into series (ProjectSeries_261121_oo01): the series of a project is its name
 * without the digits at its end, and a series of one project is shown as that project alone.
 */
@Tag("ProjectSeries_261121_oo01")
class ProjectSeriesTest {

    @Test
    void seriesOf_dropsTheDigitsAtTheEndOfTheName() {
        assertEquals("doc_Base", ProjectSeries.seriesOf("doc_Base010"));
        assertEquals("doc_SCI", ProjectSeries.seriesOf("doc_SCI003"));
        assertEquals("doc_SCIVICS", ProjectSeries.seriesOf("doc_SCIVICS002"));
        assertEquals("doc_AASG", ProjectSeries.seriesOf("doc_AASG1"));
        assertEquals("nigsc_homepage", ProjectSeries.seriesOf("nigsc_homepage2"));
    }

    @Test
    void seriesOf_whenTheNameEndsInNoDigit_isTheNameItself() {
        assertEquals("doc_Template-ts", ProjectSeries.seriesOf("doc_Template-ts"));
        assertEquals("scivicslab-homepage", ProjectSeries.seriesOf("scivicslab-homepage"));
        assertEquals("2026", ProjectSeries.seriesOf("2026"), "a name of digits only is not emptied");
    }

    @Test
    void group_keepsDocSciAndDocScivicsApart_becauseASeriesIsTheWholeNameNotAPrefix() {
        List<ProjectSeries.Group> groups = ProjectSeries.group(List.of("doc_SCIVICS001", "doc_SCI001", "doc_SCIVICS002", "doc_SCI002"));
        assertEquals(List.of("doc_SCI", "doc_SCIVICS"), groups.stream().map(ProjectSeries.Group::series).toList());
        assertEquals(List.of("doc_SCI001", "doc_SCI002"), groups.get(0).projects());
        assertEquals(List.of("doc_SCIVICS001", "doc_SCIVICS002"), groups.get(1).projects());
    }

    @Test
    void group_whenASeriesHasOneProject_showsTheProjectAlone_inNameOrderAmongTheSeries() {
        List<ProjectSeries.Group> groups = ProjectSeries.group(List.of(
                "scivicslab-homepage", "doc_Base011", "doc_AASG1", "doc_Base010", "nigsc_homepage2", "doc_Template-ts"));
        assertEquals(List.of("doc_AASG1", "doc_Base", "doc_Template-ts", "nigsc_homepage2", "scivicslab-homepage"),
                groups.stream().map(ProjectSeries.Group::label).toList());
        assertEquals(List.of(false, true, false, false, false), groups.stream().map(ProjectSeries.Group::isSeries).toList());
        assertEquals(List.of("doc_Base010", "doc_Base011"), groups.get(1).projects(), "projects of a series in name order");
    }
}
