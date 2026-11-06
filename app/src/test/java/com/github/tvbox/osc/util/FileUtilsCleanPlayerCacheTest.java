package com.github.tvbox.osc.util;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.File;
import java.io.IOException;

public class FileUtilsCleanPlayerCacheTest {

    @Rule
    public TemporaryFolder tmp = new TemporaryFolder();

    private File cacheDir;
    private File externalCacheDir;

    @Before
    public void setUp() throws IOException {
        cacheDir = tmp.newFolder("cache");
        externalCacheDir = tmp.newFolder("external");
    }

    private void clean() {
        FileUtils.cleanPlayerCache(cacheDir.getAbsolutePath(), externalCacheDir.getAbsolutePath());
    }

    private static File newFile(File parent, String relativePath) throws IOException {
        File file = new File(parent, relativePath);
        assertTrue(file.getParentFile().mkdirs() || file.getParentFile().isDirectory());
        assertTrue(file.createNewFile());
        return file;
    }

    @Test
    public void deletesThunderUnderCacheDir() throws IOException {
        newFile(cacheDir, "thunder/sub/a.bin");

        clean();

        assertFalse(new File(cacheDir, "thunder").exists());
    }

    @Test
    public void deletesIjkcachesUnderExternalCacheDir() throws IOException {
        newFile(externalCacheDir, "ijkcaches/sub/a.bin");

        clean();

        assertFalse(new File(externalCacheDir, "ijkcaches").exists());
    }

    @Test
    public void deletesJpaliDownloadsButKeepsRestOfJpali() throws IOException {
        newFile(externalCacheDir, "jpali/Downloads/sub/a.bin");
        File keep = newFile(externalCacheDir, "jpali/other/b.bin");

        clean();

        assertFalse(new File(externalCacheDir, "jpali/Downloads").exists());
        assertTrue(keep.exists());
    }

    @Test
    public void keepsOtherFilesInExternalCacheDir() throws IOException {
        File keep = newFile(externalCacheDir, "qjscache_x.js");
        File keepDir = newFile(externalCacheDir, "other/c.bin");

        clean();

        assertTrue(keep.exists());
        assertTrue(keepDir.exists());
    }

    @Test
    public void doesNothingWhenDirectoriesAreMissing() {
        clean();

        assertTrue(cacheDir.isDirectory());
        assertTrue(externalCacheDir.isDirectory());
    }
}
