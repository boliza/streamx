/*
 * Copyright (c) 2019 The StreamX Project
 * <p>
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements. See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership. The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License. You may obtain a copy of the License at
 * <p>
 * http://www.apache.org/licenses/LICENSE-2.0
 * <p>
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied. See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */
package com.streamxhub.streamx.console.core.service;

import com.baomidou.mybatisplus.extension.service.IService;
import com.streamxhub.streamx.console.core.entity.Application;
import com.streamxhub.streamx.console.core.entity.FlinkSql;

import java.util.List;

/**
 * @author benjobs
 */
public interface FlinkSqlService extends IService<FlinkSql> {

    /**
     * @param flinkSql
     * @param latest   true  表示设置新增的的记录为 "latest"<br>
     *                 false 表示设置新增的的记录为 "Effective"<br>
     */
    void create(FlinkSql flinkSql, Boolean latest);

    /**
     * @param latest true  表示设置新增的的记录为 "latest"<br>
     *               false 表示设置新增的的记录为 "Effective"<br>
     * @param sqlId
     * @param appId
     */
    void setLatestOrEffective(Boolean latest, Long sqlId, Long appId);

    /**
     * @param appParam
     * @param decode
     * @return
     */
    FlinkSql getEffective(Long appId, boolean decode);

    /**
     * @param application
     * @return
     */
    List<FlinkSql> history(Application application);

    /**
     * @param application
     * @return
     */
    FlinkSql getLatest(Long appId);

    /**
     * @param appId
     */
    void toEffective(Long appId,Long sqlId);
}
