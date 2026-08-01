package com.scivicslab.htmlsaurus;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

/** Unit tests for {@link TranslationCache}: key derivation and on-disk persistence. */
class TranslationCacheTest {

    @Test
    void key_sameTextAndLang_isStable() {
        assertEquals(TranslationCache.key("Hello", "ja"), TranslationCache.key("Hello", "ja"));
    }

    @Test
    void key_differentTargetLang_differs() {
        assertNotEquals(TranslationCache.key("Hello", "ja"), TranslationCache.key("Hello", "en"));
    }

    @Test
    void key_differentText_differs() {
        assertNotEquals(TranslationCache.key("Hello", "ja"), TranslationCache.key("Hi", "ja"));
    }

    @Test
    void getPut_missThenHit(@TempDir Path dir) {
        TranslationCache cache = TranslationCache.load(dir);
        String key = TranslationCache.key("Hello", "ja");
        assertNull(cache.get(key));
        cache.put(key, "こんにちは");
        assertEquals("こんにちは", cache.get(key));
    }

    @Test
    void put_specialCharacters_roundTripThroughReload(@TempDir Path dir) {
        TranslationCache cache = TranslationCache.load(dir);
        String key = TranslationCache.key("multi", "en");
        String value = "line1\nline2\twith tab and \\backslash\\";
        cache.put(key, value);

        TranslationCache reloaded = TranslationCache.load(dir);
        assertEquals(value, reloaded.get(key));
    }

    @Test
    void put_doesNotOverwriteExistingEntry(@TempDir Path dir) {
        TranslationCache cache = TranslationCache.load(dir);
        String key = TranslationCache.key("Hello", "ja");
        cache.put(key, "first");
        cache.put(key, "second");
        assertEquals("first", cache.get(key));
    }
}
