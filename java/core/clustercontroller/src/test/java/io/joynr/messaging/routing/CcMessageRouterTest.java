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
package io.joynr.messaging.routing;

import static io.joynr.util.JoynrUtil.createUuidString;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.lessThan;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;
import static org.mockito.Matchers.any;
import static org.mockito.Matchers.anyBoolean;
import static org.mockito.Matchers.anyLong;
import static org.mockito.Matchers.anyString;
import static org.mockito.Matchers.eq;
import static org.mockito.Mockito.atLeast;
import static org.mockito.Mockito.atMost;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.timeout;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.DelayQueue;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.Semaphore;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.Mockito;
import org.mockito.invocation.InvocationOnMock;
import org.mockito.runners.MockitoJUnitRunner;
import org.mockito.stubbing.Answer;

import com.google.inject.AbstractModule;
import com.google.inject.Guice;
import com.google.inject.Injector;
import com.google.inject.Module;
import com.google.inject.Provides;
import com.google.inject.Singleton;
import com.google.inject.TypeLiteral;
import com.google.inject.multibindings.MapBinder;
import com.google.inject.multibindings.Multibinder;
import com.google.inject.name.Named;
import com.google.inject.name.Names;
import com.google.inject.util.Modules;

import io.joynr.accesscontrol.AccessController;
import io.joynr.accesscontrol.HasConsumerPermissionCallback;
import io.joynr.common.ExpiryDate;
import io.joynr.dispatching.MutableMessageFactory;
import io.joynr.exceptions.JoynrDelayMessageException;
import io.joynr.exceptions.JoynrMessageExpiredException;
import io.joynr.exceptions.JoynrMessageNotSentException;
import io.joynr.exceptions.JoynrRuntimeException;
import io.joynr.messaging.AbstractMiddlewareMessagingStubFactory;
import io.joynr.messaging.ConfigurableMessagingSettings;
import io.joynr.messaging.FailureAction;
import io.joynr.messaging.IMessagingSkeletonFactory;
import io.joynr.messaging.IMessagingStub;
import io.joynr.messaging.JoynrMessageProcessor;
import io.joynr.messaging.JsonMessageSerializerModule;
import io.joynr.messaging.MessagingQos;
import io.joynr.messaging.MessagingSkeletonFactory;
import io.joynr.messaging.MulticastReceiverRegistrar;
import io.joynr.messaging.SuccessAction;
import io.joynr.messaging.inprocess.InProcessAddress;
import io.joynr.messaging.inprocess.InProcessMessagingSkeleton;
import io.joynr.messaging.persistence.MessagePersister;
import io.joynr.messaging.util.MulticastWildcardRegexFactory;
import io.joynr.runtime.ClusterControllerRuntimeModule;
import io.joynr.runtime.ShutdownNotifier;
import io.joynr.util.JoynrThreadFactory;
import io.joynr.util.ObjectMapper;
import joynr.ImmutableMessage;
import joynr.Message;
import joynr.MulticastPublication;
import joynr.MutableMessage;
import joynr.Reply;
import joynr.Request;
import joynr.SubscriptionPublication;
import joynr.SubscriptionReply;
import joynr.Message.MessageType;
import joynr.system.RoutingTypes.Address;
import joynr.system.RoutingTypes.BinderAddress;
import joynr.system.RoutingTypes.MqttAddress;
import joynr.system.RoutingTypes.RoutingTypesUtil;
import joynr.system.RoutingTypes.UdsClientAddress;
import joynr.system.RoutingTypes.WebSocketAddress;
import joynr.system.RoutingTypes.WebSocketClientAddress;
import joynr.system.RoutingTypes.WebSocketProtocol;

@RunWith(MockitoJUnitRunner.class)
public class CcMessageRouterTest {

    private String mqttTopic = "MessageSchedulerTest_" + createUuidString();
    private final MqttAddress mqttAddress = new MqttAddress("mqtt://testUrl:42", mqttTopic);
    private final int maximumParallelSends = 1;
    private final long routingTableGracePeriodMs = 30000;

    @Mock
    private RoutingTableAddressValidator addressValidatorMock;
    private RoutingTable routingTable;
    InMemoryMulticastReceiverRegistry multicastReceiverRegistry = new InMemoryMulticastReceiverRegistry(new MulticastWildcardRegexFactory());
    private AddressManager addressManager;

    @Mock
    private IMessagingStub messagingStubMock;
    @Mock
    private AbstractMiddlewareMessagingStubFactory<IMessagingStub, MqttAddress> mqttMessagingStubFactoryMock;
    @Mock
    private AbstractMiddlewareMessagingStubFactory<IMessagingStub, WebSocketClientAddress> websocketClientMessagingStubFactoryMock;
    @Mock
    private AbstractMiddlewareMessagingStubFactory<IMessagingStub, WebSocketAddress> webSocketMessagingStubFactoryMock;
    @Mock
    private AbstractMiddlewareMessagingStubFactory<IMessagingStub, InProcessAddress> inProcessMessagingStubFactoryMock;
    @Mock
    private ShutdownNotifier shutdownNotifier;
    @Mock
    private MessagePersister messagePersisterMock;

    @Mock
    private AccessController accessControllerMock;

    private MessageQueue messageQueue;

    private MessageRouter messageRouter;
    private MutableMessage joynrMessage;
    protected String toParticipantId = "toParticipantId";
    protected String fromParticipantId = "fromParticipantId";

    private Module testModule;
    private Injector injector;
    private MutableMessageFactory messageFactory;

    private long globalMaxRetryCount = 10;

