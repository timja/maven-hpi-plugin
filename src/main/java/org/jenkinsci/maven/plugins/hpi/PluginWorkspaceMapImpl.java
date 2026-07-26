package org.jenkinsci.maven.plugins.hpi;

import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.channels.Channels;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.channels.OverlappingFileLockException;
import java.nio.file.StandardOpenOption;
import java.util.Map;
import java.util.Properties;
import javax.inject.Named;
import javax.inject.Singleton;

/**
 * Default and currently the only implementation of {@link PluginWorkspaceMap}
 *
 * @author Jesse Glick
 * @author Kohsuke Kawaguchi
 */
@Named
@Singleton
public class PluginWorkspaceMapImpl implements PluginWorkspaceMap {

    /**
     * Guards {@link #mapFile} against concurrent access from this class loader. A {@link FileLock}
     * is taken in addition to this, since that guards against other JVMs (such as concurrent builds)
     * but not against other threads of this one.
     */
    private static final Object LOCK = new Object();

    private static final int LOCK_ATTEMPTS = 20;

    private static final long LOCK_RETRY_MILLIS = 50;

    private final File mapFile;

    public PluginWorkspaceMapImpl(File mapFile) {
        this.mapFile = mapFile;
    }

    public PluginWorkspaceMapImpl() {
        this.mapFile = new File(System.getProperty("user.home"), ".jenkins-hpl-map");
    }

    @Override
    @SuppressFBWarnings(value = "PATH_TRAVERSAL_IN", justification = "TODO needs triage")
    public /*@CheckForNull*/ File read(String id) throws IOException {
        synchronized (LOCK) {
            if (!mapFile.isFile()) {
                return null;
            }
            try (FileChannel channel = FileChannel.open(mapFile.toPath(), StandardOpenOption.READ);
                    FileLock ignored = lock(channel, true)) {
                for (Map.Entry<Object, Object> entry : loadMap(channel).entrySet()) {
                    if (entry.getValue().equals(id)) {
                        String path = (String) entry.getKey();
                        File f = new File(path);
                        if (f.exists()) {
                            return f;
                        }
                    }
                }
            }
            return null;
        }
    }

    @Override
    public void write(String id, File f) throws IOException {
        synchronized (LOCK) {
            try (FileChannel channel = FileChannel.open(
                            mapFile.toPath(),
                            StandardOpenOption.CREATE,
                            StandardOpenOption.READ,
                            StandardOpenOption.WRITE);
                    FileLock ignored = lock(channel, false)) {
                Properties p = loadMap(channel);
                p.setProperty(f.getAbsolutePath(), id);
                // Rewrite in place while still holding the lock, so that no reader can observe the
                // truncated file.
                channel.truncate(0);
                channel.position(0);
                try (OutputStream os = new java.io.FilterOutputStream(Channels.newOutputStream(channel)) {
                    @Override
                    public void close() throws IOException {
                        flush();
                    }
                }) {
                    p.store(os, " List of development files for Jenkins plugins that have been built.");
                }
            }
        }
    }

    private Properties loadMap(FileChannel channel) throws IOException {
        Properties p = new Properties();
        channel.position(0);
        try (InputStream is = new java.io.FilterInputStream(Channels.newInputStream(channel)) {
            @Override
            public void close() throws IOException {
                // Intentionally do not close the underlying FileChannel (callers may still use it).
            }
        }) {
            p.load(is);
        } catch (IllegalArgumentException x) {
            throw new IOException("Malformed " + mapFile + ": " + x, x);
        }
        return p;
    }

    /**
     * Takes a {@link FileLock} on the whole of {@code channel}, waiting for any other class loader in
     * this JVM to release its own lock first. A {@link FileLock} is held by the JVM rather than by
     * the thread, so overlapping requests are an error rather than something the caller blocks on.
     */
    private FileLock lock(FileChannel channel, boolean shared) throws IOException {
        for (int i = 0; ; i++) {
            try {
                return channel.lock(0L, Long.MAX_VALUE, shared);
            } catch (OverlappingFileLockException e) {
                if (i >= LOCK_ATTEMPTS) {
                    throw new IOException("Timed out waiting for a lock on " + mapFile, e);
                }
                try {
                    Thread.sleep(LOCK_RETRY_MILLIS);
                } catch (InterruptedException x) {
                    Thread.currentThread().interrupt();
                    throw new IOException("Interrupted while waiting for a lock on " + mapFile, x);
                }
            }
        }
    }
}
