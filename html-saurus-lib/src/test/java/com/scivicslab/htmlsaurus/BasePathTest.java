package com.scivicslab.htmlsaurus;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * {@link BasePath}: the prefix a proxy publishes the server below, and nothing else. With no prefix it must
 * return every URL as it was given, because a server at the host root has to behave as it always did.
 */
class BasePathTest {

    @Test
    void nothingSet_urlsAreUnchanged() {
        for (BasePath none : new BasePath[] {BasePath.none(), BasePath.of(null), BasePath.of(""), BasePath.of("  "), BasePath.of("/")}) {
            assertEquals("", none.prefix());
            assertEquals("/api/search?q=a", none.url("/api/search?q=a"));
            assertEquals("/", none.url("/"));
            assertEquals("/proj/x/", none.strip("/proj/x/"));
        }
    }

    @Test
    void sessionPathWithTrailingSlash_isNormalized() {
        BasePath base = BasePath.of("/pups-with-audit/session/abc123/");
        assertEquals("/pups-with-audit/session/abc123", base.prefix());
        assertEquals("/pups-with-audit/session/abc123/api/search?q=a", base.url("/api/search?q=a"));
        assertEquals("/pups-with-audit/session/abc123/", base.url("/"));
    }

    @Test
    void leadingSlashIsAdded() {
        assertEquals("/session/x", BasePath.of("session/x").prefix());
    }

    @Test
    void strip_takesTheBasePathOffTheFront() {
        BasePath base = BasePath.of("/s/1");
        assertEquals("/proj/page/", base.strip("/s/1/proj/page/"));
        assertEquals("/", base.strip("/s/1"));
        assertEquals("/s/12/proj", base.strip("/s/12/proj"), "A longer segment that merely starts the same is not the prefix");
        assertEquals("/other", base.strip("/other"));
    }

    @Test
    void aValueThatIsNotAPlainPath_isRefused() {
        for (String bad : new String[] {"//evil.example", "/a b", "/a\"b", "/a'b", "/<script>", "http://x/y", "/a//b", "/a?b=c", "/a\\b"}) {
            assertThrows(IllegalArgumentException.class, () -> BasePath.of(bad), bad);
        }
    }

    @Test
    void urlNeedsARootRelativeArgument() {
        assertThrows(IllegalArgumentException.class, () -> BasePath.of("/s").url("api/x"));
    }
}
