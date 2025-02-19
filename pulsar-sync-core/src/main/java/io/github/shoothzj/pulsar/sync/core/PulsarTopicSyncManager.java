/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */

package io.github.shoothzj.pulsar.sync.core;

import lombok.extern.slf4j.Slf4j;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

@Slf4j
public class PulsarTopicSyncManager {

    private final PulsarHandle pulsarHandle;

    private final SyncConfig syncConfig;

    private final ScheduledExecutorService scheduledExecutor;

    private final TenantNamespace tenantNamespace;

    private ScheduledFuture<?> scheduledFuture;

    private final Map<TenantNamespaceTopic, PulsarPartitionSyncWorker> map = new ConcurrentHashMap<>();

    public PulsarTopicSyncManager(PulsarHandle pulsarHandle,
                                  SyncConfig syncConfig, ScheduledExecutorService scheduledExecutor,
                                  TenantNamespace tenantNamespace) {
        this.pulsarHandle = pulsarHandle;
        this.syncConfig = syncConfig;
        this.scheduledExecutor = scheduledExecutor;
        this.tenantNamespace = tenantNamespace;
    }

    public void start() {
        if (syncConfig.isAutoUpdateTopic()) {
            this.scheduledFuture = scheduledExecutor
                    .scheduleWithFixedDelay(this::sync, 0, 3, TimeUnit.MINUTES);
        } else {
            sync();
        }
    }

    public void sync() {
        pulsarHandle.srcAdmin().topics().getListAsync(tenantNamespace.namespace())
                .whenComplete((topics, throwable) -> {
                    if (throwable != null) {
                        log.error("failed to get partitioned topic list", throwable);
                        return;
                    }
                    for (String topic : topics) {
                        if (topic.contains("-partition")) {
                            continue;
                        }
                        TenantNamespaceTopic tenantNamespaceTopic = new TenantNamespaceTopic(tenantNamespace, topic);
                        log.info("begin to start topic sync worker [{}]", tenantNamespaceTopic);
                        map.computeIfAbsent(tenantNamespaceTopic, k -> startTopicSyncWorker(tenantNamespaceTopic));
                    }
                });
    }

    public PulsarPartitionSyncWorker startTopicSyncWorker(TenantNamespaceTopic tenantNamespaceTopic) {
        PulsarPartitionSyncWorker worker = new PulsarPartitionSyncWorker(pulsarHandle,
                syncConfig, scheduledExecutor, tenantNamespaceTopic);
        worker.start();
        return worker;
    }

    public void close() {
        if (scheduledFuture != null) {
            scheduledFuture.cancel(true);
        }
        map.values().forEach(PulsarPartitionSyncWorker::close);
    }
}
