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
import com.nageoffer.ai.ragent.framework.context.UserContext;
import com.nageoffer.ai.ragent.framework.trace.RagTraceContext;
import com.nageoffer.ai.ragent.framework.trace.RagTraceLink;
import com.nageoffer.ai.ragent.rag.config.RagTraceProperties;
import com.nageoffer.ai.ragent.rag.dao.entity.RagTraceRunDO;
import com.nageoffer.ai.ragent.rag.service.RagTraceRecordService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.Date;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Supplier;

@Component
@Slf4j
@RequiredArgsConstructor
public class KnowledgeSearchTraceRunner {
    private final RagTraceRecordService records;
    private final RagTraceProperties properties;
    private final RagTraceSerializer serializer;

    public static KnowledgeSearchTraceRunner disabled() { return new KnowledgeSearchTraceRunner(null, null, null); }

    public String run(String query, Supplier<String> business) {
        if (properties == null || !properties.isEnabled()) return business.get();
        var link = RagTraceLink.current();
        if (RagTraceContext.getTraceId() != null) {
            if (link != null) link.created(RagTraceContext.getTraceId(), () -> { });
            return business.get();
        }
        var attributes = link == null ? RagTraceData.map() : link.attributes();
        String taskId = stringAttribute(attributes.get("taskId"));
        String traceId = IdUtil.getSnowflakeNextIdStr();
        long started = System.currentTimeMillis();
        var extra = RagTraceData.map("schemaVersion", 1, "stage", "agent-knowledge-search", "question", query,
                "questionLength", query == null ? 0 : query.length(), "association", attributes);
        try {
            records.startRun(RagTraceRunDO.builder().traceId(traceId).traceName("agent-knowledge-search")
                    .entryMethod("KnowledgeSearchFacade#search").conversationId(stringAttribute(attributes.get("conversationId")))
                    .taskId(taskId).userId(attributes.get("userId") instanceof String id ? id : UserContext.getUserId())
                    .status("RUNNING").startTime(new Date(started)).extraData(serializer.serialize(extra)).build());
        } catch (Exception error) {
            log.warn("Trace startRun failed for knowledge search", error);
            return business.get();
        }
        AtomicBoolean finished = new AtomicBoolean();
        if (link != null) link.created(traceId, () -> finish(traceId, "CANCELLED", null, started, finished));
        try (var scope = RagTraceContext.withTrace(traceId, taskId)) {
            try {
                String result = business.get();
                finish(traceId, TaskCancellation.isCancelled() ? "CANCELLED" : "SUCCESS", null, started, finished);
                return result;
            } catch (RuntimeException | Error error) {
                finish(traceId, TaskCancellation.isCancellation(error) ? "CANCELLED" : "ERROR", error, started, finished);
                throw error;
            }
        }
    }

    private static String stringAttribute(Object value) { return value == null ? null : String.valueOf(value); }

    private void finish(String id, String status, Throwable error, long start, AtomicBoolean finished) {
        if (!finished.compareAndSet(false, true)) return;
        String message = error == null ? null : error.getClass().getSimpleName() + ": " + error.getMessage();
        if (message != null) message = message.substring(0, Math.min(message.length(), Math.max(0, properties.getMaxErrorLength())));
        try { records.finishRun(id, status, message, new Date(), Math.max(0, System.currentTimeMillis() - start)); }
        catch (Exception failure) { log.warn("Trace finishRun failed for knowledge search, traceId={}", id, failure); }
    }
}
