package com.jimuqu.solon.claw.cli.acp;

import cn.hutool.core.util.StrUtil;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Bounded FIFO queue for ACP prompt requests.
 * Allows callers to enqueue prompts while a session is busy and drain them
 * in order once the session becomes available.
 */
public class AcpRequestQueue {
    private static final int DEFAULT_CAPACITY = 32;

    private final BlockingQueue<QueuedRequest> queue;
    private final int capacity;
    private final AtomicInteger totalEnqueued = new AtomicInteger(0);
    private final AtomicInteger totalDequeued = new AtomicInteger(0);

    public AcpRequestQueue() {
        this(DEFAULT_CAPACITY);
    }

    public AcpRequestQueue(int capacity) {
        this.capacity = capacity > 0 ? capacity : DEFAULT_CAPACITY;
        this.queue = new ArrayBlockingQueue<QueuedRequest>(this.capacity);
    }

    /**
     * Enqueues a prompt request.
     *
     * @param sessionId  the ACP session this request belongs to
     * @param prompt     the prompt text
     * @return the queued request, or null if the queue is full
     */
    public QueuedRequest enqueue(String sessionId, String prompt) {
        QueuedRequest request = new QueuedRequest(
                StrUtil.nullToEmpty(sessionId).trim(),
                StrUtil.nullToEmpty(prompt),
                System.currentTimeMillis());
        if (queue.offer(request)) {
            totalEnqueued.incrementAndGet();
            return request;
        }
        return null;
    }

    /**
     * Polls the next request from the queue without blocking.
     *
     * @return the next request, or null if the queue is empty
     */
    public QueuedRequest poll() {
        QueuedRequest request = queue.poll();
        if (request != null) {
            totalDequeued.incrementAndGet();
        }
        return request;
    }

    /**
     * Polls the next request, waiting up to {@code timeoutMs} milliseconds.
     *
     * @return the next request, or null if the timeout elapsed
     */
    public QueuedRequest poll(long timeoutMs) throws InterruptedException {
        QueuedRequest request = queue.poll(Math.max(0L, timeoutMs), TimeUnit.MILLISECONDS);
        if (request != null) {
            totalDequeued.incrementAndGet();
        }
        return request;
    }

    /** Returns the number of requests currently in the queue. */
    public int size() {
        return queue.size();
    }

    /** Returns true if the queue is empty. */
    public boolean isEmpty() {
        return queue.isEmpty();
    }

    /** Returns true if the queue has reached its capacity. */
    public boolean isFull() {
        return queue.remainingCapacity() == 0;
    }

    /** Returns the maximum capacity of this queue. */
    public int getCapacity() {
        return capacity;
    }

    /** Returns the total number of requests ever enqueued (including dequeued ones). */
    public int getTotalEnqueued() {
        return totalEnqueued.get();
    }

    /** Returns the total number of requests ever dequeued. */
    public int getTotalDequeued() {
        return totalDequeued.get();
    }

    /** Drains all current requests into a list and returns them. */
    public List<QueuedRequest> drainAll() {
        List<QueuedRequest> result = new ArrayList<QueuedRequest>();
        queue.drainTo(result);
        totalDequeued.addAndGet(result.size());
        return result;
    }

    /** Returns a snapshot of queue stats as a map. */
    public Map<String, Object> stats() {
        Map<String, Object> map = new LinkedHashMap<String, Object>();
        map.put("size", Integer.valueOf(size()));
        map.put("capacity", Integer.valueOf(capacity));
        map.put("full", Boolean.valueOf(isFull()));
        map.put("total_enqueued", Integer.valueOf(totalEnqueued.get()));
        map.put("total_dequeued", Integer.valueOf(totalDequeued.get()));
        return map;
    }

    /** Represents a single queued ACP prompt request. */
    public static class QueuedRequest {
        private final String sessionId;
        private final String prompt;
        private final long enqueuedAt;

        private QueuedRequest(String sessionId, String prompt, long enqueuedAt) {
            this.sessionId = sessionId;
            this.prompt = prompt;
            this.enqueuedAt = enqueuedAt;
        }

        public String getSessionId() { return sessionId; }
        public String getPrompt() { return prompt; }
        public long getEnqueuedAt() { return enqueuedAt; }

        @Override
        public String toString() {
            return "QueuedRequest{sessionId='" + sessionId + "', enqueuedAt=" + enqueuedAt + "}";
        }
    }
}
