package cn.lwccxy.trip.business.conversation.service;

import cn.lwccxy.trip.business.conversation.enums.ConversationErrorCode;
import cn.lwccxy.trip.common.exception.BizException;
import org.springframework.stereotype.Component;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Semaphore;
import java.util.concurrent.atomic.AtomicBoolean;

@Component
public class ConversationCoordinator {
    private final ConcurrentHashMap<Long, Entry> entries = new ConcurrentHashMap<>();

    public Lease acquire(Long id, boolean wait) {
        Entry entry = entries.compute(id, (key, old) -> {
            Entry value = old == null ? new Entry() : old;
            value.references++;
            return value;
        });
        boolean acquired = false;
        try {
            if (wait) { entry.semaphore.acquire(); acquired = true; }
            else acquired = entry.semaphore.tryAcquire();
            if (!acquired) throw new BizException(ConversationErrorCode.CONVERSATION_BUSY);
            return new Lease(id, entry);
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            throw new BizException(ConversationErrorCode.CONVERSATION_BUSY);
        } finally {
            if (!acquired) dereference(id, entry);
        }
    }

    private void dereference(Long id, Entry entry) {
        entries.compute(id, (key, current) -> --entry.references == 0 ? null : entry);
    }

    private static final class Entry {
        private final Semaphore semaphore = new Semaphore(1, true);
        private int references;
    }

    public final class Lease implements AutoCloseable {
        private final Long id;
        private final Entry entry;
        private final AtomicBoolean closed = new AtomicBoolean();
        private Lease(Long id, Entry entry) { this.id = id; this.entry = entry; }
        @Override public void close() {
            if (closed.compareAndSet(false, true)) {
                entry.semaphore.release();
                dereference(id, entry);
            }
        }
    }
}
