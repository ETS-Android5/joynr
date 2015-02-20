package io.joynr.channel;

/*
 * #%L
 * %%
 * Copyright (C) 2011 - 2014 BMW Car IT GmbH
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

import io.joynr.dispatcher.rpc.Callback;
import io.joynr.exceptions.JoynrException;

import java.util.Arrays;
import java.util.List;
import java.util.UUID;

import joynr.types.ChannelUrlInformation;

import org.junit.Assert;
import org.junit.Before;
import org.junit.Test;

public class ChannelUrlDirectoryTest {
    private ChannelUrlDirectoyImpl fixture;

    @Before
    public void setup() {
        fixture = new ChannelUrlDirectoyImpl(500);
    }

    private abstract class TestCallback implements Callback<ChannelUrlInformation> {

        @Override
        public void onFailure(JoynrException error) {
            Assert.assertFalse(error.getLocalizedMessage(), true);
        }

    }

    @Test
    public void testDelayedCleanup() throws Exception {

        final String testChannelId = "testDelayedCleanup" + UUID.randomUUID().toString();
        final String[] urls = { "http://testurl.com/" + testChannelId + "/" };
        ChannelUrlInformation channelUrlInformation = new ChannelUrlInformation();
        channelUrlInformation.setUrls(Arrays.asList(urls));

        fixture.registerChannelUrls(null, testChannelId, channelUrlInformation);

        Callback<ChannelUrlInformation> callback = new TestCallback() {

            @Override
            public void onSuccess(ChannelUrlInformation result) {

                List<String> urlsFromServer = result.getUrls();
                Assert.assertArrayEquals(urls, urlsFromServer.toArray(new String[urlsFromServer.size()]));
                fixture.unregisterChannelUrls(null, testChannelId);
                /* after deletion, url shall still be a valid channelurl, as the unregistration shall only affect after
                 * fixture.channelurInactiveTimeInMS
                 */

                Callback<ChannelUrlInformation> callback2 = new TestCallback() {

                    @Override
                    public void onSuccess(ChannelUrlInformation result) {
                        List<String> urlsFromServer = result.getUrls();
                        Assert.assertArrayEquals(urls, urlsFromServer.toArray(new String[urlsFromServer.size()]));

                        synchronized (this) {
                            try {
                                this.wait(fixture.channelurInactiveTimeInMS * 2);
                            } catch (InterruptedException e) {
                                Assert.assertFalse(e.getMessage(), true);
                            }
                        }

                        Callback<ChannelUrlInformation> callback3 = new TestCallback() {

                            @Override
                            public void onSuccess(ChannelUrlInformation result) {
                                Assert.assertEquals(0, result.getUrls().size());
                            }
                        };
                        fixture.getUrlsForChannel(callback3, testChannelId);
                    }
                };
                fixture.getUrlsForChannel(callback2, testChannelId);
            }
        };
        fixture.getUrlsForChannel(callback, testChannelId);
    }

    @Test
    public void testDelayedCleanupWithReactivate() throws Exception {

        final String testChannelId = "testDelayedCleanupWithReactivate" + UUID.randomUUID().toString();
        final String[] urls = { "http://testurl.com/" + testChannelId + "/" };
        final ChannelUrlInformation channelUrlInformation = new ChannelUrlInformation();
        channelUrlInformation.setUrls(Arrays.asList(urls));

        fixture.registerChannelUrls(null, testChannelId, channelUrlInformation);

        fixture.unregisterChannelUrls(null, testChannelId);
        Callback<ChannelUrlInformation> callback = new TestCallback() {

            @Override
            public void onSuccess(ChannelUrlInformation urlsForChannelId) {
                List<String> urlsFromServer = urlsForChannelId.getUrls();
                Assert.assertArrayEquals(urls, urlsFromServer.toArray(new String[urlsFromServer.size()]));
                Assert.assertEquals(1, fixture.inactiveChannelIds.size());
                Assert.assertNotNull(fixture.inactiveChannelIds.get(testChannelId));
                fixture.registerChannelUrls(null, testChannelId, channelUrlInformation);
                Assert.assertEquals(0, fixture.inactiveChannelIds.size());
                synchronized (this) {
                    try {
                        this.wait(fixture.channelurInactiveTimeInMS * 2);
                    } catch (InterruptedException e) {
                        Assert.assertFalse(e.getMessage(), true);
                    }
                }
                Callback<ChannelUrlInformation> callback2 = new TestCallback() {

                    @Override
                    public void onSuccess(ChannelUrlInformation urlsForChannelId) {
                        List<String> urlsFromServer = urlsForChannelId.getUrls();
                        Assert.assertArrayEquals(urls, urlsFromServer.toArray(new String[urlsFromServer.size()]));
                    }
                };
                fixture.getUrlsForChannel(callback2, testChannelId);

            }
        };
        fixture.getUrlsForChannel(callback, testChannelId);

    }
}
