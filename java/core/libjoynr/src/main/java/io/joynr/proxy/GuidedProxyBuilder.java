/*
 * #%L
 * %%
 * Copyright (C) 2020 BMW Car IT GmbH
 * %%
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 * #L%
 */
package io.joynr.proxy;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.Arrays;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import io.joynr.arbitration.ArbitrationCallback;
import io.joynr.arbitration.ArbitrationResult;
import io.joynr.arbitration.Arbitrator;
import io.joynr.arbitration.ArbitratorFactory;
import io.joynr.arbitration.DiscoveryQos;
import io.joynr.exceptions.DiscoveryException;
import io.joynr.exceptions.JoynrRuntimeException;
import io.joynr.messaging.MessagingQos;
import io.joynr.util.ReflectionUtils;
import io.joynr.util.VersionUtil;
import io.joynr.util.ObjectMapper;
import joynr.system.DiscoveryAsync;
import joynr.types.DiscoveryEntryWithMetaInfo;
import joynr.types.Version;

public class GuidedProxyBuilder {

    private static final Logger logger = LoggerFactory.getLogger(GuidedProxyBuilder.class);

    private boolean discoveryCompletedOnce;

    private String[] gbids;
    private String statelessAsyncCallbackUseCase;
    private String interfaceName;
    private Set<String> domains;

    private ProxyBuilderFactory proxyBuilderFactory;
    private ArbitrationResult savedArbitrationResult;
    private DiscoveryAsync localDiscoveryAggregator;
    private DiscoveryQos discoveryQos;
    private MessagingQos messagingQos;
    private Arbitrator arbitrator;
    private final long maxMessagingTtl;
    private final long defaultDiscoveryTimeoutMs;
    private final long defaultDiscoveryRetryIntervalMs;
    private ObjectMapper objectMapper;
    private boolean discoveryInProgress;

    public GuidedProxyBuilder(DiscoverySettingsStorage discoverySettingsStorage,
                              Set<String> domains,
                              Class<?> interfaceClass) {
        this.proxyBuilderFactory = discoverySettingsStorage.getProxyBuilderFactory();
        this.objectMapper = discoverySettingsStorage.getObjectMapper();
        this.localDiscoveryAggregator = discoverySettingsStorage.getLocalDiscoveryAggregator();
        this.maxMessagingTtl = discoverySettingsStorage.getMaxMessagingTtl();
        this.defaultDiscoveryTimeoutMs = discoverySettingsStorage.getDefaultDiscoveryTimeoutMs();
        this.defaultDiscoveryRetryIntervalMs = discoverySettingsStorage.getDefaultDiscoveryRetryIntervalMs();
        this.domains = domains;
        try {
            interfaceName = (String) interfaceClass.getField("INTERFACE_NAME").get(String.class);
        } catch (Exception e) {
            logger.error("INTERFACE_NAME needs to be set in the interface class {}", interfaceClass);
            throw new IllegalStateException(e);
        }
    }

    /*
     * (non-Javadoc)
     *
     * @see
     * io.joynr.proxy.ProxyBuilder#setDiscoveryQos(io.joynr.arbitration.DiscoveryQos
     * )
     */
    public GuidedProxyBuilder setDiscoveryQos(final DiscoveryQos discoveryQos) throws DiscoveryException {
        if (discoveryInProgress) {
            throw new IllegalStateException("setDiscoveryQos called while discovery in progress");
        }
        this.discoveryQos = new DiscoveryQos(discoveryQos);
        applyDefaultValues(this.discoveryQos);
        return this;
    }

    /*
     * (non-Javadoc)
     *
     * @see
     * io.joynr.proxy.ProxyBuilder#setMessagingQos(io.joynr.messaging.MessagingQos)
     */
    public GuidedProxyBuilder setMessagingQos(final MessagingQos messagingQos) throws DiscoveryException {
        if (discoveryInProgress) {
            throw new IllegalStateException("setMessagingQos called while discovery in progress");
        }
        if (messagingQos.getRoundTripTtl_ms() > maxMessagingTtl) {
            logger.warn("Error in MessageQos. domains: {} interface: {} Max allowed ttl: {}. Passed ttl: {}",
                        domains,
                        interfaceName,
                        maxMessagingTtl,
                        messagingQos.getRoundTripTtl_ms());
            messagingQos.setTtl_ms(maxMessagingTtl);
        }

        this.messagingQos = messagingQos;
        return this;
    }

    /*
     * (non-Javadoc)
     *
     * @see
     * io.joynr.proxy.ProxyBuilder#setStatelessAsyncCallback(Object)
     */
    public GuidedProxyBuilder setStatelessAsyncCallbackUseCase(String statelessAsyncCallbackUseCase) {
        if (discoveryInProgress) {
            throw new IllegalStateException("setStatelessAsyncCallbackUseCase called while discovery in progress");
        }
        this.statelessAsyncCallbackUseCase = statelessAsyncCallbackUseCase;
        return this;
    }

    /*
     * (non-Javadoc)
     *
     * @see
     * io.joynr.proxy.ProxyBuilder#setGbids(String[] gbids)
     */
    public GuidedProxyBuilder setGbids(final String[] gbids) {
        if (discoveryInProgress) {
            throw new IllegalStateException("setGbids called while discovery in progress");
        }
        if (gbids == null || gbids.length == 0) {
            throw new IllegalArgumentException("GBIDs array must not be null or empty.");
        }
        this.gbids = gbids.clone();
        return this;
    }

