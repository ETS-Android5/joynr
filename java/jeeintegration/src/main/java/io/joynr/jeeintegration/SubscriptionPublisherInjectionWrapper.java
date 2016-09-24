package io.joynr.jeeintegration;

/*
 * #%L
 * %%
 * Copyright (C) 2011 - 2016 BMW Car IT GmbH
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

import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.ParameterizedType;
import java.lang.reflect.Proxy;
import java.lang.reflect.Type;
import java.util.Arrays;

import io.joynr.exceptions.JoynrIllegalStateException;
import io.joynr.provider.SubscriptionPublisher;
import io.joynr.provider.SubscriptionPublisherInjection;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * This class is used to {@link #createWrapper(Object, Class) create a wrapper} around a
 * bean instance which implements a {@link SubscriptionPublisherInjection} interface for
 * a given concrete {@link SubscriptionPublisher}, and intercepts the {@link SubscriptionPublisherInjection#setSubscriptionPublisher(SubscriptionPublisher) setter}
 * method in order to in turn inject a proxy which wraps the subscription publisher with a
 * {@link SubscriptionPublisherWrapper}, so that this can verify that only multicast compatible
 * publications are made from within the JEE context.
 */
public class SubscriptionPublisherInjectionWrapper implements InvocationHandler {

    private static final Logger logger = LoggerFactory.getLogger(SubscriptionPublisherInjectionWrapper.class);

    private final Object beanInstance;
    private final Class<? extends SubscriptionPublisherInjection> proxiedInterface;
    private final Class<? extends SubscriptionPublisher> subscriptionPublisherClass;

    private SubscriptionPublisherInjectionWrapper(Object beanInstance,
                                                  Class<? extends SubscriptionPublisherInjection> proxiedInterface,
                                                  Class<? extends SubscriptionPublisher> subscriptionPublisherClass) {
        this.beanInstance = beanInstance;
        this.proxiedInterface = proxiedInterface;
        this.subscriptionPublisherClass = subscriptionPublisherClass;
    }

    public static Object createWrapper(Object beanInstance, Class beanClass) {
        logger.debug("Called with {} and {}", beanInstance, beanClass);
        Class proxiedInterface = null;
        Class subscriptionPublisherClass = null;
        for (Type interfaceType : beanClass.getGenericInterfaces()) {
            if (interfaceType instanceof ParameterizedType) {
                ParameterizedType parameterizedType = ((ParameterizedType) interfaceType);
                if (parameterizedType.getRawType() instanceof Class
                        && parameterizedType.getActualTypeArguments().length == 1
                        && SubscriptionPublisher.class.isAssignableFrom((Class) parameterizedType.getActualTypeArguments()[0])) {
                    Class rawType = (Class) parameterizedType.getRawType();
                    if (SubscriptionPublisherInjection.class.isAssignableFrom(rawType)) {
                        proxiedInterface = rawType;
                        subscriptionPublisherClass = (Class) parameterizedType.getActualTypeArguments()[0];
                    }
                }
            }
        }
        logger.debug("Found injector {} and publisher {} classes.", proxiedInterface, subscriptionPublisherClass);
        if (subscriptionPublisherClass == null || proxiedInterface == null) {
            throw new JoynrIllegalStateException("Cannot create subscription publisher injection wrapper proxy for class which doesn't implement the SubscriptionPublisherInjection interface for a valid SubscriptionPublisher interface.");
        }
        return Proxy.newProxyInstance(proxiedInterface.getClassLoader(),
                                      new Class[]{ proxiedInterface },
                                      new SubscriptionPublisherInjectionWrapper(beanInstance,
                                                                                proxiedInterface,
                                                                                subscriptionPublisherClass));
    }

    @Override
    public Object invoke(Object proxy, Method method, Object[] args) throws Throwable {
        if (args.length == 0 || args[0] == null || !subscriptionPublisherClass.isAssignableFrom(args[0].getClass())) {
            throw new JoynrIllegalStateException(String.format("Cannot set the subscription publisher with args %s. First argument must be a non-null instance of a %s.",
                                                               Arrays.toString(args),
                                                               subscriptionPublisherClass));
        }
        ((SubscriptionPublisherInjection) beanInstance).setSubscriptionPublisher(SubscriptionPublisherWrapper.createWrapper((SubscriptionPublisher) args[0],
                                                                                                                            subscriptionPublisherClass));
        return null;
    }
}
