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

import java.util.Properties;

import com.google.inject.Injector;

import io.joynr.messaging.AtmosphereMessagingModule;
import io.joynr.runtime.JoynrBaseModule;
import io.joynr.runtime.JoynrInjectorFactory;
import io.joynr.runtime.JoynrRuntime;

public class LocalCommunicationTest extends AbstractLocalCommunicationTest {

    private Injector injectorA;

    @Override
    protected JoynrRuntime getRuntime(Properties joynrConfig) {
        injectorA = new JoynrInjectorFactory(new JoynrBaseModule(joynrConfig,
                                                                 new AtmosphereMessagingModule())).getInjector();
        return injectorA.getInstance(JoynrRuntime.class);
    }

}