    @Before
    public void setUp() throws Exception {
        doReturn(true).when(addressValidatorMock).isValidForRoutingTable(any(Address.class));
        final String[] gbidsArray = { "joynrtestgbid1", "joynrtestgbid2" };
        routingTable = spy(new RoutingTableImpl(42, gbidsArray, addressValidatorMock));
        messageQueue = spy(new MessageQueue(new DelayQueue<DelayableImmutableMessage>(),
                                            new MessageQueue.MaxTimeoutHolder(),
                                            createUuidString(),
                                            messagePersisterMock,
                                            routingTable));
        addressManager = spy(new AddressManager(routingTable,
                                                new AddressManager.PrimaryGlobalTransportHolder(null),
                                                new HashSet<MulticastAddressCalculator>(),
                                                multicastReceiverRegistry));

        when(mqttMessagingStubFactoryMock.create(any(MqttAddress.class))).thenReturn(messagingStubMock);

        AbstractModule mockModule = new AbstractModule() {

            private Long msgRetryIntervalMs = 10L;
            // message runnables + cleanup thread
            private int numberOfThreads = maximumParallelSends + 1;
            private long routingTableCleanupIntervalMs = 60000;

            @Override
            protected void configure() {
                requestStaticInjection(RoutingTypesUtil.class);
                bind(CcMessageRouter.class).in(Singleton.class);
                bind(MessageRouter.class).to(CcMessageRouter.class);
                bind(MulticastReceiverRegistrar.class).to(CcMessageRouter.class);
                bind(RoutingTable.class).toInstance(routingTable);
                bind(AddressManager.class).toInstance(addressManager);
                bind(MulticastReceiverRegistry.class).toInstance(multicastReceiverRegistry);
                bind(ShutdownNotifier.class).toInstance(shutdownNotifier);
                bind(Long.class).annotatedWith(Names.named(ConfigurableMessagingSettings.PROPERTY_SEND_MSG_RETRY_INTERVAL_MS))
                                .toInstance(msgRetryIntervalMs);
                bind(Integer.class).annotatedWith(Names.named(ConfigurableMessagingSettings.PROPERTY_MESSAGING_MAXIMUM_PARALLEL_SENDS))
                                   .toInstance(maximumParallelSends);
                bind(Long.class).annotatedWith(Names.named(ConfigurableMessagingSettings.PROPERTY_ROUTING_TABLE_GRACE_PERIOD_MS))
                                .toInstance(routingTableGracePeriodMs);
                bind(Long.class).annotatedWith(Names.named(ConfigurableMessagingSettings.PROPERTY_ROUTING_TABLE_CLEANUP_INTERVAL_MS))
                                .toInstance(routingTableCleanupIntervalMs);
                bind(String.class).annotatedWith(Names.named(MessageQueue.MESSAGE_QUEUE_ID))
                                  .toInstance(createUuidString());
                bind(Long.class).annotatedWith(Names.named(ConfigurableMessagingSettings.PROPERTY_ROUTING_MAX_RETRY_COUNT))
                                .toInstance(globalMaxRetryCount);

                bindConstant().annotatedWith(Names.named(ClusterControllerRuntimeModule.PROPERTY_ACCESSCONTROL_ENABLE))
                              .to(false);

                bind(AccessController.class).toInstance(accessControllerMock);
                bind(MessagePersister.class).toInstance(messagePersisterMock);

                MapBinder<Class<? extends Address>, AbstractMiddlewareMessagingStubFactory<? extends IMessagingStub, ? extends Address>> messagingStubFactory;
                messagingStubFactory = MapBinder.newMapBinder(binder(), new TypeLiteral<Class<? extends Address>>() {
                },
                                                              new TypeLiteral<AbstractMiddlewareMessagingStubFactory<? extends IMessagingStub, ? extends Address>>() {
                                                              },
                                                              Names.named(MessagingStubFactory.MIDDLEWARE_MESSAGING_STUB_FACTORIES));
                messagingStubFactory.addBinding(WebSocketClientAddress.class)
                                    .toInstance(websocketClientMessagingStubFactoryMock);
                messagingStubFactory.addBinding(WebSocketAddress.class).toInstance(webSocketMessagingStubFactoryMock);
                messagingStubFactory.addBinding(MqttAddress.class).toInstance(mqttMessagingStubFactoryMock);
                messagingStubFactory.addBinding(InProcessAddress.class).toInstance(inProcessMessagingStubFactoryMock);

                MapBinder.newMapBinder(binder(), new TypeLiteral<Class<? extends Address>>() {
                }, new TypeLiteral<IMessagingSkeletonFactory>() {
                }, Names.named(MessagingSkeletonFactory.MIDDLEWARE_MESSAGING_SKELETON_FACTORIES));

                Multibinder.newSetBinder(binder(), new TypeLiteral<MulticastAddressCalculator>() {
                });
            }

            @Provides
            @Named(MessageRouter.SCHEDULEDTHREADPOOL)
            ScheduledExecutorService provideMessageSchedulerThreadPoolExecutor() {
                ThreadFactory schedulerNamedThreadFactory = new JoynrThreadFactory("joynr.MessageScheduler-scheduler");
                ScheduledThreadPoolExecutor scheduler = new ScheduledThreadPoolExecutor(numberOfThreads,
                                                                                        schedulerNamedThreadFactory);
                scheduler.setKeepAliveTime(100, TimeUnit.SECONDS);
                scheduler.allowCoreThreadTimeOut(true);
                return scheduler;
            }
        };

        testModule = Modules.override(new JsonMessageSerializerModule())
                            .with(mockModule, new TestGlobalAddressModule());

        injector = Guice.createInjector(Modules.override(testModule).with(new AbstractModule() {
            @Override
            protected void configure() {
                bind(MessageQueue.class).toInstance(messageQueue);
            }
        }));
        messageRouter = injector.getInstance(MessageRouter.class);

        ObjectMapper objectMapper = new ObjectMapper();
        messageFactory = new MutableMessageFactory(objectMapper, new HashSet<JoynrMessageProcessor>());

        final boolean isGloballyVisible = true; // toParticipantId is globally visible
        final long expiryDateMs = Long.MAX_VALUE;
        final boolean isSticky = true;
        routingTable.put(toParticipantId, mqttAddress, isGloballyVisible, expiryDateMs, isSticky);

        Request request = new Request("noMethod", new Object[]{}, new String[]{}, "requestReplyId");

        joynrMessage = messageFactory.createRequest(fromParticipantId, toParticipantId, request, new MessagingQos());
        joynrMessage.setLocalMessage(true);
    }

    @Test
    public void testScheduleMessageOk() throws Exception {
        joynrMessage.setTtlMs(ExpiryDate.fromRelativeTtl(100000000).getValue());
        joynrMessage.setTtlAbsolute(true);

        ImmutableMessage immutableMessage = joynrMessage.getImmutableMessage();

        messageRouter.route(immutableMessage);

        ArgumentCaptor<DelayableImmutableMessage> passedDelaybleMessage = ArgumentCaptor.forClass(DelayableImmutableMessage.class);
        verify(messageQueue).put(passedDelaybleMessage.capture());
        assertEquals(immutableMessage, passedDelaybleMessage.getAllValues().get(0).getMessage());
        final long delayMs = passedDelaybleMessage.getAllValues().get(0).getDelay(TimeUnit.MILLISECONDS);
        assertTrue("Delay was: " + delayMs, delayMs <= 0);

        verify(mqttMessagingStubFactoryMock, timeout(1000)).create(eq(mqttAddress));
        verify(messagingStubMock, timeout(100)).transmit(eq(immutableMessage),
                                                         any(SuccessAction.class),
                                                         any(FailureAction.class));
    }

    @Test
    public void testScheduleExpiredMessageFails() throws Exception {
        joynrMessage.setTtlMs(ExpiryDate.fromRelativeTtl(1).getValue());
        joynrMessage.setTtlAbsolute(true);

        ImmutableMessage immutableMessage = joynrMessage.getImmutableMessage();

        Thread.sleep(5);
        try {
            messageRouter.route(immutableMessage);
        } catch (JoynrMessageExpiredException e) {
            verify(messageQueue, times(0)).put(any(DelayableImmutableMessage.class));
            verify(mqttMessagingStubFactoryMock, never()).create(any(MqttAddress.class));
            verify(routingTable).remove(immutableMessage.getSender());
            return;
        }
        fail("scheduling an expired message should throw");
    }

    private void prepareMulticastForMultipleAddresses(final Address receiverAddress1, final Address receiverAddress2) {
        final String multicastId = "multicastId";
        final String receiverParticipantId1 = "receiverParticipantId1";
        final String receiverParticipantId2 = "receiverParticipantId2";

        multicastReceiverRegistry.registerMulticastReceiver(multicastId, receiverParticipantId1);
        multicastReceiverRegistry.registerMulticastReceiver(multicastId, receiverParticipantId2);

        final boolean isGloballyVisible = false;
        final long expiryDateMs = Long.MAX_VALUE;
        routingTable.put(receiverParticipantId1, receiverAddress1, isGloballyVisible, expiryDateMs);
        routingTable.put(receiverParticipantId2, receiverAddress2, isGloballyVisible, expiryDateMs);

        joynrMessage.setTtlMs(ExpiryDate.fromRelativeTtl(100000).getValue());
        joynrMessage.setType(Message.MessageType.VALUE_MESSAGE_TYPE_MULTICAST);
        joynrMessage.setRecipient(multicastId);
    }

    @Test
    public void testNoMessageDuplicationForMulticastForMultipleAddressesWithErrorFromStubForAllAddresses() throws Exception {
        MqttAddress receiverAddress1 = new MqttAddress("what", "ever1");
        MqttAddress receiverAddress2 = new MqttAddress("what", "ever2");
        prepareMulticastForMultipleAddresses(receiverAddress1, receiverAddress2);
        ImmutableMessage immutableMessage = joynrMessage.getImmutableMessage();

        doAnswer(new Answer<Void>() {
            private int callCount = 0;

            @Override
            public Void answer(InvocationOnMock invocation) throws Throwable {
                FailureAction failureAction = invocation.getArgumentAt(2, FailureAction.class);
                if (callCount < 2) {
                    callCount++;
                    failureAction.execute(new JoynrDelayMessageException(10, "first retry"));
                } else {
                    failureAction.execute(new JoynrMessageNotSentException("do not retry twice"));
                }
                return null;
            }
        }).when(messagingStubMock).transmit(eq(immutableMessage), any(SuccessAction.class), any(FailureAction.class));

        messageRouter.route(immutableMessage);

        Thread.sleep(1000);

        verify(messagingStubMock, times(4)).transmit(eq(immutableMessage),
                                                     any(SuccessAction.class),
                                                     any(FailureAction.class));
        verify(mqttMessagingStubFactoryMock, times(2)).create(receiverAddress1);
        verify(mqttMessagingStubFactoryMock, times(2)).create(receiverAddress2);
    }

