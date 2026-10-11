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

import cn.hutool.json.JSONUtil;
import com.nageoffer.ai.ragent.framework.trace.RagTraceObservation;
import com.nageoffer.ai.ragent.rag.config.RagTraceProperties;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

@Component
@RequiredArgsConstructor
public class RagTraceSerializer {
    private final RagTraceProperties properties;
    private static final Set<String> CONTENT_KEYS = Set.of("question", "originalQuestion", "rewrittenQuestion",
            "subQuestions", "textPreview", "context", "rawResponse", "prompt", "answer");

    public RagTraceObservation.Snapshot snapshot(String section, Object value) {
        boolean[] truncated = {false};
        Object copy = normalize(value, section, truncated, 0, Math.max(0, properties.getMaxCandidates()), false);
        return new RagTraceObservation.Snapshot(copy, truncated[0]);
    }

    public String serialize(Map<String, Object> value) {
        boolean[] truncated = {false};
        @SuppressWarnings("unchecked")
        Map<String, Object> normalized = (Map<String, Object>) normalize(value, "", truncated, 0,
                Math.max(0, properties.getMaxCandidates()), false);
        Map<String, Object> capture = new LinkedHashMap<>();
        capture.put("contentCaptured", properties.isCaptureContent());
        capture.put("truncated", truncated[0] || wasTruncated(value));
        normalized.put("capture", capture);
        String json = JSONUtil.toJsonStr(normalized);
        int byteLimit = Math.max(1024, properties.getMaxNodeBytes());
        if (json.getBytes(StandardCharsets.UTF_8).length <= byteLimit) return json;
        // Shed previews first, then shrink lists while preserving counts, decisions and valid JSON.
        capture.put("truncated", true);
        capture.put("reason", "NODE_BYTE_LIMIT");
        int listLimit = Math.max(0, properties.getMaxCandidates());
        while (true) {
            @SuppressWarnings("unchecked")
            Map<String, Object> reduced = (Map<String, Object>) normalize(value, "", truncated, 0, listLimit, true);
            reduced.put("capture", capture);
            json = JSONUtil.toJsonStr(reduced);
            if (json.getBytes(StandardCharsets.UTF_8).length <= byteLimit) return json;
            if (listLimit == 0) break;
            listLimit /= 2;
        }
        return JSONUtil.toJsonStr(Map.of("schemaVersion", 1, "stage", value.getOrDefault("stage", "unknown"),
                "capture", capture));
    }

    private boolean wasTruncated(Object value) {
        if (value instanceof Map<?, ?> map) {
            if (Boolean.TRUE.equals(map.get("truncated"))) return true;
            return map.values().stream().anyMatch(this::wasTruncated);
        }
        return value instanceof Collection<?> items && items.stream().anyMatch(this::wasTruncated);
    }

    private Object normalize(Object value, String key, boolean[] truncated, int depth, int listLimit, boolean compact) {
        if (depth > 14) { truncated[0] = true; return "[depth limit]"; }
        if (value == null || value instanceof Boolean) return value;
        if (value instanceof Number number) {
            return Double.isFinite(number.doubleValue()) ? number : null;
        }
        if (value instanceof CharSequence text) {
            // A subQuestions list may contain strings or structured intent records. Only hide strings.
            if (!properties.isCaptureContent() && CONTENT_KEYS.contains(key)) return "[content disabled]";
            if (compact && "textPreview".equals(key)) return "[omitted: node byte limit]";
            int limit = "textPreview".equals(key) ? properties.getMaxPreviewLength() : properties.getMaxContentLength();
            if (compact) limit = Math.min(limit, 512);
            if (text.length() > Math.max(0, limit)) {
                truncated[0] = true;
                return text.subSequence(0, Math.max(0, limit)).toString();
            }
            return text.toString();
        }
        if (value instanceof Map<?, ?> map) {
            Map<String, Object> result = new LinkedHashMap<>();
            for (var entry : map.entrySet()) {
                String field = String.valueOf(entry.getKey());
                if (field.toLowerCase(Locale.ROOT).matches(".*(password|authorization|api[_-]?key|secret|access[_-]?token).*")) {
                    result.put(field, "[redacted]");
                } else result.put(field, normalize(entry.getValue(), field, truncated, depth + 1, listLimit, compact));
            }
            if (result.containsKey("recordedCount") && result.get("candidates") instanceof List<?> candidates) {
                result.put("recordedCount", candidates.size());
                if (result.get("totalCount") instanceof Number total && candidates.size() < total.intValue()) {
                    result.put("truncated", true);
                }
            }
            return result;
        }
        if (value instanceof Collection<?> collection) {
            List<Object> result = new ArrayList<>();
            for (Object item : collection) {
                if (result.size() >= listLimit) { truncated[0] = true; break; }
                result.add(normalize(item, key, truncated, depth + 1, listLimit, compact));
            }
            return result;
        }
        return normalize(String.valueOf(value), key, truncated, depth + 1, listLimit, compact);
    }
}
