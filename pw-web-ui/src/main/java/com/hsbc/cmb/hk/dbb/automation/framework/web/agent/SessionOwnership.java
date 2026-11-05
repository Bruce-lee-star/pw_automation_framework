package com.hsbc.cmb.hk.dbb.automation.framework.web.agent;

import java.util.concurrent.locks.ReentrantLock;
import java.util.function.Supplier;

/**
 * 通道会话所有权（P0 第 4 项 · 并发所有权地基）。
 *
 * <p>Playwright 的 Page 不是线程安全的：同一 page 会话若被多个 {@code StepDriver} / 多线程交错驱动，
 * 会抛并发异常或产生不可复现的竞态。本接口把「独占性」显式建模为可注入契约，使通道成为其所属
 * page 会话的唯一驱动者——所有命令经 {@link #runExclusive} 串行执行。</p>
 *
 * <p>默认实现 {@link #locked()} 基于 {@link ReentrantLock}（可重入，避免未来嵌套调用自杀）；
 * 测试可注入自定义实现验证串行化，而不依赖真实浏览器。</p>
 */
public interface SessionOwnership {

    /** 独占执行：同一所有权实例的多次调用严格串行，临界区内至多一个线程。 */
    <T> T runExclusive(Supplier<T> action);

    /** 默认实现：基于 {@link ReentrantLock} 的互斥所有权（可重入）。 */
    static SessionOwnership locked() {
        return new Locked();
    }

    /** 基于可重入锁的会话所有权实现。 */
    class Locked implements SessionOwnership {
        private final ReentrantLock lock = new ReentrantLock();

        @Override
        public <T> T runExclusive(Supplier<T> action) {
            lock.lock();
            try {
                return action.get();
            } finally {
                lock.unlock();
            }
        }
    }
}
