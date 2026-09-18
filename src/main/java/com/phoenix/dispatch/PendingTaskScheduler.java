package com.phoenix.dispatch;

import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

import com.google.inject.Inject;
import com.google.inject.Singleton;
import com.phoenix.model.TaskFailureReason;
import com.phoenix.model.TaskResult;
import com.phoenix.tui.TaskJournal;

/**
 * Periodically re-places logical tasks that are waiting for an eligible node.
 */
@Singleton
public class PendingTaskScheduler {

    private final PendingTaskQueue pendingQueue;
    private final TaskDispatcher dispatcher;
    private final TaskJournal taskJournal;

    private ScheduledExecutorService ticker;

    @Inject
    public PendingTaskScheduler(
            PendingTaskQueue pendingQueue,
            TaskDispatcher dispatcher,
            TaskJournal taskJournal) {
        this.pendingQueue = pendingQueue;
        this.dispatcher = dispatcher;
        this.taskJournal = taskJournal;
    }

    public synchronized void start() {
        if (ticker != null && !ticker.isShutdown()) {
            return;
        }

        ticker = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread thread = new Thread(
                    r,
                    "phoenix-pending-scheduler"
            );
            thread.setDaemon(true);
            return thread;
        });

        ticker.scheduleAtFixedRate(
                this::tick,
                2,
                2,
                TimeUnit.SECONDS
        );
    }

    public synchronized void stop() {
        if (ticker != null) {
            ticker.shutdownNow();
            ticker = null;
        }
    }

    private void tick() {
        for (PendingTaskQueue.Pending pending : pendingQueue.snapshot()) {
            String taskId = pending.task().id();

            if (pendingQueue.isExpired(pending)) {
                pendingQueue.remove(taskId);

                taskJournal.completed(
                        taskId,
                        new TaskResult(
                                taskId,
                                "FAILED",
                                "No eligible node within "
                                        + pendingQueue.maxWait().toSeconds()
                                        + "s",
                                TaskFailureReason.NO_ELIGIBLE_NODE
                        )
                );

                System.out.printf(
                        "[Recovery] %s gave up: no eligible node within %ds%n",
                        taskId,
                        pendingQueue.maxWait().toSeconds()
                );
                continue;
            }

            try {
                TaskDispatcher.Outcome outcome =
                        dispatcher.replace(pending.task());

                if (outcome instanceof TaskDispatcher.Outcome.Completed completed) {
                    pendingQueue.remove(taskId);
                    System.out.printf(
                            "[Recovery] %s recovered -> %s on %s%n",
                            taskId,
                            completed.result().result().status(),
                            completed.result().nodeDisplayName()
                    );
                }
            } catch (Exception e) {
                System.out.printf(
                        "[Recovery] Re-placement of %s failed: %s%n",
                        taskId,
                        e.getMessage()
                );
            }
        }
    }
}
