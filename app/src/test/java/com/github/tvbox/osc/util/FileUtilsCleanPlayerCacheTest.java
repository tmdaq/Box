package com.github.tvbox.osc.util;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import com.github.tvbox.osc.precache.HlsPrecache;

import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Executor;

public class FileUtilsCleanPlayerCacheTest {

    @Rule
    public TemporaryFolder tmp = new TemporaryFolder();

    private static final long NOW = 1700000000000L;
    private static final Executor SYNC = Runnable::run;

    private File cacheDir;
    private File externalCacheDir;

    @Before
    public void setUp() throws IOException {
        cacheDir = tmp.newFolder("cache");
        externalCacheDir = tmp.newFolder("external");
    }

    private void clean() {
        clean(SYNC);
    }

    private void clean(Executor background) {
        FileUtils.cleanPlayerCache(cacheDir.getAbsolutePath(), externalCacheDir.getAbsolutePath(), NOW, background);
    }

    /** 记下提交的任务，测试决定什么时候执行，用来区分主线程上做的事和后台做的事。 */
    private static final class DeferredExecutor implements Executor {
        final List<Runnable> tasks = new ArrayList<>();

        @Override
        public void execute(Runnable task) {
            tasks.add(task);
        }

        void runAll() {
            for (Runnable task : tasks) task.run();
        }
    }

    private File hlsCache() {
        return new File(externalCacheDir, HlsPrecache.CACHE_DIR_NAME);
    }

    private File trash(long timestamp) {
        return new File(externalCacheDir, HlsPrecache.CACHE_DIR_NAME + ".trash-" + timestamp);
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
    public void deletesHlsPrecacheDirUnderExternalCacheDir() throws IOException {
        newFile(externalCacheDir, HlsPrecache.CACHE_DIR_NAME + "/s1/a.seg");
        newFile(externalCacheDir, HlsPrecache.CACHE_DIR_NAME + "/b.tmp");

        clean();

        assertFalse(hlsCache().exists());
        assertFalse(trash(NOW).exists());
    }

    @Test
    public void movesHlsCacheAsideOnCallingThreadAndDeletesItInBackground() throws IOException {
        newFile(externalCacheDir, HlsPrecache.CACHE_DIR_NAME + "/s1/a.seg");
        DeferredExecutor background = new DeferredExecutor();

        clean(background);

        assertFalse(hlsCache().exists());
        assertTrue(new File(trash(NOW), "s1/a.seg").exists());

        background.runAll();

        assertFalse(trash(NOW).exists());
    }

    @Test
    public void deletesTrashLeftByEarlierLaunchesInBackground() throws IOException {
        newFile(externalCacheDir, HlsPrecache.CACHE_DIR_NAME + ".trash-1/s1/a.seg");
        newFile(externalCacheDir, HlsPrecache.CACHE_DIR_NAME + ".trash-2/b.tmp");
        DeferredExecutor background = new DeferredExecutor();

        clean(background);

        assertTrue(trash(1).exists());
        assertTrue(trash(2).exists());

        background.runAll();

        assertFalse(trash(1).exists());
        assertFalse(trash(2).exists());
    }

    @Test
    public void deletesHlsCacheSynchronouslyWhenRenameFails() throws IOException {
        newFile(externalCacheDir, HlsPrecache.CACHE_DIR_NAME + "/s1/a.seg");
        // 目标位置已经是非空目录，rename 失败
        newFile(externalCacheDir, HlsPrecache.CACHE_DIR_NAME + ".trash-" + NOW + "/blocker");

        clean(new DeferredExecutor());

        assertFalse(hlsCache().exists());
    }

    @Test
    public void keepsOtherFilesInExternalCacheDir() throws IOException {
        File keep = newFile(externalCacheDir, "qjscache_x.js");
        File keepDir = newFile(externalCacheDir, "other/c.bin");
        File lookalike = newFile(externalCacheDir, HlsPrecache.CACHE_DIR_NAME + "-other/d.bin");
        newFile(externalCacheDir, "ijkcaches/a.bin");
        newFile(externalCacheDir, HlsPrecache.CACHE_DIR_NAME + "/s1/a.seg");

        clean();

        assertTrue(keep.exists());
        assertTrue(keepDir.exists());
        assertTrue(lookalike.exists());
        // 对照：同一次清理确实删掉了要清理的目录
        assertFalse(new File(externalCacheDir, "ijkcaches").exists());
        assertFalse(hlsCache().exists());
    }

    @Test
    public void skipsMissingDirectoriesAndStillCleansTheRest() throws IOException {
        // thunder、jpali/Downloads、hlscache 都不存在，只有 ijkcaches 在
        newFile(externalCacheDir, "ijkcaches/a.bin");

        clean();

        assertTrue(cacheDir.isDirectory());
        assertTrue(externalCacheDir.isDirectory());
        // 对照：缺失的目录不影响清理其余的
        assertFalse(new File(externalCacheDir, "ijkcaches").exists());
    }
}
