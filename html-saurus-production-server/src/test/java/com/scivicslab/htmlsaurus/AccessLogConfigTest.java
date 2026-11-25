package com.scivicslab.htmlsaurus;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;
import java.util.Map;

import org.junit.jupiter.api.Test;

/** Command line over system property over default. */
class AccessLogConfigTest {

    private static final Path CWD = Path.of("/srv/app");

    private static AccessLogConfig resolve(boolean off, String dir, String file, String rt, String proxy, Map<String, String> props) {
        return AccessLogConfig.resolve(off, dir, file, rt, proxy, props::get, CWD);
    }

    @Test
    void theDefaultIsAccessLogInALogsDirectoryOfTheWorkingDirectory() {
        AccessLogConfig c = resolve(false, null, null, null, null, Map.of());
        assertTrue(c.enabled());
        assertEquals(Path.of("/srv/app/logs/access.log"), c.file());
        assertFalse(c.responseTime());
        assertTrue(c.trustedProxies().isEmpty(), "no header is trusted by default");
    }

    @Test
    void theOptionsOverrideTheSystemProperties() {
        Map<String, String> props = Map.of("htmlsaurus.log.dir", "/var/p", "htmlsaurus.access.log", "p.log");
        AccessLogConfig c = resolve(false, "/var/log/hs", "site.log", "true", "10.0.0.0/8", props);
        assertEquals(Path.of("/var/log/hs/site.log"), c.file());
        assertTrue(c.responseTime());
        assertFalse(c.trustedProxies().isEmpty());
        assertEquals(Path.of("/var/p/p.log"), resolve(false, null, null, null, null, props).file());
    }

    @Test
    void aDashIsStandardOutput_anAbsoluteNameIsUsedAsIs_aRelativeDirIsOfTheWorkingDirectory() {
        assertNull(resolve(false, null, "-", null, null, Map.of()).file());
        assertEquals(Path.of("/tmp/x.log"), resolve(false, "/var/log", "/tmp/x.log", null, null, Map.of()).file());
        assertEquals(Path.of("/srv/app/var/a.log"), resolve(false, "var", "a.log", null, null, Map.of()).file());
    }

    @Test
    void theLogCanBeTurnedOff() {
        assertFalse(resolve(true, null, null, null, null, Map.of()).enabled());
        assertFalse(resolve(false, null, null, null, null, Map.of("htmlsaurus.access.log", "off")).enabled());
    }
}
