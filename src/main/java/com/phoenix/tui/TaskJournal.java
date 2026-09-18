package com.phoenix.tui;

import java.time.Duration;
import java.time.Instant;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import com.google.inject.Singleton;
import com.phoenix.model.TaskDescriptor;
import com.phoenix.model.TaskResult;

@Singleton
public class TaskJournal {

    private final Map<String, TaskState> tasks =
            new ConcurrentHashMap<>();

    public void submitted(TaskDescriptor task) {
        tasks.put(
                task.id(),
                new TaskState(
                        task.id(),
                        task.type(),
                        null,
                        "SUBMITTED",
                        Instant.now(),
                        null,
                        null,
                        1,
                        null
                )
        );
    }

    public void assigned(
            String taskId,
            String nodeName) {

        tasks.computeIfPresent(
                taskId,
                (ignored, state) ->
                        state.withAssignment(nodeName)
        );
    }

    public void waiting(
            String taskId,
            String reason) {

        tasks.computeIfPresent(
                taskId,
                (ignored, state) ->
                        state.withWaiting(reason)
        );
    }

    public void retrying(
            String taskId,
            String nodeName,
            int attempt,
            String reason) {

        tasks.computeIfPresent(
                taskId,
                (ignored, state) ->
                        state.withRetry(
                                nodeName,
                                attempt,
                                reason
                        )
        );
    }

    public void completed(
            String taskId,
            TaskResult result) {

        tasks.computeIfPresent(
                taskId,
                (ignored, state) ->
                        state.withResult(
                                result,
                                Instant.now()
                        )
        );
    }

    public List<TaskState> recent(int limit) {
        return tasks.values()
                .stream()
                .sorted(
                        Comparator.comparing(
                                TaskState::submittedAt
                        ).reversed()
                )
                .limit(limit)
                .toList();
    }

    public record TaskState(
            String taskId,
            String type,
            String nodeName,
            String status,
            Instant submittedAt,
            Instant completedAt,
            String output,
            int attempt,
            String failureReason
    ) {

        TaskState withAssignment(String node) {
            return new TaskState(
                    taskId,
                    type,
                    node,
                    "RUNNING",
                    submittedAt,
                    null,
                    output,
                    attempt,
                    failureReason
            );
        }

        TaskState withWaiting(String reason) {
            return new TaskState(
                    taskId,
                    type,
                    nodeName,
                    "WAITING",
                    submittedAt,
                    null,
                    output,
                    attempt,
                    reason
            );
        }

        TaskState withRetry(
                String node,
                int nextAttempt,
                String reason) {
            return new TaskState(
                    taskId,
                    type,
                    node,
                    "RETRYING",
                    submittedAt,
                    null,
                    output,
                    nextAttempt,
                    reason
            );
        }

        TaskState withResult(
                TaskResult result,
                Instant completedAt) {
            return new TaskState(
                    taskId,
                    type,
                    nodeName,
                    result.status(),
                    submittedAt,
                    completedAt,
                    result.outputData(),
                    attempt,
                    result.failureReason().name()
            );
        }

        public long elapsedMs() {
            Instant end =
                    completedAt != null
                            ? completedAt
                            : Instant.now();

            return Duration.between(
                    submittedAt,
                    end
            ).toMillis();
        }
    }
}
