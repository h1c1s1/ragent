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

package com.nageoffer.ai.ragent.rag.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * RAG Trace 配置
 */
@Data
@Component
@ConfigurationProperties(prefix = "rag.trace")
public class RagTraceProperties {

    /**
     * 是否启用注解式 Trace 采集
     */
    private boolean enabled = true;

    /**
     * 错误信息最大长度，防止落库过大
     */
    private int maxErrorLength = 1000;

    /** 关闭时只保留结构、标识、分数和决策，不记录问题、片段正文和上下文。 */
    private boolean captureContent = true;
    /** 每个快照列表的最大条目数，不改变实际检索预算。 */
    private int maxCandidates = 200;
    /** 普通文本字段的最大字符数。 */
    private int maxContentLength = 4000;
    /** 证据正文预览的最大字符数。 */
    private int maxPreviewLength = 300;
    /** extraData 的 UTF-8 字节上限，运行时最低按 1024 字节处理。 */
    private int maxNodeBytes = 65536;
}
