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

package com.nageoffer.ai.ragent.rag.trace;

import cn.hutool.core.util.IdUtil;
import com.nageoffer.ai.ragent.framework.cancellation.TaskCancellation;
import com.nageoffer.ai.ragent.framework.trace.RagTraceContext;
import com.nageoffer.ai.ragent.framework.trace.RagTraceObservation;
import com.nageoffer.ai.ragent.rag.config.RagTraceProperties;
import com.nageoffer.ai.ragent.rag.dao.entity.RagTraceNodeDO;
import com.nageoffer.ai.ragent.rag.service.RagTraceRecordService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.Date;

@Slf4j
@Component
@RequiredArgsConstructor
public class RagTraceRecorder {
    private final RagTraceRecordService records;
    private final RagTraceProperties properties;
    private final RagTraceSerializer serializer;

    public static RagTraceRecorder disabled() { return new RagTraceRecorder(null, null, null); }

    public void context(String text, java.util.Set<String> eligibleIntents, String consumer) {
        try (var span = start("retrieval-context", "RETRIEVE_CONTEXT"); var scope = span.activate()) {
            span.record("output", RagTraceData.map("context", text, "eligibleIntentIds", eligibleIntents,
                    "consumer", consumer, "characterCount", text == null ? 0 : text.length()));
        }
    }

    public NodeSpan start(String name, String type) { return start(name, type, null, null); }

    public NodeSpan start(String name, String type, String className, String methodName) {
        String traceId = RagTraceContext.getTraceId();
        if (properties == null || !properties.isEnabled() || traceId == null || traceId.isBlank()) return new NodeSpan();
        String nodeId = IdUtil.getSnowflakeNextIdStr();
        long started = System.currentTimeMillis();
        try {
            records.startNode(RagTraceNodeDO.builder().traceId(traceId).nodeId(nodeId)
                    .parentNodeId(RagTraceContext.currentNodeId()).depth(RagTraceContext.depth())
                    .nodeName(name).nodeType(type).className(className).methodName(methodName)
                    .status("RUNNING").startTime(new Date(started)).build());
            return new NodeSpan(traceId, started, new RagTraceObservation(nodeId, name,
                    RagTraceContext.getSubQuestionId(), Math.max(0, properties.getMaxCandidates()), serializer::snapshot));
        } catch (Exception error) {
            log.warn("Trace startNode failed, name={}", name, error);
            return new NodeSpan();
        }
    }

    public final class NodeSpan implements AutoCloseable {
        private final String traceId;
        private final long started;
        private final RagTraceObservation observation;
        private NodeSpan() { this(null, 0, null); }
        private NodeSpan(String traceId, long started, RagTraceObservation observation) {
            this.traceId = traceId; this.started = started; this.observation = observation;
        }
        public RagTraceContext.Scope activate() { return RagTraceContext.activate(observation); }
        public void record(String key, Object value) { if (observation != null) observation.record(key, value); }
        public void success() {
            if (TaskCancellation.isCancelled()) finish("CANCELLED", null, "CANCELLED");
            else finish("SUCCESS", null, null);
        }
        public void error(Throwable error) {
            if (TaskCancellation.isCancellation(error)) finish("CANCELLED", error, "CANCELLED");
            else finish("ERROR", error, "ERROR");
        }
        public void timeout(Throwable error) { finish("ERROR", error, "TIMEOUT"); }
        public void cancelled() { finish("CANCELLED", null, "CANCELLED"); }
        private void finish(String status, Throwable error, String outcome) {
            if (observation == null) return;
            String message = error == null ? null : error.getClass().getSimpleName() + ": " + error.getMessage();
            var snapshot = observation.finish(outcome, message);
            if (snapshot == null) return;
            try {
                if (message != null) message = message.substring(0, Math.min(message.length(), Math.max(0, properties.getMaxErrorLength())));
                records.finishNode(traceId, observation.nodeId(), status, message, new Date(),
                        Math.max(0, System.currentTimeMillis() - started), serializer.serialize(snapshot));
            } catch (Exception failure) { log.warn("Trace finishNode failed, nodeId={}", observation.nodeId(), failure); }
        }
        @Override public void close() { success(); }
    }
}