    @Test
    public void testMulticastIsOnlyResentForFailingAddress() throws Exception {
        MqttAddress receiverAddress1 = new MqttAddress("http://testUrl", "channelId1");
        MqttAddress receiverAddress2 = new MqttAddress("http://testUrl", "channelId2");
        prepareMulticastForMultipleAddresses(receiverAddress1, receiverAddress2);
        ImmutableMessage immutableMessage = joynrMessage.getImmutableMessage();

        IMessagingStub successfulMessagingStubMock = Mockito.mock(IMessagingStub.class);
        IMessagingStub failingMessagingStubMock = Mockito.mock(IMessagingStub.class);
        when(mqttMessagingStubFactoryMock.create(receiverAddress1)).thenReturn(successfulMessagingStubMock);
        when(mqttMessagingStubFactoryMock.create(receiverAddress2)).thenReturn(failingMessagingStubMock);

        doAnswer(new Answer<Void>() {
            @Override
            public Void answer(InvocationOnMock invocation) throws Throwable {
                SuccessAction successAction = invocation.getArgumentAt(1, SuccessAction.class);
                successAction.execute();
                return null;
            }
        }).when(successfulMessagingStubMock)
          .transmit(eq(immutableMessage), any(SuccessAction.class), any(FailureAction.class));

        doAnswer(new Answer<Void>() {
            private int callCount = 0;

            @Override
            public Void answer(InvocationOnMock invocation) throws Throwable {
                FailureAction failureAction = invocation.getArgumentAt(2, FailureAction.class);
                if (callCount < 2) {
                    callCount++;
                    failureAction.execute(new JoynrDelayMessageException(10, "first retry"));
                } else {
                    failureAction.execute(new JoynrMessageNotSentException("do not retry thrice"));
                }
                return null;
            }
        }).when(failingMessagingStubMock)
          .transmit(eq(immutableMessage), any(SuccessAction.class), any(FailureAction.class));

        messageRouter.route(immutableMessage);

        Thread.sleep(1000);

        verify(successfulMessagingStubMock, times(1)).transmit(eq(immutableMessage),
                                                               any(SuccessAction.class),
                                                               any(FailureAction.class));
        verify(failingMessagingStubMock, times(3)).transmit(eq(immutableMessage),
                                                            any(SuccessAction.class),
                                                            any(FailureAction.class));
        verify(addressManager, times(1)).getParticipantIdsForImmutableMessage(immutableMessage);
        verify(addressManager, atLeast(4)).getAddressForDelayableImmutableMessage(any());
        verify(mqttMessagingStubFactoryMock, times(1)).create(receiverAddress1);
        verify(mqttMessagingStubFactoryMock, times(3)).create(receiverAddress2);
    }

    private ImmutableMessage retryRoutingWith1msDelay(MessageRouter messageRouter, int ttlMs) throws Exception {
        doThrow(new JoynrDelayMessageException(1, "test")).when(messagingStubMock)
                                                          .transmit(any(ImmutableMessage.class),
                                                                    any(SuccessAction.class),
                                                                    any(FailureAction.class));
        joynrMessage.setTtlMs(ExpiryDate.fromRelativeTtl(ttlMs).getValue());
        joynrMessage.setTtlAbsolute(true);

        ImmutableMessage immutableMessage = joynrMessage.getImmutableMessage();

        messageRouter.route(immutableMessage);
        Thread.sleep(100);

        return immutableMessage;
    }

    private MessageRouter getMessageRouterWithMaxRetryCount(final long routingMaxRetryCount) {
        Module testMaxRetryCountModule = Modules.override(testModule).with(new AbstractModule() {
            @Override
            protected void configure() {
                bind(Long.class).annotatedWith(Names.named(ConfigurableMessagingSettings.PROPERTY_ROUTING_MAX_RETRY_COUNT))
                                .toInstance(routingMaxRetryCount);
            }
        });
        Injector injector2 = Guice.createInjector(testMaxRetryCountModule);
        return injector2.getInstance(MessageRouter.class);
    }

    @Test
    public void testRetryWithMaxRetryCount() throws Exception {
        final long routingMaxRetryCount = 3;
        MessageRouter messageRouterWithMaxRetryCount = getMessageRouterWithMaxRetryCount(routingMaxRetryCount);

        ImmutableMessage immutableMessage = retryRoutingWith1msDelay(messageRouterWithMaxRetryCount, 100000000);

        verify(messagingStubMock, times((int) routingMaxRetryCount + 1)).transmit(eq(immutableMessage),
                                                                                  any(SuccessAction.class),
                                                                                  any(FailureAction.class));
    }

    @Test
    public void testRetryWithoutMaxRetryCount() throws Exception {
        ImmutableMessage immutableMessage = retryRoutingWith1msDelay(messageRouter, 200);

        verify(messagingStubMock, atLeast(10)).transmit(eq(immutableMessage),
                                                        any(SuccessAction.class),
                                                        any(FailureAction.class));
    }

    @Test
    public void testDelayWithExponentialBackoffLimit() throws Exception {
        final long routingDuration = 10000;
        final long sendMsgRetryIntervalMs = 500;
        final long maxDelayMs = 700;
        final long toleranceMs = 200;

        Module testMaxRetryCountModule = Modules.override(testModule).with(new AbstractModule() {
            @Override
            protected void configure() {
                bind(Long.class).annotatedWith(Names.named(ConfigurableMessagingSettings.PROPERTY_SEND_MSG_RETRY_INTERVAL_MS))
                                .toInstance(sendMsgRetryIntervalMs);
                bind(Long.class).annotatedWith(Names.named(ConfigurableMessagingSettings.PROPERTY_MAX_DELAY_WITH_EXPONENTIAL_BACKOFF_MS))
                                .toInstance(maxDelayMs);

            }
        });
        Injector injector3 = Guice.createInjector(testMaxRetryCountModule);
        MessageRouter messageRouterWithMaxExponentialBackoff = injector3.getInstance(MessageRouter.class);

        doAnswer(new Answer<Object>() {
            private long previousInvocationTimeMs = -1;

            @Override
            public Object answer(InvocationOnMock invocation) throws Throwable {
                if (previousInvocationTimeMs == -1) { // firstRun
                    previousInvocationTimeMs = System.currentTimeMillis();

                } else {
                    long now = System.currentTimeMillis();
                    assertThat(now - previousInvocationTimeMs, lessThan(maxDelayMs + toleranceMs));
                    previousInvocationTimeMs = now;
                }

                invocation.getArgumentAt(2, FailureAction.class).execute(new Exception());

                return null;
            }
        }).when(messagingStubMock)
          .transmit(any(ImmutableMessage.class), any(SuccessAction.class), any(FailureAction.class));

        joynrMessage.setTtlMs(ExpiryDate.fromRelativeTtl(100000000).getValue());
        joynrMessage.setTtlAbsolute(true);
        ImmutableMessage immutableMessage = joynrMessage.getImmutableMessage();

        messageRouterWithMaxExponentialBackoff.route(immutableMessage);
        Thread.sleep(routingDuration);

        // test that the mock is called multiple times which means that
        // the assert inside is multiple times correct
        verify(messagingStubMock, atLeast(10)).transmit(eq(immutableMessage),
                                                        any(SuccessAction.class),
                                                        any(FailureAction.class));
    }

