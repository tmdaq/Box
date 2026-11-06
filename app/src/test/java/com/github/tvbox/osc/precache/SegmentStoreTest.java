package com.github.tvbox.osc.precache;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.Set;

public class SegmentStoreTest {

    @Rule
    public TemporaryFolder tmp = new TemporaryFolder();

    private File dir;
    private SegmentStore store;

    @Before
    public void setUp() {
        dir = new File(tmp.getRoot(), "s1");
        store = new SegmentStore(dir, "https://cdn.example.com/index.m3u8");
    }

    private void put(String key, byte[] data, String contentType) throws IOException {
        File temp = store.newTempFile(key);
        try (FileOutputStream out = new FileOutputStream(temp)) {
            out.write(data);
        }
        store.commit(key, temp, data.length, contentType);
    }

    private static byte[] read(File f) throws IOException {
        byte[] data = new byte[(int) f.length()];
        try (FileInputStream in = new FileInputStream(f)) {
            int off = 0;
            while (off < data.length) off += in.read(data, off, data.length - off);
        }
        return data;
    }

    @Test
    public void remembersPlaylistUrl() {
        assertEquals("https://cdn.example.com/index.m3u8", store.playlistUrl());
    }

    @Test
    public void committedSegmentCanBeReadBack() throws IOException {
        put("k1", new byte[]{1, 2, 3}, "video/mp2t");

        File f = store.committedFile("k1");

        assertArrayEquals(new byte[]{1, 2, 3}, read(f));
        assertEquals(new File(dir, "k1.seg"), f);
        assertTrue(store.isCommitted("k1"));
        assertEquals("video/mp2t", store.contentType("k1"));
        assertEquals(Collections.singleton("k1"), store.committedKeys());
    }

    @Test
    public void unknownKeyIsNotCommitted() {
        assertNull(store.committedFile("nope"));
        assertFalse(store.isCommitted("nope"));
        assertNull(store.contentType("nope"));
    }

    @Test
    public void tracksCommittedBytesAndAverageSize() throws IOException {
        assertEquals(0, store.committedBytes());
        assertEquals(777, store.averageSize(777));

        put("a", new byte[100], null);
        put("b", new byte[300], null);

        assertEquals(400, store.committedBytes());
        assertEquals(200, store.averageSize(777));
    }

    @Test
    public void recommittingSameKeyReplacesSizeAndContent() throws IOException {
        put("a", new byte[100], "x/old");
        put("a", new byte[]{9, 9}, "x/new");

        assertEquals(2, store.committedBytes());
        assertEquals("x/new", store.contentType("a"));
        assertArrayEquals(new byte[]{9, 9}, read(store.committedFile("a")));
    }

    @Test
    public void tempFilesAreDistinctPerDownload() throws IOException {
        File first = store.newTempFile("k");
        File second = store.newTempFile("k");

        assertNotEquals(first, second);
        assertTrue(first.isFile());
        assertTrue(second.isFile());
        assertTrue(first.getName().matches("k\\.\\d+\\.tmp"));
    }

    @Test
    public void externallyDeletedFileIsTreatedAsMiss() throws IOException {
        put("a", new byte[100], null);
        put("b", new byte[50], null);
        assertTrue(new File(dir, "a.seg").delete());

        assertNull(store.committedFile("a"));
        assertFalse(store.isCommitted("a"));
        assertEquals(50, store.committedBytes());
        assertEquals(Collections.singleton("b"), store.committedKeys());
    }

    @Test
    public void recreatesDeletedDirectoryForNewTempFile() throws IOException {
        put("a", new byte[10], null);
        assertTrue(new File(dir, "a.seg").delete());
        assertTrue(dir.delete());

        File temp = store.newTempFile("b");

        assertTrue(temp.isFile());
        assertEquals(dir, temp.getParentFile());
    }

    @Test
    public void newTempFileFailsWhenDirectoryCannotBeCreated() throws IOException {
        File plainFile = tmp.newFile("not-a-dir");
        SegmentStore broken = new SegmentStore(new File(plainFile, "s1"), "u");

        try {
            broken.newTempFile("k");
            fail("expected IOException");
        } catch (IOException expected) {
        }
    }

    @Test
    public void commitFailsWhenTempFileIsMissing() {
        try {
            store.commit("k", new File(dir, "missing.tmp"), 1, null);
            fail("expected IOException");
        } catch (IOException expected) {
        }
        assertFalse(store.isCommitted("k"));
    }

    @Test
    public void removeDeletesFileAndRecord() throws IOException {
        put("a", new byte[10], null);
        put("b", new byte[20], null);

        store.remove("a");
        store.remove("never-committed");

        assertFalse(new File(dir, "a.seg").exists());
        assertFalse(store.isCommitted("a"));
        assertEquals(20, store.committedBytes());
    }

    @Test
    public void committedKeysIsASnapshot() throws IOException {
        put("a", new byte[1], null);
        Set<String> snapshot = store.committedKeys();

        put("b", new byte[1], null);

        assertEquals(Collections.singleton("a"), snapshot);
        assertEquals(new HashSet<>(Arrays.asList("a", "b")), store.committedKeys());
    }

    @Test
    public void deleteAllRemovesDirectoryAndRecords() throws IOException {
        put("a", new byte[10], null);
        store.newTempFile("b");

        store.deleteAll();

        assertFalse(dir.exists());
        assertTrue(store.committedKeys().isEmpty());
        assertEquals(0, store.committedBytes());
        store.deleteAll();
    }
}
