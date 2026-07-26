package org.jenkinsci.maven.plugins.hpi;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.File;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class PluginWorkspaceMapImplTest {

    @TempDir
    private Path tmp;

    @Test
    void roundTrip() throws Exception {
        PluginWorkspaceMap map = new PluginWorkspaceMapImpl(tmp.resolve("map").toFile());
        assertNull(map.read("g:a:hpi:1.0"));

        File dir = tmp.resolve("a").toFile();
        assertTrue(dir.mkdir());
        map.write("g:a:hpi:1.0", dir);
        assertEquals(dir, map.read("g:a:hpi:1.0"));

        // A second entry must not displace the first.
        File other = tmp.resolve("b").toFile();
        assertTrue(other.mkdir());
        map.write("g:b:hpi:1.0", other);
        assertEquals(dir, map.read("g:a:hpi:1.0"));
        assertEquals(other, map.read("g:b:hpi:1.0"));
    }

    /**
     * Concurrent writers must not lose entries, which is what {@code threadSafe} on the mojos writing
     * to this map depends on.
     */
    @Test
    void concurrentWrites() throws Exception {
        PluginWorkspaceMap map = new PluginWorkspaceMapImpl(tmp.resolve("map").toFile());
        int count = 20;

        List<Callable<Void>> tasks = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            File dir = tmp.resolve("p" + i).toFile();
            assertTrue(dir.mkdir());
            String id = "g:p" + i + ":hpi:1.0";
            tasks.add(() -> {
                map.write(id, dir);
                return null;
            });
        }

        ExecutorService svc = Executors.newFixedThreadPool(8);
        try {
            for (Future<Void> f : svc.invokeAll(tasks, 30, TimeUnit.SECONDS)) {
                f.get(); // rethrow any failure (or CancellationException on timeout)
            }
        } finally {
            svc.shutdownNow();
            assertTrue(svc.awaitTermination(30, TimeUnit.SECONDS));
        }

        for (int i = 0; i < count; i++) {
            assertEquals(tmp.resolve("p" + i).toFile(), map.read("g:p" + i + ":hpi:1.0"));
        }
    }
}