    @Test
    public void testDelayWithoutExponentialBackoffLimit() throws Exception {
        // test idea is that on average more than sendMsgRetryIntervalMs ms are needed.
        // -> at least one run exists that takes longer than sendMsgRetryIntervalMs
        // -> exponential backoff for the retry interval is active
        final long routingDuration = 10000;
        final long sendMsgRetryIntervalMs = 200;
        final long expectedAverageIntervalMs = 500;

        final long maxruns = routingDuration / expectedAverageIntervalMs;

        Module testMaxRetryCountModule = Modules.override(testModule).with(new AbstractModule() {
            @Override
            protected void configure() {
                bind(Long.class).annotatedWith(Names.named(ConfigurableMessagingSettings.PROPERTY_SEND_MSG_RETRY_INTERVAL_MS))
                                .toInstance(sendMsgRetryIntervalMs);
            }
        });
        Injector injector4 = Guice.createInjector(testMaxRetryCountModule);
        MessageRouter messageRouterWithHighRetryInterval = injector4.getInstance(MessageRouter.class);

        doAnswer(new Answer<Object>() {
            @Override
            public Object answer(InvocationOnMock invocation) throws Throwable {
                invocation.getArgumentAt(2, FailureAction.class).execute(new Exception());
                return null;
            }
        }).when(messagingStubMock)
          .transmit(any(ImmutableMessage.class), any(SuccessAction.class), any(FailureAction.class));

        joynrMessage.setTtlMs(ExpiryDate.fromRelativeTtl(100000000).getValue());
        joynrMessage.setTtlAbsolute(true);
        ImmutableMessage immutableMessage = joynrMessage.getImmutableMessage();

        messageRouterWithHighRetryInterval.route(immutableMessage);
        Thread.sleep(routingDuration);

        // make sure that the stub is called at least few times
        // but not too often which means that the average retry interval
        // is much higher then initially set in sendMsgRetryIntervalMs
        verify(messagingStubMock, atLeast(5)).transmit(eq(immutableMessage),
                                                       any(SuccessAction.class),
                                                       any(FailureAction.class));
        verify(messagingStubMock, atMost((int) maxruns)).transmit(eq(immutableMessage),
                                                                  any(SuccessAction.class),
                                                                  any(FailureAction.class));
    }

    @Test
    public void testMessageProcessedListenerCalledOnSuccessForRequest() throws Exception {
        final Semaphore semaphore = new Semaphore(0);

        joynrMessage.setTtlMs(ExpiryDate.fromRelativeTtl(100000000).getValue());
        joynrMessage.setTtlAbsolute(true);
        final ImmutableMessage immutableMessage = joynrMessage.getImmutableMessage();

        final MessageProcessedListener mockMessageProcessedListener = mock(MessageProcessedListener.class);
        messageRouter.registerMessageProcessedListener(mockMessageProcessedListener);

        doAnswer(new Answer<Void>() {
            @Override
            public Void answer(InvocationOnMock invocation) throws Throwable {
                verify(mockMessageProcessedListener, times(0)).messageProcessed(eq(immutableMessage.getId()));
                invocation.getArgumentAt(1, SuccessAction.class).execute();
                semaphore.release();
                return null;
            }
        }).when(messagingStubMock)
          .transmit(any(ImmutableMessage.class), any(SuccessAction.class), any(FailureAction.class));

        messageRouter.route(immutableMessage);

        semaphore.tryAcquire(1000, TimeUnit.MILLISECONDS);
        verify(mockMessageProcessedListener).messageProcessed(eq(immutableMessage.getId()));
        verify(routingTable, never()).remove(anyString());
    }

    @Test
    public void testMessageProcessedListenerCalledOnSuccessForReply() throws Exception {
        final Semaphore semaphore = new Semaphore(0);

        final Reply reply = new Reply("requestReplyId", new JoynrRuntimeException("TestException"));
        joynrMessage = messageFactory.createReply(fromParticipantId, toParticipantId, reply, new MessagingQos());
        joynrMessage.setTtlMs(ExpiryDate.fromRelativeTtl(100000000).getValue());
        joynrMessage.setTtlAbsolute(true);
        final ImmutableMessage immutableMessage = joynrMessage.getImmutableMessage();

        final MessageProcessedListener mockMessageProcessedListener = mock(MessageProcessedListener.class);
        messageRouter.registerMessageProcessedListener(mockMessageProcessedListener);

        doAnswer(new Answer<Void>() {
            @Override
            public Void answer(InvocationOnMock invocation) throws Throwable {
                verify(mockMessageProcessedListener, times(0)).messageProcessed(eq(immutableMessage.getId()));
                invocation.getArgumentAt(1, SuccessAction.class).execute();
                semaphore.release();
                return null;
            }
        }).when(messagingStubMock)
          .transmit(any(ImmutableMessage.class), any(SuccessAction.class), any(FailureAction.class));

        messageRouter.route(immutableMessage);

        semaphore.tryAcquire(1000, TimeUnit.MILLISECONDS);
        verify(mockMessageProcessedListener).messageProcessed(eq(immutableMessage.getId()));
        verify(routingTable, times(1)).remove(eq(joynrMessage.getRecipient()));
    }

    @Test
    public void testReferenceCountDecreasedOnSubscriptionReplyOfInProcessProvider() throws Exception {
        final Semaphore semaphore = new Semaphore(0);

        InProcessAddress providerAddress = new InProcessAddress();
        routingTable.put(fromParticipantId, providerAddress, true, Long.MAX_VALUE, true);

        SubscriptionReply subscriptionReply = new SubscriptionReply("subscriptionId");

        joynrMessage = messageFactory.createSubscriptionReply(fromParticipantId,
                                                              toParticipantId,
                                                              subscriptionReply,
                                                              new MessagingQos());

        joynrMessage.setTtlMs(ExpiryDate.fromRelativeTtl(100000000).getValue());
        final ImmutableMessage immutableMessage = joynrMessage.getImmutableMessage();

        final MessageProcessedListener mockMessageProcessedListener = mock(MessageProcessedListener.class);
        messageRouter.registerMessageProcessedListener(mockMessageProcessedListener);

        doAnswer(new Answer<Void>() {
            @Override
            public Void answer(InvocationOnMock invocation) throws Throwable {
                verify(mockMessageProcessedListener, times(0)).messageProcessed(eq(immutableMessage.getId()));
                verify(routingTable, times(0)).remove(toParticipantId);
                invocation.getArgumentAt(1, SuccessAction.class).execute();
                semaphore.release();
                return null;
            }
        }).when(messagingStubMock)
          .transmit(any(ImmutableMessage.class), any(SuccessAction.class), any(FailureAction.class));

        messageRouter.route(immutableMessage);

        semaphore.tryAcquire(1000, TimeUnit.MILLISECONDS);
        verify(mockMessageProcessedListener).messageProcessed(eq(immutableMessage.getId()));
        verify(routingTable).remove(toParticipantId);
    }

    private void testReferenceCountNotDecreasedOnSubscriptionReplyOfNotInProcessProvider(Address address) throws Exception {
        final Semaphore semaphore = new Semaphore(0);

        routingTable.put(fromParticipantId, address, true, Long.MAX_VALUE, true);

        SubscriptionReply subscriptionReply = new SubscriptionReply("subscriptionId");

        joynrMessage = messageFactory.createSubscriptionReply(fromParticipantId,
                                                              toParticipantId,
                                                              subscriptionReply,
                                                              new MessagingQos());

        joynrMessage.setTtlMs(ExpiryDate.fromRelativeTtl(100000000).getValue());
        final ImmutableMessage immutableMessage = joynrMessage.getImmutableMessage();

        final MessageProcessedListener mockMessageProcessedListener = mock(MessageProcessedListener.class);
        messageRouter.registerMessageProcessedListener(mockMessageProcessedListener);

        doAnswer(new Answer<Void>() {
            @Override
            public Void answer(InvocationOnMock invocation) throws Throwable {
                verify(mockMessageProcessedListener, times(0)).messageProcessed(eq(immutableMessage.getId()));
                verify(routingTable, times(0)).remove(toParticipantId);
                invocation.getArgumentAt(1, SuccessAction.class).execute();
                semaphore.release();
                return null;
            }
        }).when(messagingStubMock)
          .transmit(any(ImmutableMessage.class), any(SuccessAction.class), any(FailureAction.class));

        messageRouter.route(immutableMessage);

        semaphore.tryAcquire(1000, TimeUnit.MILLISECONDS);
        verify(mockMessageProcessedListener).messageProcessed(eq(immutableMessage.getId()));
        verify(routingTable, never()).remove(toParticipantId);
    }

