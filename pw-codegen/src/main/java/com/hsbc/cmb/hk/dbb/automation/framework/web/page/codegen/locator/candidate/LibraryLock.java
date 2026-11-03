package com.hsbc.cmb.hk.dbb.automation.framework.web.page.codegen.locator.candidate;

import java.io.IOException;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.channels.OverlappingFileLockException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;

/**
 * 候选库写操作的<b>排他锁</b>：拿不到锁就失败，不排队、不强写。
 *
 * <p>候选库是多人协作的审批台账。两个人同时审批时，若"后写覆盖前写"，丢掉的不是一条日志，
 * 而是一条<b>审批记录</b>——它背后是有人签名背书的"这条定位器可用于生产"。这类丢失事后无法察觉，
 * 所以这里的语义是"要么拿到锁，要么报错让人重试"，绝不做乐观覆盖。</p>
 *
 * <p>锁文件（{@code <库>.lock}）<b>刻意不删除</b>：删除会让"另一个进程刚打开同一 inode"的
 * 竞态窗口重新出现。</p>
 */
final class LibraryLock implements AutoCloseable {

    private final FileChannel channel;
    private final FileLock lock;

    private LibraryLock(FileChannel channel, FileLock lock) {
        this.channel = channel;
        this.lock = lock;
    }

    static LibraryLock acquire(Path libraryFile) throws IOException {
        Path absolute = libraryFile.toAbsolutePath();
        Path parent = absolute.getParent();
        if (parent != null) {
            Files.createDirectories(parent);
        }
        Path lockFile = absolute.resolveSibling(absolute.getFileName() + ".lock");
        FileChannel channel = FileChannel.open(lockFile,
                StandardOpenOption.CREATE, StandardOpenOption.WRITE);
        FileLock lock;
        try {
            lock = channel.tryLock();
        } catch (OverlappingFileLockException e) {
            channel.close();
            throw new LibraryConflictException("另一个线程正在修改 " + libraryFile.toAbsolutePath());
        } catch (IOException e) {
            channel.close();
            throw new LibraryConflictException("无法获取候选库锁：" + lockFile + " - " + e.getMessage());
        }
        if (lock == null) {
            channel.close();
            throw new LibraryConflictException("另一个进程正在修改 " + libraryFile.toAbsolutePath()
                    + "；已放弃本次写入（无锁覆盖会静默丢掉别人的审批记录）");
        }
        return new LibraryLock(channel, lock);
    }

    @Override
    public void close() throws IOException {
        try {
            lock.release();
        } finally {
            channel.close();
        }
    }
}
