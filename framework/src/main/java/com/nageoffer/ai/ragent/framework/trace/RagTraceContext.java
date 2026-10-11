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

import java.util.ArrayDeque;
import java.util.Deque;

/**
 * RAG Trace 上下文
 * 使用 TTL 在异步线程池中透传 traceId 与节点栈
 */
public final class RagTraceContext {

    private static final TransmittableThreadLocal<String> TRACE_ID = new TransmittableThreadLocal<>();
    private static final TransmittableThreadLocal<String> TASK_ID = new TransmittableThreadLocal<>();
    private static final TransmittableThreadLocal<String> SUB_QUESTION_ID = new TransmittableThreadLocal<>();
    private static final TransmittableThreadLocal<Deque<RagTraceObservation>> OBSERVATIONS = new TransmittableThreadLocal<>() {
        @Override
        public Deque<RagTraceObservation> copy(Deque<RagTraceObservation> parent) {
            return parent == null ? null : new ArrayDeque<>(parent);
        }
    };
    // 父线程 -> 子线程的 NODE_STACK 必须深拷贝
    // 默认 copy() 返回父值引用，并发子任务会共用同一个 Deque，
    // 互相 push/pop 时父子节点 ID 串挂，trace 层级紊乱
    private static final TransmittableThreadLocal<Deque<String>> NODE_STACK = new TransmittableThreadLocal<>() {
        @Override
        public Deque<String> copy(Deque<String> parentValue) {
            return parentValue == null ? null : new ArrayDeque<>(parentValue);
        }
    };

    private RagTraceContext() {
    }

    public static String getTraceId() {
        return TRACE_ID.get();
    }

    public static String getSubQuestionId() { return SUB_QUESTION_ID.get(); }

    public static Scope withSubQuestion(String id) {
        String previous = SUB_QUESTION_ID.get();
        SUB_QUESTION_ID.set(id);
        return () -> { if (previous == null) SUB_QUESTION_ID.remove(); else SUB_QUESTION_ID.set(previous); };
    }

    public static Scope withTrace(String traceId, String taskId) {
        String previousTrace = TRACE_ID.get();
        String previousTask = TASK_ID.get();
        String previousQuestion = SUB_QUESTION_ID.get();
        Deque<String> previousNodes = NODE_STACK.get();
        Deque<RagTraceObservation> previousObservations = OBSERVATIONS.get();
        clear();
        TRACE_ID.set(traceId);
        if (taskId != null) TASK_ID.set(taskId);
        return () -> {
            clear();
            if (previousTrace != null) TRACE_ID.set(previousTrace);
            if (previousTask != null) TASK_ID.set(previousTask);
            if (previousQuestion != null) SUB_QUESTION_ID.set(previousQuestion);
            if (previousNodes != null) NODE_STACK.set(previousNodes);
            if (previousObservations != null) OBSERVATIONS.set(previousObservations);
        };
    }

    public static Scope activate(RagTraceObservation observation) {
        if (observation == null) return () -> { };
        Deque<String> previousNodes = NODE_STACK.get();
        Deque<RagTraceObservation> previousObservations = OBSERVATIONS.get();
        Deque<String> nodes = previousNodes == null ? new ArrayDeque<>() : new ArrayDeque<>(previousNodes);
        Deque<RagTraceObservation> observations = previousObservations == null
                ? new ArrayDeque<>() : new ArrayDeque<>(previousObservations);
        nodes.push(observation.nodeId());
        observations.push(observation);
        NODE_STACK.set(nodes);
        OBSERVATIONS.set(observations);
        return () -> {
            if (previousNodes == null) NODE_STACK.remove(); else NODE_STACK.set(previousNodes);
            if (previousObservations == null) OBSERVATIONS.remove(); else OBSERVATIONS.set(previousObservations);
        };
    }

    private static RagTraceObservation currentObservation() {
        Deque<RagTraceObservation> observations = OBSERVATIONS.get();
        RagTraceObservation observation = observations == null ? null : observations.peek();
        return observation != null && observation.nodeId().equals(currentNodeId()) ? observation : null;
    }

    public static int captureLimit() {
        RagTraceObservation observation = currentObservation();
        return observation == null ? 0 : observation.maxItems();
    }

    public static void record(String section, Object value) {
        RagTraceObservation observation = currentObservation();
        if (observation != null) observation.record(section, value);
    }

    public static void event(String outcome, String reason) {
        RagTraceObservation observation = currentObservation();
        if (observation != null) observation.event(outcome, reason);
    }

    public interface Scope extends AutoCloseable {
        @Override void close();
    }

    public static void setTraceId(String traceId) {
        TRACE_ID.set(traceId);
    }

    public static String getTaskId() {
        return TASK_ID.get();
    }

    public static void setTaskId(String taskId) {
        TASK_ID.set(taskId);
    }

    public static int depth() {
        Deque<String> stack = NODE_STACK.get();
        return stack == null ? 0 : stack.size();
    }

    public static String currentNodeId() {
        Deque<String> stack = NODE_STACK.get();
        return stack == null ? null : stack.peek();
    }

    public static void pushNode(String nodeId) {
        Deque<String> stack = NODE_STACK.get();
        if (stack == null) {
            stack = new ArrayDeque<>();
            NODE_STACK.set(stack);
        }
        stack.push(nodeId);
    }

    public static void popNode() {
        Deque<String> stack = NODE_STACK.get();
        if (stack == null || stack.isEmpty()) {
            return;
        }
        stack.pop();
        if (stack.isEmpty()) {
            NODE_STACK.remove();
        }
    }

    public static void clear() {
        TRACE_ID.remove();
        TASK_ID.remove();
        NODE_STACK.remove();
        OBSERVATIONS.remove();
        SUB_QUESTION_ID.remove();
    }
}
