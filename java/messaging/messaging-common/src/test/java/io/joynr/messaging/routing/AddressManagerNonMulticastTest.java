/*
 * #%L
 * %%
 * Copyright (C) 2018 BMW Car IT GmbH
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

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.mockito.Mockito.when;
import static org.mockito.MockitoAnnotations.initMocks;

import java.util.Arrays;
import java.util.HashSet;
import java.util.Set;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.Parameterized.Parameter;
import org.junit.runners.Parameterized.Parameters;
import org.junit.runners.Parameterized;
import org.mockito.Mock;

import joynr.ImmutableMessage;
import joynr.Message;
import joynr.system.RoutingTypes.Address;

@RunWith(Parameterized.class)
public class AddressManagerNonMulticastTest {
    private static final String NO_PRIMARY_GLOBAL_TRANSPORT_SELECTED = null;
    private static final String PARTICIPANT_ID = "participantId";

    @Parameters(name = "{index}: MessageType={0}")
    public static Iterable<? extends Object> getNonMulticastMessageTypes() {
        return Arrays.asList(Message.VALUE_MESSAGE_TYPE_REQUEST,
                             Message.VALUE_MESSAGE_TYPE_REPLY,
                             Message.VALUE_MESSAGE_TYPE_PUBLICATION,
                             Message.VALUE_MESSAGE_TYPE_MULTICAST_SUBSCRIPTION_REQUEST,
                             Message.VALUE_MESSAGE_TYPE_ONE_WAY);
    }

    @Parameter
    public String messageTypeParameter;

    @Mock
    private RoutingTable routingTable;

    @Mock
    private MulticastReceiverRegistry multicastReceiverRegistry;

    @Mock
    private ImmutableMessage joynrMessage;

    @Mock
    private Address address;

    private AddressManager subject;

    @Before
    public void setup() {
        initMocks(this);
        subject = new AddressManager(routingTable,
                                     new AddressManager.PrimaryGlobalTransportHolder(NO_PRIMARY_GLOBAL_TRANSPORT_SELECTED),
                                     new HashSet<MulticastAddressCalculator>(),
                                     multicastReceiverRegistry);
        when(joynrMessage.getType()).thenReturn(messageTypeParameter);
    }

    @Test
    public void testNoAddressFoundForNonMulticastMessage() {
        Set<Address> result = subject.getAddresses(joynrMessage);
        assertEquals(0, result.size());
    }

    @Test
    public void testGetAddressFromRoutingTable() {
        when(routingTable.containsKey(PARTICIPANT_ID)).thenReturn(true);
        when(routingTable.get(PARTICIPANT_ID)).thenReturn(address);
        when(joynrMessage.getRecipient()).thenReturn(PARTICIPANT_ID);

        Set<Address> result = subject.getAddresses(joynrMessage);
        assertNotNull(result);
        assertEquals(1, result.size());
        assertEquals(address, result.iterator().next());
    }
}
