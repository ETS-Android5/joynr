/*
 * #%L
 * %%
 * Copyright (C) 2011 - 2017 BMW Car IT GmbH
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
package io.joynr.integration;

import static io.joynr.util.JoynrUtil.createUuidString;
import static org.junit.Assert.fail;

import java.util.Arrays;
import java.util.HashSet;
import java.util.Properties;
import java.util.concurrent.Semaphore;

import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.Mock;
import org.mockito.runners.MockitoJUnitRunner;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.google.inject.Module;
import com.google.inject.util.Modules;

import io.joynr.JoynrVersion;
import io.joynr.arbitration.DiscoveryQos;
import io.joynr.arbitration.DiscoveryScope;
import io.joynr.exceptions.JoynrRuntimeException;
import io.joynr.exceptions.MultiDomainNoCompatibleProviderFoundException;
import io.joynr.exceptions.NoCompatibleProviderFoundException;
import io.joynr.integration.util.DummyJoynrApplication;
import io.joynr.messaging.MessagingPropertyKeys;
import io.joynr.messaging.routing.TestGlobalAddressModule;
import io.joynr.provider.JoynrInterface;
import io.joynr.proxy.ProxyBuilder.ProxyCreatedCallback;
import io.joynr.proxy.ProxyBuilder;
import io.joynr.runtime.CCInProcessRuntimeModule;
import io.joynr.runtime.JoynrInjectorFactory;
import io.joynr.runtime.JoynrRuntime;
import joynr.test.JoynrTestLoggingRule;
import joynr.tests.test;
import joynr.tests.testAsync;
import joynr.tests.testBroadcastInterface;
import joynr.tests.testProvider;
import joynr.tests.testSubscriptionInterface;
import joynr.tests.testSync;
import joynr.tests.testTypes.TestEnum;
import joynr.types.ProviderQos;
import joynr.types.ProviderScope;

@RunWith(MockitoJUnitRunner.class)
public class ProxyErrorsTest {
    private static final Logger logger = LoggerFactory.getLogger(ProxyErrorsTest.class);
    @Rule
    public JoynrTestLoggingRule joynrTestRule = new JoynrTestLoggingRule(logger);

    private static final long CONST_DEFAULT_TEST_TIMEOUT = 3000;

    // Dummy interface with a major version incompatible to the provider's version
    @JoynrVersion(major = 2, minor = 0)
    public interface TestProxyWrongVersion
            extends testAsync, testSync, testSubscriptionInterface, testBroadcastInterface {
        public static String INTERFACE_NAME = "tests/test";
    }

    @JoynrInterface(provider = TestProviderWrongVersion.class, provides = test.class, name = "tests/test")
    @JoynrVersion(major = 1, minor = 0)
    interface TestProviderWrongVersion extends testProvider {
    }

    @Mock
    TestProviderWrongVersion provider;

    private Semaphore waitOnExceptionAndErrorCallbackSemaphore;
    private String domain;
    private String domain2;
    private JoynrRuntime runtime;
    private DiscoveryQos discoveryQos;
    private ProxyCreatedCallback<TestProxyWrongVersion> callback;

    protected JoynrRuntime getRuntime(Properties joynrConfig, Module... modules) {
        Module runtimeModule = new CCInProcessRuntimeModule();
        Module modulesWithRuntime = Modules.override(runtimeModule).with(modules);
        modulesWithRuntime = Modules.override(modulesWithRuntime).with(new TestGlobalAddressModule());

        DummyJoynrApplication application = (DummyJoynrApplication) new JoynrInjectorFactory(joynrConfig,
                                                                                             modulesWithRuntime).createApplication(DummyJoynrApplication.class);

        return application.getRuntime();
    }

    @Before
    public void setUp() {
        // the error callback and NoCompatibleProviderFoundException will each increment the permits.
        // The test will wait until 2 permits are available, or fail in the junit test timeout time.
        waitOnExceptionAndErrorCallbackSemaphore = new Semaphore(-1, true);
        domain = "domain-" + createUuidString();
        domain2 = "domain2-" + createUuidString();
        Properties joynrConfig = new Properties();
        joynrConfig.setProperty(MessagingPropertyKeys.CHANNELID, "discoverydirectory_channelid");

        // provider and proxy using same runtime to allow local-only communications
        runtime = getRuntime(joynrConfig);
        ProviderQos providerQos = new ProviderQos();
        providerQos.setScope(ProviderScope.LOCAL);
        runtime.registerProvider(domain, provider, providerQos);
        runtime.registerProvider(domain2, provider, providerQos);

        discoveryQos = new DiscoveryQos();
        discoveryQos.setDiscoveryScope(DiscoveryScope.LOCAL_ONLY);
        discoveryQos.setDiscoveryTimeoutMs(1000);
        discoveryQos.setRetryIntervalMs(100);

        callback = new ProxyCreatedCallback<ProxyErrorsTest.TestProxyWrongVersion>() {

            @Override
            public void onProxyCreationFinished(TestProxyWrongVersion result) {
                fail("proxy creation should fail with a version exception");
            }

            @Override
            public void onProxyCreationError(JoynrRuntimeException error) {
                // adds 1 of 2 permits to the semaphore. The other is provided when the exception is caught
                waitOnExceptionAndErrorCallbackSemaphore.release();
            }
        };
    }

    @After
    public void tearDown() {
        runtime.shutdown(true);
    }

    @Test(timeout = CONST_DEFAULT_TEST_TIMEOUT)
    public void testNoCompatibleProviderFound() throws Exception {

        ProxyBuilder<ProxyErrorsTest.TestProxyWrongVersion> proxyBuilder = runtime.getProxyBuilder(domain,
                                                                                                   ProxyErrorsTest.TestProxyWrongVersion.class);
        TestProxyWrongVersion proxy = proxyBuilder.setDiscoveryQos(discoveryQos).build(callback);

        try {
            proxy.setEnumAttribute(TestEnum.TWO);
        } catch (NoCompatibleProviderFoundException e) {
            // adds 1 of 2 permits to the semaphore. The other is provided when the proxy created error callback is called
            waitOnExceptionAndErrorCallbackSemaphore.release();
        } catch (Exception e) {
            fail("did not expect exception " + e);
        }

        // wait for a NoCompatibleProviderFoundException and the proxy created error callback to be called
        waitOnExceptionAndErrorCallbackSemaphore.acquire();
    }

    @Test(timeout = CONST_DEFAULT_TEST_TIMEOUT)
    public void testMultiDomainNoCompatibleProviderFound() throws Exception {

        ProxyBuilder<ProxyErrorsTest.TestProxyWrongVersion> proxyBuilder = runtime.getProxyBuilder(new HashSet<String>(Arrays.asList(domain,
                                                                                                                                     domain2)),
                                                                                                   ProxyErrorsTest.TestProxyWrongVersion.class);
        TestProxyWrongVersion proxy = proxyBuilder.setDiscoveryQos(discoveryQos).build(callback);

        try {
            proxy.setEnumAttribute(TestEnum.TWO);
        } catch (MultiDomainNoCompatibleProviderFoundException e) {
            // adds 1 of 2 permits to the semaphore. The other is provided when the proxy created error callback is called
            waitOnExceptionAndErrorCallbackSemaphore.release();
        } catch (Exception e) {
            fail("did not expect exception " + e);
        }

        // wait for a MultiDomainNoCompatibleProviderFoundException and the proxy created error callback to be called
        waitOnExceptionAndErrorCallbackSemaphore.acquire();
    }

    @Test(timeout = CONST_DEFAULT_TEST_TIMEOUT)
    public void testProxyIsInvalidatedOnceArbitrationExceptionThrown() throws Exception {

        ProxyBuilder<ProxyErrorsTest.TestProxyWrongVersion> proxyBuilder = runtime.getProxyBuilder(domain,
                                                                                                   ProxyErrorsTest.TestProxyWrongVersion.class);
        TestProxyWrongVersion proxy = proxyBuilder.setDiscoveryQos(discoveryQos).build();

        try {
            proxy.setEnumAttribute(TestEnum.TWO);
            fail("expected NoCompatibleProviderFoundException");
        } catch (NoCompatibleProviderFoundException e) {
            // got the correct exception
        } catch (Exception e) {
            fail("did not expect exception " + e);
        }

        // next try should fail immediately
        try {
            proxy.setEnumAttribute(TestEnum.TWO);
            fail("expected NoCompatibleProviderFoundException");
        } catch (NoCompatibleProviderFoundException e) {
            // got the correct exception
        } catch (Exception e) {
            fail("did not expect exception " + e);
        }
    }
}
