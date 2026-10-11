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

package com.nageoffer.ai.ragent.rag.aop;

import com.nageoffer.ai.ragent.framework.trace.RagTraceNode;
import com.nageoffer.ai.ragent.rag.trace.RagTraceRecorder;
import lombok.RequiredArgsConstructor;
import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.annotation.Around;
import org.aspectj.lang.annotation.Aspect;
import org.aspectj.lang.reflect.MethodSignature;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

@Aspect
@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 10)
@RequiredArgsConstructor
public class RagTraceAspect {
    private final RagTraceRecorder recorder;

    @Around("@annotation(annotation)")
    public Object aroundNode(ProceedingJoinPoint joinPoint, RagTraceNode annotation) throws Throwable {
        var method = ((MethodSignature) joinPoint.getSignature()).getMethod();
        String name = annotation.name().isBlank() ? method.getName() : annotation.name();
        var span = recorder.start(name, annotation.type().isBlank() ? "METHOD" : annotation.type(),
                method.getDeclaringClass().getName(), method.getName());
        try (var scope = span.activate()) {
            try {
                Object result = joinPoint.proceed();
                span.success();
                return result;
            } catch (Throwable error) {
                span.error(error);
                throw error;
            }
        }
    }
}
