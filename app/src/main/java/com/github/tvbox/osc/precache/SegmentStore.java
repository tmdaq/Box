package com.github.tvbox.osc.precache;

import java.io.File;
import java.io.IOException;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 一个会话目录里已提交的分片（design.md D6）。只有已提交的分片记在内存里；
 * 文件名是 {@code <key>.seg}，下载中的临时文件是 {@code <key>.<下载序号>.tmp}。
 */
class SegmentStore {
    /** 进程内递增，保证两次下载不会写同一个临时文件。 */
    private static final AtomicLong TEMP_SEQUENCE = new AtomicLong();

    private static final class Entry {
        final long size;
        final String contentType;

        Entry(long size, String contentType) {
            this.size = size;
            this.contentType = contentType;
        }
    }

    private final File dir;
    private final String playlistUrl;
    private final Map<String, Entry> committed = new HashMap<>();
    private long committedBytes;

    SegmentStore(File dir, String playlistUrl) {
        this.dir = dir;
        this.playlistUrl = playlistUrl;
    }

    String playlistUrl() {
        return playlistUrl;
    }

    /** 已提交而且文件还在时返回文件；文件被外部删掉时删除这条记录，返回 null。会做一次 stat。 */
    synchronized File committedFile(String key) {
        if (!committed.containsKey(key)) return null;
        File file = fileOf(key);
        if (file.isFile()) return file;
        forget(key);
        return null;
    }

    /** 只查内存，不做 IO。 */
    synchronized boolean isCommitted(String key) {
        return committed.containsKey(key);
    }

    synchronized String contentType(String key) {
        Entry entry = committed.get(key);
        return entry == null ? null : entry.contentType;
    }

    /** 先 mkdirs，再创建文件；失败抛 IOException。 */
    File newTempFile(String key) throws IOException {
        dir.mkdirs();
        File temp = new File(dir, key + "." + TEMP_SEQUENCE.incrementAndGet() + ".tmp");
        if (!temp.createNewFile()) throw new IOException("cannot create " + temp);
        return temp;
    }

    synchronized void commit(String key, File temp, long size, String contentType) throws IOException {
        File target = fileOf(key);
        forget(key);
        target.delete();
        if (!temp.renameTo(target)) throw new IOException("cannot rename " + temp + " to " + target);
        committed.put(key, new Entry(size, contentType));
        committedBytes += size;
    }

    /** 删文件和记录。 */
    synchronized void remove(String key) {
        forget(key);
        fileOf(key).delete();
    }

    /** 快照。 */
    synchronized Set<String> committedKeys() {
        return new HashSet<>(committed.keySet());
    }

    synchronized long committedBytes() {
        return committedBytes;
    }

    /** 已提交分片的平均大小；没有已提交的分片时返回 fallback。 */
    synchronized long averageSize(long fallback) {
        return committed.isEmpty() ? fallback : committedBytes / committed.size();
    }

    /** 递归删除目录，清空记录。只在 ioExecutor 上调用。 */
    void deleteAll() {
        synchronized (this) {
            committed.clear();
            committedBytes = 0;
        }
        deleteRecursively(dir);
    }

    private File fileOf(String key) {
        return new File(dir, key + ".seg");
    }

    private void forget(String key) {
        Entry entry = committed.remove(key);
        if (entry != null) committedBytes -= entry.size;
    }

    private static void deleteRecursively(File file) {
        File[] children = file.listFiles();
        if (children != null) {
            for (File child : children) deleteRecursively(child);
        }
        file.delete();
    }
}
