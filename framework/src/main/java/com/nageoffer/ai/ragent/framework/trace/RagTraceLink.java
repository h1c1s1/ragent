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

import com.alibaba.ttl.TransmittableThreadLocal;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;

/** Explicit bridge: Reactor/Agent identities enter RAG without a dependency on AgentScope or OTel. */
public final class RagTraceLink {
    private static final TransmittableThreadLocal<RagTraceLink> CURRENT = new TransmittableThreadLocal<>();
    private final Map<String, Object> attributes;
    private final Consumer<String> onCreated;
    private final AtomicBoolean cancelled = new AtomicBoolean();
    private final AtomicReference<Runnable> cancelAction = new AtomicReference<>();

    public RagTraceLink(Map<String, Object> attributes, Consumer<String> onCreated) {
        this.attributes = new LinkedHashMap<>(attributes);
        this.onCreated = onCreated;
    }
    public static RagTraceLink current() { return CURRENT.get(); }
    public Map<String, Object> attributes() { return new LinkedHashMap<>(attributes); }
    public RagTraceContext.Scope activate() {
        RagTraceLink previous = CURRENT.get();
        CURRENT.set(this);
        return () -> { if (previous == null) CURRENT.remove(); else CURRENT.set(previous); };
    }
    public void created(String traceId, Runnable action) {
        cancelAction.set(action);
        try { if (onCreated != null) onCreated.accept(traceId); } catch (RuntimeException ignored) { }
        if (cancelled.get()) action.run();
    }
    public void cancel() {
        cancelled.set(true);
        Runnable action = cancelAction.get();
        if (action != null) action.run();
    }
}