    @Test
    public void testReferenceCountDecreasedOnSubscriptionReplyOfBinderProvider() throws Exception {
        testReferenceCountNotDecreasedOnSubscriptionReplyOfNotInProcessProvider(new BinderAddress());
    }

    @Test
    public void testReferenceCountDecreasedOnSubscriptionReplyOfMqttProvider() throws Exception {
        testReferenceCountNotDecreasedOnSubscriptionReplyOfNotInProcessProvider(new MqttAddress());
    }

    @Test
    public void testReferenceCountDecreasedOnSubscriptionReplyOfUdsClientProvider() throws Exception {
        testReferenceCountNotDecreasedOnSubscriptionReplyOfNotInProcessProvider(new UdsClientAddress());
    }

    @Test
    public void testReferenceCountDecreasedOnSubscriptionReplyOfWebsocketClientProvider() throws Exception {
        testReferenceCountNotDecreasedOnSubscriptionReplyOfNotInProcessProvider(new WebSocketClientAddress());
    }

    private void testMessageProcessedListenerCalledOnError(MessageRouter messageRouter,
                                                           Class<? extends Exception> expectedException) throws Exception {
        final Semaphore semaphore = new Semaphore(0);
        final ImmutableMessage immutableMessage = joynrMessage.getImmutableMessage();

        MqttAddress proxyAddress = new MqttAddress();
        routingTable.put(fromParticipantId, proxyAddress, true, Long.MAX_VALUE, true);

        final MessageProcessedListener mockMessageProcessedListener = mock(MessageProcessedListener.class);
        doAnswer(new Answer<Void>() {
            @Override
            public Void answer(InvocationOnMock invocation) throws Throwable {
                semaphore.release();
                return null;
            }
        }).when(mockMessageProcessedListener).messageProcessed(anyString());
        messageRouter.registerMessageProcessedListener(mockMessageProcessedListener);

        if (expectedException == null) {
            messageRouter.route(immutableMessage);
        } else {
            try {
                messageRouter.route(immutableMessage);
                fail("Expected exception of type " + expectedException);
            } catch (Exception e) {
                assertEquals(expectedException, e.getClass());
            }
        }

        semaphore.tryAcquire(1000, TimeUnit.MILLISECONDS);
        verify(mockMessageProcessedListener).messageProcessed(eq(immutableMessage.getId()));

        MessageType type = immutableMessage.getType();
        if (AbstractMessageRouter.MESSAGE_TYPE_REQUESTS.contains(type)) {
            verify(routingTable, times(1)).remove(eq(immutableMessage.getSender()));
        } else if (AbstractMessageRouter.MESSAGE_TYPE_REPLIES.contains(type)) {
            verify(routingTable, times(1)).remove(eq(immutableMessage.getRecipient()));
        }
    }

    @Test
    public void testMessageProcessedListenerCalledForExpiredRequestMessage() throws Exception {
        joynrMessage.setTtlMs(ExpiryDate.fromRelativeTtl(0).getValue());
        joynrMessage.setTtlAbsolute(true);

        testMessageProcessedListenerCalledOnError(messageRouter, JoynrMessageExpiredException.class);
    }

    @Test
    public void testMessageProcessedListenerCalledForExpiredReplyMessage() throws Exception {
        final Reply reply = new Reply("requestReplyId", new JoynrRuntimeException("TestException"));
        joynrMessage = messageFactory.createReply(fromParticipantId, toParticipantId, reply, new MessagingQos());
        joynrMessage.setTtlMs(ExpiryDate.fromRelativeTtl(0).getValue());
        joynrMessage.setTtlAbsolute(true);

        testMessageProcessedListenerCalledOnError(messageRouter, JoynrMessageExpiredException.class);
    }

    @Test
    public void testMessageProcessedListenerCalledForMessageWithRelativeTtl() throws Exception {
        joynrMessage.setTtlMs(ExpiryDate.fromRelativeTtl(100000000).getValue());
        joynrMessage.setTtlAbsolute(false);

        testMessageProcessedListenerCalledOnError(messageRouter, JoynrRuntimeException.class);
    }

    @Test
    public void testMessageProcessedListenerCalledForAbortedMessage() throws Exception {
        doThrow(new JoynrMessageNotSentException("test")).when(messagingStubMock)
                                                         .transmit(any(ImmutableMessage.class),
                                                                   any(SuccessAction.class),
                                                                   any(FailureAction.class));

        joynrMessage.setTtlMs(ExpiryDate.fromRelativeTtl(100000000).getValue());
        joynrMessage.setTtlAbsolute(true);

        // If an aborted message is not expired, a reply will be sent to the sender.
        // The decrease of the reference count happens after that
        testMessageProcessedListenerCalledOnError(messageRouter, null);
    }

    @Test
    public void testMessageProcessedListenerCalledAfterMaxRetry() throws Exception {
        final long routingMaxRetryCount = 0;
        MessageRouter messageRouterWithMaxRetryCount = getMessageRouterWithMaxRetryCount(routingMaxRetryCount);

        doThrow(new JoynrDelayMessageException(1, "test")).when(messagingStubMock)
                                                          .transmit(any(ImmutableMessage.class),
                                                                    any(SuccessAction.class),
                                                                    any(FailureAction.class));

        joynrMessage.setTtlMs(ExpiryDate.fromRelativeTtl(100000000).getValue());
        joynrMessage.setTtlAbsolute(true);

        testMessageProcessedListenerCalledOnError(messageRouterWithMaxRetryCount, null);
    }

    @Test
    public void testMessageProcessedListenerOnlyCalledOnceForMulticast() throws Exception {
        final Semaphore semaphore = new Semaphore(-1);

        MqttAddress receiverAddress1 = new MqttAddress("what", "ever1");
        MqttAddress receiverAddress2 = new MqttAddress("what", "ever2");
        prepareMulticastForMultipleAddresses(receiverAddress1, receiverAddress2);
        final ImmutableMessage immutableMessage = joynrMessage.getImmutableMessage();

        final MessageProcessedListener mockMessageProcessedListener = mock(MessageProcessedListener.class);
        messageRouter.registerMessageProcessedListener(mockMessageProcessedListener);

        doAnswer(new Answer<Void>() {
            @Override
            public Void answer(InvocationOnMock invocation) throws Throwable {
                verify(mockMessageProcessedListener, times(0)).messageProcessed(eq(immutableMessage.getId()));
                invocation.getArgumentAt(1, SuccessAction.class).execute();
                semaphore.release();
                return null;
            }
        }).when(messagingStubMock).transmit(eq(immutableMessage), any(SuccessAction.class), any(FailureAction.class));

        messageRouter.route(immutableMessage);

        semaphore.tryAcquire(1000, TimeUnit.MILLISECONDS);
        verify(messagingStubMock, times(2)).transmit(eq(immutableMessage),
                                                     any(SuccessAction.class),
                                                     any(FailureAction.class));
        verify(mockMessageProcessedListener).messageProcessed(eq(immutableMessage.getId()));
    }