    public DiscoveryResult discover() {
        try {
            return discoverAsync().get();
        } catch (InterruptedException e) {
            throw new DiscoveryException(e.toString());
        } catch (ExecutionException e) {
            if (e.getCause() == null) {
                String domainString = String.join(",", domains);
                throw new DiscoveryException("Lookup failed for domains: " + domainString + ", interface: "
                        + interfaceName + " gbids: " + Arrays.toString(gbids) + " with: " + e.toString());
            } else {
                throw new DiscoveryException(e.getCause().getMessage());
            }
        }
    }

    public CompletableFuture<DiscoveryResult> discoverAsync() {
        return discoverAsyncInternal().thenCompose(this::createDiscoveryResultFromArbitrationResult);
    }

    private CompletableFuture<DiscoveryResult> createDiscoveryResultFromArbitrationResult(ArbitrationResult result) {
        return CompletableFuture.completedFuture(new DiscoveryResult(result.getDiscoveryEntries()));
    }

    private void applyDefaultValues(DiscoveryQos discoveryQos) {
        if (discoveryQos.getDiscoveryTimeoutMs() == DiscoveryQos.NO_VALUE) {
            discoveryQos.setDiscoveryTimeoutMs(defaultDiscoveryTimeoutMs);
        }

        if (discoveryQos.getRetryIntervalMs() == DiscoveryQos.NO_VALUE) {
            discoveryQos.setRetryIntervalMs(defaultDiscoveryRetryIntervalMs);
        }
    }

    private CompletableFuture<ArbitrationResult> discoverAsyncInternal() {
        if (arbitrator == null) {
            if (discoveryQos == null) {
                discoveryQos = new DiscoveryQos();
                applyDefaultValues(discoveryQos);
            }
            arbitrator = ArbitratorFactory.create(domains,
                                                  interfaceName,
                                                  new Version(),
                                                  discoveryQos,
                                                  localDiscoveryAggregator,
                                                  gbids);
        }
        CompletableFuture<ArbitrationResult> resultFuture = new CompletableFuture<>();
        arbitrator.setArbitrationListener(new ArbitrationCallback() {
            @Override
            public void onSuccess(ArbitrationResult arbitrationResult) {
                savedArbitrationResult = arbitrationResult;
                discoveryCompletedOnce = true;
                discoveryInProgress = false;
                resultFuture.complete(arbitrationResult);
            }

            @Override
            public void onError(Throwable throwable) {
                JoynrRuntimeException reason;
                if (throwable instanceof JoynrRuntimeException) {
                    reason = (JoynrRuntimeException) throwable;
                } else {
                    reason = new JoynrRuntimeException(throwable);
                }
                discoveryInProgress = false;
                resultFuture.completeExceptionally(reason);
            }
        });
        savedArbitrationResult = new ArbitrationResult();
        discoveryInProgress = true;
        discoveryCompletedOnce = false;
        arbitrator.lookup();
        return resultFuture;
    }

    public <T> T buildProxy(Class<T> interfaceClass, String participantId) {
        if (!discoveryCompletedOnce) {
            throw new IllegalStateException("Discovery has to be completed before building a proxy!");
        }
        DiscoveryEntryWithMetaInfo discoveryEntryForProxy = null;
        for (DiscoveryEntryWithMetaInfo entry : savedArbitrationResult.getDiscoveryEntries()) {
            if (entry.getParticipantId().equals(participantId)) {
                discoveryEntryForProxy = entry;
            }
        }
        if (discoveryEntryForProxy == null) {
            throw new IllegalArgumentException("No provider with participant ID " + participantId + " was discovered!");
        }
        Version interfaceVersion = VersionUtil.getVersionFromAnnotation(interfaceClass);
        Version providerVersion = discoveryEntryForProxy.getProviderVersion();
        if (!interfaceVersion.equals(providerVersion)) {
            throw new IllegalArgumentException("Provider Version " + providerVersion
                    + " does not match interface version " + interfaceVersion + " !");
        }
        ArbitrationResult arbitrationResultForProxy = new ArbitrationResult(discoveryEntryForProxy);
        registerInterfaceClassTypes(interfaceClass, "Cannot create ProxyBuilder");
        ProxyBuilder<T> proxyBuilder = proxyBuilderFactory.get(domains, interfaceClass);
        if (discoveryQos == null) {
            // discoveryQos should be already set, since this is done latest in
            // discoverAsyncInternal() which must have been called already since
            // discoveryCompletedOnce is true
            throw new IllegalStateException("DiscoveryQos not set, internal error!");
        }
        proxyBuilder.setDiscoveryQos(discoveryQos);

        if (statelessAsyncCallbackUseCase != null) {
            proxyBuilder.setStatelessAsyncCallbackUseCase(statelessAsyncCallbackUseCase);
        }

        if (messagingQos == null) {
            proxyBuilder.setMessagingQos(new MessagingQos());
        } else {
            proxyBuilder.setMessagingQos(messagingQos);
        }
        if (!(gbids == null) && gbids.length > 0) {
            proxyBuilder.setGbids(gbids);
        }
        return proxyBuilder.build(arbitrationResultForProxy);
    }

    protected synchronized void registerInterfaceClassTypes(final Class<?> interfaceClass, String errorPrefix) {
        try {
            Method m = ReflectionUtils.getStaticMethodFromSuperInterfaces(interfaceClass, "getDataTypes");
            @SuppressWarnings("unchecked")
            Set<Class<?>> subClasses = (Set<Class<?>>) m.invoke(null);
            objectMapper.registerSubtypes(subClasses.toArray(new Class<?>[subClasses.size()]));
        } catch (NoSuchMethodException | SecurityException | IllegalAccessException | InvocationTargetException e) {
            throw new IllegalArgumentException(errorPrefix + ": failed to register interface data types", e);
        }
    }
}
