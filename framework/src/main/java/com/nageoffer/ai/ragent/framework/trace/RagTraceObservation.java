/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.nageoffer.ai.ragent.framework.trace;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.BiFunction;

/** A request-local observation. Closing it freezes all data, including late worker writes. */
public final class RagTraceObservation {
    private final String nodeId;
    private final int maxItems;
    private final BiFunction<String, Object, Snapshot> snapshotter;
    private final Map<String, Object> data = new LinkedHashMap<>();
    private boolean finished;

    public RagTraceObservation(String nodeId, String stage, String subQuestionId, int maxItems,
                               BiFunction<String, Object, Snapshot> snapshotter) {
        this.nodeId = nodeId;
        this.maxItems = maxItems;
        this.snapshotter = snapshotter;
        data.put("schemaVersion", 1);
        data.put("stage", stage);
        if (subQuestionId != null) data.put("subQuestionId", subQuestionId);
    }

    public String nodeId() { return nodeId; }
    public int maxItems() { return maxItems; }

    public synchronized void record(String section, Object value) {
        if (finished) return;
        try {
            Snapshot snapshot = snapshotter.apply(section, value);
            data.put(section, snapshot.value());
            if (snapshot.truncated()) data.put("truncated", true);
        } catch (RuntimeException ignored) { }
    }

    public synchronized void event(String outcome, String reason) {
        if (finished) return;
        try {
            Snapshot snapshot = snapshotter.apply("execution", Map.of("outcome", outcome == null ? "" : outcome,
                    "reason", reason == null ? "" : reason));
            Object previous = data.get("execution");
            if (!(previous instanceof Map<?, ?> execution)
                    || severity(outcome) >= severity(String.valueOf(execution.get("outcome")))) {
                data.put("execution", snapshot.value());
            }
            @SuppressWarnings("unchecked")
            List<Object> events = (List<Object>) data.computeIfAbsent("events", key -> new ArrayList<>());
            if (events.size() < maxItems) events.add(snapshot.value());
            else data.put("truncated", true);
            if (snapshot.truncated()) data.put("truncated", true);
        } catch (RuntimeException ignored) { }
    }

    public synchronized Map<String, Object> finish(String outcome, String reason) {
        if (finished) return null;
        if (outcome != null) event(outcome, reason == null ? "" : reason);
        data.putIfAbsent("execution", Map.of("outcome", "SUCCESS", "reason", ""));
        finished = true;
        return new LinkedHashMap<>(data);
    }

    private static int severity(String outcome) {
        if (outcome == null) return 0;
        return switch (outcome) {
            case "ERROR", "TIMEOUT", "CANCELLED" -> 100;
            case "DEGRADED" -> 80;
            case "FILTERED" -> 20;
            case "SKIPPED" -> 10;
            default -> 0;
        };
    }

    public record Snapshot(Object value, boolean truncated) { }
}
