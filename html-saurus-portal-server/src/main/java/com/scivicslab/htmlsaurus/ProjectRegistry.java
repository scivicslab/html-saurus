package com.scivicslab.htmlsaurus;

import com.scivicslab.pojoactor.core.ActorRef;
import com.scivicslab.pojoactor.core.ActorSystem;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * The projects this portal serves, by name and in the order the portal lists them, and the locale of each searcher key
 * ({@code <project>} and {@code <project>:<locale>}).
 *
 * <p>A plain POJO with no locks. Scanning the works directory adds and removes projects while requests read them, so it
 * is hosted as an actor ({@link #host}): every change and every read is one message, and a reader gets a copy it
 * owns, never the live list. It is a leaf: it asks no actor and waits on none; a test pins this. The long work of a
 * scan (building and indexing a project) is done by the caller, outside the actor; only the registry's own changes
 * are messages.</p>
 */
final class ProjectRegistry {

    /** The name of the actor in the portal's {@link ActorSystem}. */
    static final String ACTOR = "project-registry";

    private final List<PortalServer.Project> projects = new ArrayList<>();
    private final Map<String, PortalServer.Project> byName = new LinkedHashMap<>();
    private final Map<String, String> searcherLocales = new LinkedHashMap<>();

    /** @return whether the project was added: {@code false} when one of that name is registered already */
    boolean add(PortalServer.Project project) {
        if (byName.containsKey(project.name())) return false;
        projects.add(project);
        byName.put(project.name(), project);
        return true;
    }

    /** Lists the projects in the order of their names, as a fresh start gives them. */
    void sortByName() {
        projects.sort(java.util.Comparator.comparing(PortalServer.Project::name));
    }

    /**
     * Drops every project whose name is not in {@code foundNames}, with the locale of each of its searchers.
     *
     * @return the projects dropped, in list order
     */
    List<PortalServer.Project> removeNotIn(Set<String> foundNames) {
        List<PortalServer.Project> vanished = projects.stream().filter(p -> !foundNames.contains(p.name())).toList();
        for (PortalServer.Project p : vanished) {
            projects.remove(p);
            byName.remove(p.name());
            String localePrefix = p.name() + ":";
            searcherLocales.keySet().removeIf(key -> key.equals(p.name()) || key.startsWith(localePrefix));
        }
        return vanished;
    }

    /** @return a copy of the projects in list order */
    List<PortalServer.Project> snapshot() {
        return List.copyOf(projects);
    }

    /** @return the project of that name, or {@code null} */
    PortalServer.Project project(String name) {
        return byName.get(name);
    }

    void setLocale(String searcherKey, String locale) {
        searcherLocales.put(searcherKey, locale);
    }

    /** @return a copy of the locale of each searcher key, in the order they were set */
    Map<String, String> searcherLocales() {
        return new LinkedHashMap<>(searcherLocales);
    }

    /** Puts one registry into {@code system} as the actor named {@link #ACTOR}. */
    static ActorRef<ProjectRegistry> host(ActorSystem system) {
        return system.actorOf(ACTOR, new ProjectRegistry());
    }
}