    @Test
    public void testWebSocketClientParticipantIdsRetrievedOnlyOnce() throws Exception {
        joynrMessage.setTtlMs(ExpiryDate.fromRelativeTtl(100000).getValue());
        joynrMessage.setTtlAbsolute(true);
        final ImmutableMessage immutableMessage = joynrMessage.getImmutableMessage();

        final Set<String> recipientSet = new HashSet<>();
        recipientSet.add(joynrMessage.getImmutableMessage().getRecipient());
        doReturn(recipientSet).when(addressManager).getParticipantIdsForImmutableMessage(immutableMessage);

        when(websocketClientMessagingStubFactoryMock.create(any(WebSocketClientAddress.class))).thenReturn(messagingStubMock);
        doThrow(new JoynrDelayMessageException(100, "test")).when(messagingStubMock)
                                                            .transmit(any(ImmutableMessage.class),
                                                                      any(SuccessAction.class),
                                                                      any(FailureAction.class));

        messageRouter.route(immutableMessage);
        Thread.sleep(500);

        verify(addressManager, times(1)).getParticipantIdsForImmutableMessage(immutableMessage);
        verify(addressManager, atLeast(2)).getAddressForDelayableImmutableMessage(any());
        final ArgumentCaptor<DelayableImmutableMessage> passedDelayableMessage = ArgumentCaptor.forClass(DelayableImmutableMessage.class);
        verify(messageQueue, atLeast(2)).put(passedDelayableMessage.capture());
        verify(messagingStubMock, atLeast(2)).transmit(eq(immutableMessage),
                                                       any(SuccessAction.class),
                                                       any(FailureAction.class));
        assertTrue(passedDelayableMessage.getAllValues().size() >= 2);
        Set<ImmutableMessage> passedImmutableMessages = passedDelayableMessage.getAllValues()
                                                                              .stream()
                                                                              .map(DelayableImmutableMessage::getMessage)
                                                                              .collect(Collectors.toSet());
        assertTrue(passedImmutableMessages.size() == 1);
        assertTrue(passedImmutableMessages.contains(immutableMessage));
    }

    @Test
    public void testRepeatedAddressResolutionForMulticast() throws Exception {
        final String multicastId = "multicast/id/test";
        joynrMessage = messageFactory.createMulticast(fromParticipantId,
                                                      new MulticastPublication(new ArrayList<>(), multicastId),
                                                      new MessagingQos());
        joynrMessage.setTtlMs(ExpiryDate.fromRelativeTtl(1000).getValue());
        joynrMessage.setTtlAbsolute(true);
        ImmutableMessage immutableMessage = joynrMessage.getImmutableMessage();

        final Set<String> participantIdSet = new HashSet<>();
        participantIdSet.add(AddressManager.multicastAddressCalculatorParticipantId);
        doReturn(participantIdSet).when(addressManager).getParticipantIdsForImmutableMessage(immutableMessage);
        doReturn(Optional.of(mqttAddress)).when(addressManager).getAddressForDelayableImmutableMessage(any());

        doThrow(new JoynrDelayMessageException(200, "test42")).when(messagingStubMock)
                                                              .transmit(any(ImmutableMessage.class),
                                                                        any(SuccessAction.class),
                                                                        any(FailureAction.class));

        messageRouter.route(immutableMessage);
        Thread.sleep(550);

        verify(addressManager, times(1)).getParticipantIdsForImmutableMessage(immutableMessage);
        verify(addressManager, atLeast(2)).getAddressForDelayableImmutableMessage(any());
        final ArgumentCaptor<DelayableImmutableMessage> passedDelayableMessage = ArgumentCaptor.forClass(DelayableImmutableMessage.class);
        verify(messageQueue, atLeast(2)).put(passedDelayableMessage.capture());
        assertTrue(passedDelayableMessage.getAllValues().size() >= 2);
        Set<ImmutableMessage> passedImmutableMessages = passedDelayableMessage.getAllValues()
                                                                              .stream()
                                                                              .map(DelayableImmutableMessage::getMessage)
                                                                              .collect(Collectors.toSet());
        assertTrue(passedImmutableMessages.size() == 1);
        assertTrue(passedImmutableMessages.contains(immutableMessage));
    }

    private void testOnlyOneParticipantIdResolution(final Address address) throws Exception {
        reset(messageQueue);
        final int ttlMs = 550;

        joynrMessage.setTtlMs(ExpiryDate.fromRelativeTtl(ttlMs).getValue());
        joynrMessage.setTtlAbsolute(true);
        final ImmutableMessage immutableMessage = joynrMessage.getImmutableMessage();

        final Set<String> participantIdSet = new HashSet<>();
        participantIdSet.add(joynrMessage.getImmutableMessage().getRecipient());
        doReturn(participantIdSet).when(addressManager).getParticipantIdsForImmutableMessage(immutableMessage);
        doReturn(Optional.of(address)).when(addressManager).getAddressForDelayableImmutableMessage(any());

        doThrow(new JoynrDelayMessageException(200, "test")).when(messagingStubMock)
                                                            .transmit(any(ImmutableMessage.class),
                                                                      any(SuccessAction.class),
                                                                      any(FailureAction.class));

        messageRouter.route(immutableMessage);
        Thread.sleep(ttlMs);

        verify(addressManager, times(1)).getParticipantIdsForImmutableMessage(immutableMessage);
        verify(addressManager, atLeast(2)).getAddressForDelayableImmutableMessage(any());
        final ArgumentCaptor<DelayableImmutableMessage> passedDelayableMessage = ArgumentCaptor.forClass(DelayableImmutableMessage.class);
        verify(messageQueue, atLeast(2)).put(passedDelayableMessage.capture());
        assertTrue("Size was " + passedDelayableMessage.getAllValues().size(),
                   passedDelayableMessage.getAllValues().size() >= 2);
        Set<ImmutableMessage> passedImmutableMessages = passedDelayableMessage.getAllValues()
                                                                              .stream()
                                                                              .map(DelayableImmutableMessage::getMessage)
                                                                              .collect(Collectors.toSet());
        assertTrue("Size was " + passedImmutableMessages.size(), passedImmutableMessages.size() == 1);
        assertTrue(passedImmutableMessages.contains(immutableMessage));
    }

    @Test
    public void testOnlyOneParticipantIdResolution() throws Exception {
        testOnlyOneParticipantIdResolution(mqttAddress);

        when(mqttMessagingStubFactoryMock.create(any(MqttAddress.class))).thenReturn(messagingStubMock);
        testOnlyOneParticipantIdResolution(new MqttAddress("brokerUri", "topic"));

        when(webSocketMessagingStubFactoryMock.create(any(WebSocketAddress.class))).thenReturn(messagingStubMock);
        testOnlyOneParticipantIdResolution(new WebSocketAddress(WebSocketProtocol.WS, "host", 42, "path"));

        when(websocketClientMessagingStubFactoryMock.create(any(WebSocketClientAddress.class))).thenReturn(messagingStubMock);
        testOnlyOneParticipantIdResolution(new WebSocketClientAddress());

        when(inProcessMessagingStubFactoryMock.create(any(InProcessAddress.class))).thenReturn(messagingStubMock);
        testOnlyOneParticipantIdResolution(new InProcessAddress(mock(InProcessMessagingSkeleton.class)));
    }

    private void testNotRoutableMessageIsDropped(final MutableMessage mutableMessage) throws Exception {
        final ImmutableMessage immutableMessage = mutableMessage.getImmutableMessage();

        final Set<String> recipientSet = new HashSet<>();
        recipientSet.add(mutableMessage.getImmutableMessage().getRecipient());
        doReturn(recipientSet).when(addressManager).getParticipantIdsForImmutableMessage(immutableMessage);
        doReturn(Optional.empty()).when(addressManager).getAddressForDelayableImmutableMessage(any());

        CountDownLatch countDownLatch = new CountDownLatch(1);
        MessageProcessedListener mockMsgProcessedListener = new MessageProcessedListener() {
            @Override
            public void messageProcessed(String messageId) {
                if (messageId.equals(immutableMessage.getId())) {
                    countDownLatch.countDown();
                }
            }
        };
        messageRouter.registerMessageProcessedListener(mockMsgProcessedListener);

        messageRouter.route(immutableMessage);
        assertTrue(countDownLatch.await(3000, TimeUnit.MILLISECONDS));
        verify(messageQueue, times(1)).put(any(DelayableImmutableMessage.class));
        verify(addressManager, times(1)).getParticipantIdsForImmutableMessage(immutableMessage);
        verify(addressManager, times(1)).getAddressForDelayableImmutableMessage(any(DelayableImmutableMessage.class));
        verifyNoMoreInteractions(messagingStubMock);
    }

