package com.hsbc.cmb.hk.dbb.automation.framework.common.rule;

import org.junit.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Predicate;

import static org.junit.Assert.*;

public class RuleRegistryTest {

    /** 简单上下文:携带一个可变的 int 值,供规则匹配/动作使用。 */
    static final class Ctx {
        int value;
        Ctx(int v) { this.value = v; }
    }

    /** 记录命中与次数的测试规则。 */
    static final class RecordingRule implements Rule<Ctx> {
        private final String id;
        private final Predicate<Ctx> pred;
        private final boolean oneShot;
        final List<Integer> appliedAt = new ArrayList<>();
        final AtomicInteger applyCount = new AtomicInteger();

        RecordingRule(String id, Predicate<Ctx> pred) { this(id, pred, true); }
        RecordingRule(String id, Predicate<Ctx> pred, boolean oneShot) {
            this.id = id; this.pred = pred; this.oneShot = oneShot;
        }
        @Override public String name() { return id; }
        @Override public boolean matches(Ctx c) { return pred.test(c); }
        @Override public void apply(Ctx c) { appliedAt.add(c.value); applyCount.incrementAndGet(); }
        @Override public boolean oneShot() { return oneShot; }
    }

    @Test
    public void register_returnsIdempotentHandle() {
        RuleRegistry<Ctx> reg = new RuleRegistry<>();
        AutoCloseable h = reg.register(new RecordingRule("r1", c -> true));
        assertNotNull(h);
        assertEquals(1, reg.size());
    }

    @Test
    public void matchAll_firesEveryMatchingRule_notOnlyFirst() {
        RuleRegistry<Ctx> reg = new RuleRegistry<>();
        RecordingRule a = new RecordingRule("a", c -> c.value > 0);
        RecordingRule b = new RecordingRule("b", c -> c.value > 0);
        RecordingRule noHit = new RecordingRule("c", c -> c.value < 0);   // 不命中
        reg.register(a); reg.register(b); reg.register(noHit);

        List<Rule<Ctx>> fired = reg.matchAll(new Ctx(1));

        assertEquals(2, fired.size());
        assertEquals(1, a.applyCount.get());
        assertEquals(1, b.applyCount.get());
        assertEquals(0, noHit.applyCount.get());
        assertEquals(1, reg.size());   // 命中的 one-shot 已注销,未命中的 noHit 仍保留
    }

    @Test
    public void matchAll_nonOneShotRulesRemain() {
        RuleRegistry<Ctx> reg = new RuleRegistry<>();
        RecordingRule persistent = new RecordingRule("p", c -> true, false);
        reg.register(persistent);

        reg.matchAll(new Ctx(1));
        assertEquals(1, reg.size());                  // 仍注册
        reg.matchAll(new Ctx(1));
        assertEquals(2, persistent.applyCount.get()); // 可多次命中
    }

    @Test
    public void matchFirst_stopsAtFirstMatch() {
        RuleRegistry<Ctx> reg = new RuleRegistry<>();
        RecordingRule a = new RecordingRule("a", c -> true);
        RecordingRule b = new RecordingRule("b", c -> true);
        reg.register(a); reg.register(b);

        Optional<Rule<Ctx>> hit = reg.matchFirst(new Ctx(1));
        assertTrue(hit.isPresent());
        assertEquals(1, a.applyCount.get());   // 仅首个执行
        assertEquals(0, b.applyCount.get());
        assertEquals(1, reg.size());           // 首个 one-shot 已注销
    }

    @Test
    public void closeHandle_isIdempotent() throws Exception {
        RuleRegistry<Ctx> reg = new RuleRegistry<>();
        AutoCloseable h = reg.register(new RecordingRule("r", c -> true));
        h.close();
        h.close();                             // 再次 close 不应抛异常
        assertEquals(0, reg.size());
    }

    @Test
    public void tryWithResources_unregistersWhenBlockExits() throws Exception {
        RuleRegistry<Ctx> reg = new RuleRegistry<>();
        RecordingRule r = new RecordingRule("r", c -> false); // 块内未触发
        try (AutoCloseable h = reg.register(r)) {
            assertEquals(1, reg.size());
            reg.matchAll(new Ctx(-1));         // 未命中,不抛异常即可
        }
        assertEquals(0, reg.size());           // 块退出自动注销
    }

    @Test
    public void concurrentRegisterAndMatch_noExceptions() throws InterruptedException {
        RuleRegistry<Ctx> reg = new RuleRegistry<>();
        int threads = 8, perThread = 500;
        ExecutorService es = Executors.newFixedThreadPool(threads);
        CountDownLatch start = new CountDownLatch(1);
        List<Runnable> tasks = new ArrayList<>();
        for (int i = 0; i < threads; i++) {
            final int id = i;
            tasks.add(() -> {
                try { start.await(); } catch (InterruptedException ignored) { Thread.currentThread().interrupt(); }
                for (int j = 0; j < perThread; j++) {
                    Rule<Ctx> rule = new RecordingRule("r-" + id + "-" + j, c -> c.value >= 0);
                    try (AutoCloseable h = reg.register(rule)) {
                        reg.matchAll(new Ctx(j % 2));
                    } catch (Exception e) {
                        throw new RuntimeException(e);
                    }
                }
            });
        }
        tasks.forEach(es::submit);
        start.countDown();
        es.shutdown();
        assertTrue(es.awaitTermination(30, TimeUnit.SECONDS));
        assertEquals(0, reg.size());           // 所有 try-with-resources 结束后应为空
    }
}
