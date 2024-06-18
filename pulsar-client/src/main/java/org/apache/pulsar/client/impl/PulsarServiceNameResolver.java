/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */
package org.apache.pulsar.client.impl;

import static com.google.common.base.Preconditions.checkState;
import com.google.common.util.concurrent.Uninterruptibles;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.URI;
import java.net.URISyntaxException;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicIntegerFieldUpdater;
import java.util.concurrent.atomic.AtomicReference;
import lombok.extern.slf4j.Slf4j;
import org.apache.pulsar.client.api.PulsarClientException.InvalidServiceURL;
import org.apache.pulsar.client.util.ScheduledExecutorProvider;
import org.apache.pulsar.common.net.ServiceURI;

/**
 * The default implementation of {@link ServiceNameResolver}.
 */
@Slf4j
public class PulsarServiceNameResolver implements ServiceNameResolver {

    private volatile ServiceURI serviceUri;
    private volatile String serviceUrl;
    private static final AtomicIntegerFieldUpdater<PulsarServiceNameResolver> CURRENT_INDEX_UPDATER =
            AtomicIntegerFieldUpdater.newUpdater(PulsarServiceNameResolver.class, "currentIndex");
    private static final ScheduledExecutorProvider executorProvider =
            new ScheduledExecutorProvider(1, "pulsar-service-resolver-health-check-");
    private final AtomicBoolean healthCheckScheduled = new AtomicBoolean(false);
    private final AtomicReference<ScheduledFuture<?>> healthCheckFuture = new AtomicReference<>();
    private volatile int currentIndex;
    private volatile List<InetSocketAddress> addressList;
    private volatile List<InetSocketAddress> healthyAddress;

    @Override
    public InetSocketAddress resolveHost() {
        final List<InetSocketAddress> list;
        List<InetSocketAddress> healthy = healthyAddress;
        if (healthy != null && !healthy.isEmpty()) {
            list = healthy;
        } else {
            // if no healthy address, use the original address list
            list = addressList;
        }
        checkState(
            list != null, "No service url is provided yet");
        checkState(
            !list.isEmpty(), "No hosts found for service url : " + serviceUrl);
        if (list.size() == 1) {
            return list.get(0);
        } else {
            int originalIndex = CURRENT_INDEX_UPDATER.getAndUpdate(this, last -> (last + 1) % list.size());
            return list.get((originalIndex + 1) % list.size());
        }
    }

    @Override
    public URI resolveHostUri() {
        InetSocketAddress host = resolveHost();
        String hostUrl = serviceUri.getServiceScheme() + "://" + host.getHostString() + ":" + host.getPort();
        return URI.create(hostUrl);
    }

    @Override
    public String getServiceUrl() {
        return serviceUrl;
    }

    @Override
    public ServiceURI getServiceUri() {
        return serviceUri;
    }

    @Override
    public void updateServiceUrl(String serviceUrl) throws InvalidServiceURL {
        ServiceURI uri;
        try {
            uri = ServiceURI.create(serviceUrl);
        } catch (IllegalArgumentException iae) {
            log.error("Invalid service-url {} provided {}", serviceUrl, iae.getMessage(), iae);
            throw new InvalidServiceURL(iae);
        }

        String[] hosts = uri.getServiceHosts();
        List<InetSocketAddress> addresses = new ArrayList<>(hosts.length);
        for (String host : hosts) {
            String hostUrl = uri.getServiceScheme() + "://" + host;
            try {
                URI hostUri = new URI(hostUrl);
                addresses.add(InetSocketAddress.createUnresolved(hostUri.getHost(), hostUri.getPort()));
            } catch (URISyntaxException e) {
                log.error("Invalid host provided {}", hostUrl, e);
                throw new InvalidServiceURL(e);
            }
        }
        this.addressList = addresses;
        this.healthyAddress = addresses;
        this.serviceUrl = serviceUrl;
        this.serviceUri = uri;
        this.currentIndex = randomIndex(addresses.size());

        if (!healthCheckScheduled.get()) {
            synchronized (this) {
                if (!healthCheckScheduled.get()) {
                    ScheduledFuture<?> future =
                            ((ScheduledExecutorService) executorProvider.getExecutor()).scheduleWithFixedDelay(
                                    this::doHealthCheck, 0, 5, TimeUnit.SECONDS);
                    healthCheckScheduled.set(true);
                    healthCheckFuture.set(future);
                }
            }
        }

    }

    @Override
    public void close() {
        ScheduledFuture<?> future = healthCheckFuture.get();
        if (future != null) {
            future.cancel(true);
            healthCheckScheduled.set(false);
            healthCheckFuture.set(null);
        }
    }

    /**
     * Health check for all addresses. This function will be called of same thread,
     * so it is safe to update the healthy address list.
     */
    private void doHealthCheck() {
        List<InetSocketAddress> list = addressList;
        if (list != null && !list.isEmpty()) {
            List<InetSocketAddress> healthy = new ArrayList<>(list.size());
            for (InetSocketAddress address : list) {
                for (int i = 0; i < 3; i++){
                    if (checkAddress(address)) {
                        healthy.add(address);
                        break;
                    } else {
                        Uninterruptibles.sleepUninterruptibly(1, TimeUnit.SECONDS);
                    }
                }
            }
            healthyAddress = healthy;
        }
    }

    private static int randomIndex(int numAddresses) {
        return numAddresses == 1
                ?
                0 : io.netty.util.internal.PlatformDependent.threadLocalRandom().nextInt(numAddresses);
    }

    private static boolean checkAddress(InetSocketAddress address) {
        try (Socket socket = new Socket()) {
            socket.connect(address, 5000);
            return true;
        } catch (Exception e) {
            log.error("[health check]Failed to connect to {}, error:{}", address, e.getMessage());
            return false;
        }
    }

}
