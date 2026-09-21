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
 * Periodically re-places logical tasks waiting for an eligible node.
 *
 * Policy:
 *
 * FIFO-PREFERRED, NON-BLOCKING.
 *
 * Pending tasks are considered in insertion order. An older pending task
 * therefore gets the first scheduling opportunity on every recovery tick.
 *
 * However, an older task that is currently ineligible does NOT block younger
 * tasks that are independently eligible.
 *
 * Example:
 *
 *   T1 -> incompatible with current nodes
 *   T2 -> compatible
 *   T3 -> compatible
 *
 * Recovery may therefore execute:
 *
 *   T2
 *   T3
 *   T1 remains pending
 *
 * This avoids head-of-line blocking while preserving age preference.
 *
 * Dependency ordering is intentionally not modeled here. If Phoenix later
 * supports task DAGs/workflows, dependency constraints should be handled
 * separately from this queueing policy.
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

    /**
     * Performs one recovery tick.
     *
     * snapshot() gives us a detached FIFO-ordered view of pending work.
     * Tasks may safely be removed from the real queue while iterating.
     */
    private void tick() {

        for (PendingTaskQueue.Pending pending : pendingQueue.snapshot()) {

            String taskId = pending.task().id();

            /*
             * Expiration is evaluated independently for every pending task.
             * An expired T1 must not prevent T2/T3 from being considered.
             */
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

                /*
                 * This is deliberately synchronous.
                 *
                 * Because this scheduler owns a single thread, the next
                 * pending task is not attempted until this placement attempt
                 * has completely returned.
                 *
                 * Therefore an older pending task gets first claim on a
                 * contended node during a single tick.
                 */
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

                /*
                 * Outcome.Waiting is intentionally NOT treated as a reason
                 * to stop the tick.
                 *
                 * The task remains in the queue in its original position,
                 * while younger tasks still receive their own scheduling
                 * opportunity.
                 */

            } catch (Exception e) {

                /*
                 * A recovery error must not terminate recovery of all
                 * subsequent pending tasks.
                 *
                 * Keep this task pending so that the next tick can retry it.
                 */
                System.out.printf(
                        "[Recovery] Re-placement of %s failed: %s%n",
                        taskId,
                        e.getMessage()
                );
            }
        }
    }
}