    @Test
    public void testNotRoutableReplyDropped() throws Exception {
        final String unknownParticipantId = "unknown_participant_id";
        final String requestReplyId = "some_request_reply_id";

        final Reply reply = new Reply(requestReplyId, new JoynrRuntimeException("TestException"));

        final MutableMessage mutableMessage = messageFactory.createReply(fromParticipantId,
                                                                         unknownParticipantId,
                                                                         reply,
                                                                         new MessagingQos());

        testNotRoutableMessageIsDropped(mutableMessage);
    }

    @Test
    public void testNotRoutableMulticastDropped() throws Exception {
        final MulticastPublication multicastPublication = new MulticastPublication(new JoynrRuntimeException("Test Exception"),
                                                                                   "multicastId");
        final MutableMessage mutableMessage = messageFactory.createMulticast(fromParticipantId,
                                                                             multicastPublication,
                                                                             new MessagingQos());

        testNotRoutableMessageIsDropped(mutableMessage);
    }

    @Test
    public void testMulticastMessageIsDroppedIfNoRecipientIsFound() throws Exception {
        final MulticastPublication multicastPublication = new MulticastPublication(new JoynrRuntimeException("Test Exception"),
                                                                                   "multicastId");
        final MutableMessage mutableMessage = messageFactory.createMulticast(fromParticipantId,
                                                                             multicastPublication,
                                                                             new MessagingQos());

        final ImmutableMessage immutableMessage = mutableMessage.getImmutableMessage();

        CountDownLatch countDownLatch = new CountDownLatch(1);
        MessageProcessedListener mockMsgProcessedListener = new MessageProcessedListener() {
            @Override
            public void messageProcessed(String messageId) {
                if (messageId.equals(immutableMessage.getId())) {
                    countDownLatch.countDown();
                }
            }
        };
        messageRouter.registerMessageProcessedListener(mockMsgProcessedListener);

        messageRouter.route(immutableMessage);
        assertTrue(countDownLatch.await(3000, TimeUnit.MILLISECONDS));
        verify(messageQueue, times(0)).put(any(DelayableImmutableMessage.class));

        verify(addressManager).getParticipantIdsForImmutableMessage(immutableMessage);
        verifyNoMoreInteractions(messagingStubMock);
    }

    @Test
    public void testPublicationMessageIsDroppedIfNoAddressIsFound() throws Exception {
        final String unknownParticipantId = "unknown_participant_id";
        final SubscriptionPublication subscriptionPublication = new SubscriptionPublication(new JoynrRuntimeException("Test Exception"),
                                                                                            "subscriptionId");
        final MutableMessage mutableMessage = messageFactory.createPublication(fromParticipantId,
                                                                               unknownParticipantId,
                                                                               subscriptionPublication,
                                                                               new MessagingQos());

        testNotRoutableMessageIsDropped(mutableMessage);
    }

    @Test
    public void testSubscriptionReplyMessageIsDroppedIfNoAddressIsFound() throws Exception {
        final String unknownParticipantId = "unknown_participant_id";
        final SubscriptionReply subscriptionReply = new SubscriptionReply("subscriptionId");
        final MutableMessage mutableMessage = messageFactory.createSubscriptionReply(fromParticipantId,
                                                                                     unknownParticipantId,
                                                                                     subscriptionReply,
                                                                                     new MessagingQos());

        testNotRoutableMessageIsDropped(mutableMessage);
    }

    @Test
    public void testShutdown() throws InterruptedException {
        verify(shutdownNotifier).registerForShutdown((CcMessageRouter) messageRouter);
    }

    @Test(timeout = 3000)
    public void testFailedTransmitDoesNotLeadToThreadStarvation() throws Exception {
        final int MESSAGE_LOAD = 10;

        final String recipient = "to";
        ImmutableMessage failingMessage = mock(ImmutableMessage.class);
        when(failingMessage.isTtlAbsolute()).thenReturn(true);
        when(failingMessage.getTtlMs()).thenReturn(ExpiryDate.fromRelativeTtl(1000L).getValue());
        when(failingMessage.getRecipient()).thenReturn(recipient);
        when(failingMessage.getType()).thenReturn(Message.MessageType.VALUE_MESSAGE_TYPE_REPLY);

        Set<String> participantIdSet = new HashSet<>();
        participantIdSet.add(recipient);

        doReturn(participantIdSet).when(addressManager).getParticipantIdsForImmutableMessage(failingMessage);
        doReturn(Optional.of(mqttAddress)).when(addressManager).getAddressForDelayableImmutableMessage(any());

        doAnswer(new Answer<Object>() {
            @Override
            public Object answer(InvocationOnMock invocation) throws Throwable {
                assertEquals(invocation.getArguments().length, 3);
                FailureAction failureAction = (FailureAction) invocation.getArguments()[2];
                failureAction.execute(new Exception("Some error"));
                return null;
            }
        }).when(messagingStubMock).transmit(eq(failingMessage), any(SuccessAction.class), any(FailureAction.class));

        for (int i = 0; i < MESSAGE_LOAD; i++) {
            messageRouter.route(failingMessage);
        }

        Thread.sleep(2000);
        verify(messagingStubMock, atLeast(MESSAGE_LOAD * 3)).transmit(eq(failingMessage),
                                                                      any(SuccessAction.class),
                                                                      any(FailureAction.class));

        ImmutableMessage anotherMessage = mock(ImmutableMessage.class);
        when(anotherMessage.isTtlAbsolute()).thenReturn(true);
        when(anotherMessage.getTtlMs()).thenReturn(ExpiryDate.fromRelativeTtl(1000L).getValue());
        when(anotherMessage.getRecipient()).thenReturn("to");
        when(anotherMessage.getType()).thenReturn(Message.MessageType.VALUE_MESSAGE_TYPE_REPLY);
        doReturn(participantIdSet).when(addressManager).getParticipantIdsForImmutableMessage(anotherMessage);

        final Semaphore semaphore = new Semaphore(0);
        doAnswer(new Answer<Object>() {
            @Override
            public Object answer(InvocationOnMock invocation) throws Throwable {
                assertEquals(invocation.getArguments().length, 3);
                SuccessAction successAction = (SuccessAction) invocation.getArguments()[1];
                successAction.execute();
                semaphore.release();
                return null;
            }
        }).when(messagingStubMock).transmit(eq(anotherMessage), any(SuccessAction.class), any(FailureAction.class));

        messageRouter.route(anotherMessage);
        assertTrue(semaphore.tryAcquire(100, TimeUnit.MILLISECONDS));
    }

