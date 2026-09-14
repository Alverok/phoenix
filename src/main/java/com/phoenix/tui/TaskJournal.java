package com.phoenix.tui;

import com.google.inject.Singleton;
import com.phoenix.model.TaskDescriptor;
import com.phoenix.model.TaskResult;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

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

    public void completed(
            String taskId,
            TaskResult result) {

        tasks.computeIfPresent(
                taskId,
                (ignored, state) ->
                        state.withResult(
                                result.status(),
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
            String output
    ) {

        TaskState withAssignment(String node) {
            return new TaskState(
                    taskId,
                    type,
                    node,
                    "RUNNING",
                    submittedAt,
                    completedAt,
                    output
            );
        }

        TaskState withResult(
                String finalStatus,
                Instant completedAt) {

            return new TaskState(
                    taskId,
                    type,
                    nodeName,
                    finalStatus,
                    submittedAt,
                    completedAt,
                    output
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