    @SuppressWarnings("unchecked")
    @Test
    public void messageWorkerUsesSameFailureActionForStubAndThrownException() throws Exception {
        // route multicast message to two recipients
        // call failureAction in first stub call
        // throw exception in second stub call
        // make sure that the message is rescheduled only once
        // (multiple executions of the same failure action reschedule only in the first call, further calls are just logged)
        MqttAddress receiverAddress1 = new MqttAddress("what", "ever1");
        MqttAddress receiverAddress2 = new MqttAddress("what", "ever2");
        prepareMulticastForMultipleAddresses(receiverAddress1, receiverAddress2);
        ImmutableMessage immutableMessage = joynrMessage.getImmutableMessage();

        IMessagingStub messagingStubMock1 = mock(IMessagingStub.class);
        IMessagingStub messagingStubMock2 = mock(IMessagingStub.class);
        reset(mqttMessagingStubFactoryMock);
        doReturn(messagingStubMock1).when(mqttMessagingStubFactoryMock).create(receiverAddress1);
        doReturn(messagingStubMock2).when(mqttMessagingStubFactoryMock).create(receiverAddress2);

        Answer<Void> stubAnswer = new Answer<Void>() {
            private volatile int callCount = 0;

            @Override
            public Void answer(InvocationOnMock invocation) throws Throwable {
                callCount++;
                FailureAction failureAction = invocation.getArgumentAt(2, FailureAction.class);
                switch (callCount) {
                case 1:
                    failureAction.execute(new JoynrDelayMessageException(32, "first stub call, failureAction"));
                    break;
                case 2:
                    throw new JoynrDelayMessageException(32, "first stub call, thrown exception");
                case 3:
                    // second stub call of stub 1
                    break;
                case 4:
                    // second stub call of stub 2
                    break;
                default:
                    fail("expected no more calls");
                }
                return null;
            }
        };
        doAnswer(stubAnswer).when(messagingStubMock1)
                            .transmit(eq(immutableMessage), any(SuccessAction.class), any(FailureAction.class));
        doAnswer(stubAnswer).when(messagingStubMock2)
                            .transmit(eq(immutableMessage), any(SuccessAction.class), any(FailureAction.class));

        messageRouter.route(immutableMessage);

        Thread.sleep(1000);

        verify(messagingStubMock1, times(2)).transmit(eq(immutableMessage),
                                                      any(SuccessAction.class),
                                                      any(FailureAction.class));
        verify(messagingStubMock2, times(2)).transmit(eq(immutableMessage),
                                                      any(SuccessAction.class),
                                                      any(FailureAction.class));
        verify(mqttMessagingStubFactoryMock, times(2)).create(receiverAddress1);
        verify(mqttMessagingStubFactoryMock, times(2)).create(receiverAddress2);
    }

    @Test
    public void setToKnownDoesNotChangeRoutingTable() {
        final String participantId = "setToKnownParticipantId";
        messageRouter.setToKnown(participantId);
        verify(routingTable, times(0)).put(eq(participantId), any(Address.class), anyBoolean(), anyLong());
        verify(routingTable,
               times(0)).put(eq(participantId), any(Address.class), anyBoolean(), anyLong(), anyBoolean());
        assertFalse(routingTable.containsKey(participantId));
    }

    private MessageRouter getMessageRouterWithEnabledAccessControl() {
        // Reconfigure testModule to enable access control
        // return messageRouter with an enabled access control
        Module testTryCatchModule = Modules.override(testModule).with(new AbstractModule() {
            @Override
            protected void configure() {
                bindConstant().annotatedWith(Names.named(ClusterControllerRuntimeModule.PROPERTY_ACCESSCONTROL_ENABLE))
                              .to(true);
            }
        });
        Injector injector5 = Guice.createInjector(testTryCatchModule);
        return injector5.getInstance(MessageRouter.class);
    }

    @Test
    public void testMessageProcessedCalledWhenHasConsumerPermissionTrue() throws Exception {
        // Test whether try_catch inside "hasConsumerPermission" callback in CcMessageRouter.route works as expected
        // pre-conditions: access control is enabled and permission is granted
        // Expected behaviour: The method messageProcessed is called at least once when JoynrMessageNotSentException is thrown
        // and the message will be dropped after the catch.
        MessageRouter messageRouterWithAccessControl = getMessageRouterWithEnabledAccessControl();

        final MessageProcessedListener mockMessageProcessedListener = mock(MessageProcessedListener.class);
        messageRouterWithAccessControl.registerMessageProcessedListener(mockMessageProcessedListener);

        doAnswer(new Answer<Void>() {
            @Override
            public Void answer(InvocationOnMock invocation) throws Throwable {
                HasConsumerPermissionCallback callback = invocation.getArgumentAt(1,
                                                                                  HasConsumerPermissionCallback.class);
                callback.hasConsumerPermission(true);
                return null;
            }
        }).when(accessControllerMock)
          .hasConsumerPermission(any(ImmutableMessage.class), any(HasConsumerPermissionCallback.class));

        // JoynrMessageNotSentException will be thrown because of expired message.
        joynrMessage.setTtlMs(ExpiryDate.fromRelativeTtl(0).getValue());
        joynrMessage.setTtlAbsolute(true);
        ImmutableMessage immutableMessage = joynrMessage.getImmutableMessage();

        messageRouterWithAccessControl.route(immutableMessage);

        verify(accessControllerMock, times(1)).hasConsumerPermission(eq(immutableMessage),
                                                                     any(HasConsumerPermissionCallback.class));
        verify(mockMessageProcessedListener, atLeast(1)).messageProcessed(eq(immutableMessage.getId()));
    }

    @Test
    public void testMessageProcessedCalledWhenHasConsumerPermissionTrueWithRelativeTtl() throws Exception {
        // Test whether try_catch inside "hasConsumerPermission" callback in CcMessageRouter.route works as expected
        // pre-conditions: access control is enabled and permission is granted
        // Expected behaviour: The method messageProcessed is called at least once when JoynrRuntimeException is thrown
        // and the message will be dropped after the catch.
        MessageRouter messageRouterWithAccessControl = getMessageRouterWithEnabledAccessControl();

        final MessageProcessedListener mockMessageProcessedListener = mock(MessageProcessedListener.class);
        messageRouterWithAccessControl.registerMessageProcessedListener(mockMessageProcessedListener);

        doAnswer(new Answer<Void>() {
            @Override
            public Void answer(InvocationOnMock invocation) throws Throwable {
                HasConsumerPermissionCallback callback = invocation.getArgumentAt(1,
                                                                                  HasConsumerPermissionCallback.class);
                callback.hasConsumerPermission(true);
                return null;
            }
        }).when(accessControllerMock)
          .hasConsumerPermission(any(ImmutableMessage.class), any(HasConsumerPermissionCallback.class));

        // JoynrRuntimeException will be thrown because relative ttl is not supported
        joynrMessage.setTtlAbsolute(false);
        ImmutableMessage immutableMessage = joynrMessage.getImmutableMessage();

        messageRouterWithAccessControl.route(immutableMessage);

        verify(accessControllerMock, times(1)).hasConsumerPermission(eq(immutableMessage),
                                                                     any(HasConsumerPermissionCallback.class));
        verify(mockMessageProcessedListener, atLeast(1)).messageProcessed(eq(immutableMessage.getId()));
    }

    @Test
    public void testMessageProcessedCalledWhenHasConsumerPermissionFalse() throws Exception {
        // Test whether try_catch inside "hasConsumerPermission" callback in CcMessageRouter.route works as expected
        // pre-conditions: access control is enabled and permission is denied
        // Expected behaviour: The method messageProcessed is called only once when hasPermission = false
        // and the message will be dropped.
        MessageRouter messageRouterWithAccessControl = getMessageRouterWithEnabledAccessControl();

        final MessageProcessedListener mockMessageProcessedListener = mock(MessageProcessedListener.class);
        messageRouterWithAccessControl.registerMessageProcessedListener(mockMessageProcessedListener);

        doAnswer(new Answer<Void>() {
            @Override
            public Void answer(InvocationOnMock invocation) throws Throwable {
                HasConsumerPermissionCallback callback = invocation.getArgumentAt(1,
                                                                                  HasConsumerPermissionCallback.class);
                callback.hasConsumerPermission(false);
                return null;
            }
        }).when(accessControllerMock)
          .hasConsumerPermission(any(ImmutableMessage.class), any(HasConsumerPermissionCallback.class));

        ImmutableMessage immutableMessage = joynrMessage.getImmutableMessage();

        messageRouterWithAccessControl.route(immutableMessage);

        verify(accessControllerMock, times(1)).hasConsumerPermission(eq(immutableMessage),
                                                                     any(HasConsumerPermissionCallback.class));
        verify(mockMessageProcessedListener, times(1)).messageProcessed(eq(immutableMessage.getId()));
        verify(routingTable).remove(eq(immutableMessage.getSender()));
    }
}
