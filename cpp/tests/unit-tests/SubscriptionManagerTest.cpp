/*
 * #%L
 * %%
 * Copyright (C) 2011 - 2013 BMW Car IT GmbH
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
#include <gtest/gtest.h>
#include <gmock/gmock.h>
#include "joynr/SubscriptionManager.h"
#include "joynr/ISubscriptionCallback.h"
#include <QRunnable>
#include <QThreadPool>
#include "tests/utils/MockObjects.h"
#include "utils/TestQString.h"
#include "utils/QThreadSleep.h"
#include "joynr/DispatcherUtils.h"
#include "joynr/SubscriptionCallback.h"
#include "joynr/DelayedScheduler.h"
#include "joynr/joynrlogging.h"
#include "joynr/Directory.h"
#include "joynr/PeriodicSubscriptionQos.h"
#include "joynr/OnChangeSubscriptionQos.h"
#include "joynr/Util.h"


using ::testing::A;
using ::testing::_;
using ::testing::AtLeast;
using ::testing::AtMost;
using ::testing::Between;


using namespace joynr;

TEST(SubscriptionManagerTest, registerSubscription_subscriptionRequestIsCorrect) {
    SubscriptionManager subscriptionManager;
    QSharedPointer<ISubscriptionListener<types::GpsLocation> > mockGpsSubscriptionListener(new MockGpsSubscriptionListener());
    QSharedPointer<SubscriptionCallback<types::GpsLocation> > gpslocationCallback(
                new SubscriptionCallback<types::GpsLocation>(mockGpsSubscriptionListener));
    auto qos = QSharedPointer<SubscriptionQos>(new OnChangeSubscriptionQos());
    qos->setExpiryDate(QDateTime::currentMSecsSinceEpoch() + 10000);
    SubscriptionRequest subscriptionRequest;
    subscriptionManager.registerSubscription(
                "methodName",
                gpslocationCallback,
                qos,
                subscriptionRequest);

    EXPECT_EQ("methodName", subscriptionRequest.getSubscribeToName());
    EXPECT_EQ(qos, subscriptionRequest.getQos());
}

TEST(SubscriptionManagerTest, registerSubscription_missedPublicationRunnableWorks) {
    QSharedPointer<MockGpsSubscriptionListener> mockGpsSubscriptionListener(
                new MockGpsSubscriptionListener());
    EXPECT_CALL(*mockGpsSubscriptionListener,
                publicationMissed())
            .Times(AtLeast(4));
    QSharedPointer<SubscriptionCallback<types::GpsLocation> > gpslocationCallback(
            new SubscriptionCallback<types::GpsLocation>(mockGpsSubscriptionListener));
    SubscriptionManager subscriptionManager;
    auto qos = QSharedPointer<PeriodicSubscriptionQos>(new PeriodicSubscriptionQos(QDateTime::currentMSecsSinceEpoch() + 1100, 100, 200));
    SubscriptionRequest subscriptionRequest;
    subscriptionManager.registerSubscription(
                "methodName",
                gpslocationCallback,
                qos,
                subscriptionRequest
    );
    QThreadSleep::msleep(1200);
}

TEST(SubscriptionManagerTest, registerSubscription_withoutExpiryDate) {
    QSharedPointer<MockGpsSubscriptionListener> mockGpsSubscriptionListener(
                new MockGpsSubscriptionListener());
    QSharedPointer<SubscriptionCallback<types::GpsLocation>> gpslocationCallback(
            new SubscriptionCallback<types::GpsLocation>(mockGpsSubscriptionListener));
    MockDelayedScheduler* mockDelayedScheduler = new MockDelayedScheduler(QString("SubscriptionManager-MockScheduler"));
    EXPECT_CALL(*mockDelayedScheduler,
                schedule(_,_))
            .Times(0);
    SubscriptionManager subscriptionManager(mockDelayedScheduler);
    auto qos = QSharedPointer<OnChangeSubscriptionQos>(new OnChangeSubscriptionQos(-1, 100));
    SubscriptionRequest subscriptionRequest;
    subscriptionManager.registerSubscription(
                "methodName",
                gpslocationCallback,
                qos,
                subscriptionRequest
    );
}

quint32 internalRunnableHandle = 1;

quint32 runnableHandle()
{
    return internalRunnableHandle++;
}

TEST(SubscriptionManagerTest, registerSubscription_withExpiryDate) {
    QSharedPointer<MockGpsSubscriptionListener> mockGpsSubscriptionListener(
                new MockGpsSubscriptionListener());
    QSharedPointer<SubscriptionCallback<types::GpsLocation>> gpslocationCallback(
            new SubscriptionCallback<types::GpsLocation>(mockGpsSubscriptionListener));
    MockDelayedScheduler* mockDelayedScheduler = new MockDelayedScheduler(QString("SubscriptionManager-MockScheduler"));
    EXPECT_CALL(*mockDelayedScheduler,
                schedule(A<QRunnable*>(),_))
            .Times(1).WillRepeatedly(::testing::Return(runnableHandle()));
    SubscriptionManager subscriptionManager(mockDelayedScheduler);
    auto qos = QSharedPointer<OnChangeSubscriptionQos>(new OnChangeSubscriptionQos(QDateTime::currentMSecsSinceEpoch() + 1000, 100));
    SubscriptionRequest subscriptionRequest;
    subscriptionManager.registerSubscription(
                "methodName",
                gpslocationCallback,
                qos,
                subscriptionRequest
    );
}

TEST(SubscriptionManagerTest, unregisterSubscription_unregisterLeadsToStoppingMissedPublicationRunnables) {

    QSharedPointer<MockGpsSubscriptionListener> mockGpsSubscriptionListener(new MockGpsSubscriptionListener());
    EXPECT_CALL(*mockGpsSubscriptionListener,
                publicationMissed())
            .Times(Between(2,3));
    SubscriptionManager subscriptionManager;
    QSharedPointer<SubscriptionCallback<types::GpsLocation>> gpslocationCallback(
                new SubscriptionCallback<types::GpsLocation>(mockGpsSubscriptionListener));
    auto qos = QSharedPointer<PeriodicSubscriptionQos>(new PeriodicSubscriptionQos(
                QDateTime::currentMSecsSinceEpoch() + 2000, // expiry date
                100,                                        // period
                400                                         // alert after interval
    ));
    SubscriptionRequest subscriptionRequest;
    subscriptionManager.registerSubscription(
                "methodName",
                gpslocationCallback,
                qos,
                subscriptionRequest);
     QThreadSleep::msleep(900);
     subscriptionManager.unregisterSubscription(subscriptionRequest.getSubscriptionId());
     QThreadSleep::msleep(1100);
}

TEST(SubscriptionManagerTest, unregisterSubscription_unregisterLeadsOnNonExistantSubscription) {
    QSharedPointer<MockGpsSubscriptionListener> mockGpsSubscriptionListener(new MockGpsSubscriptionListener());
    SubscriptionManager subscriptionManager;
    subscriptionManager.unregisterSubscription("superId");
}

class TestRunnable : public QRunnable {
public:
    virtual ~TestRunnable() {

    }
    TestRunnable() {

    }
    void run() {
        LOG_TRACE(logger, "run: entering...");
        QThreadSleep::msleep(200);
        LOG_TRACE(logger, "run: leaving...");
    }
private:
    static joynr_logging::Logger* logger;
};
joynr_logging::Logger* TestRunnable::logger = joynr_logging::Logging::getInstance()->getLogger("MSG", "TestRunnable");

TEST(SingleThreadedDelayedSchedulerTest, schedule_deletingRunnablesCorrectly) {
    SingleThreadedDelayedScheduler scheduler(QString("SingleThreadedDelayedScheduler"));
    TestRunnable* runnable = new TestRunnable();
    scheduler.schedule(runnable, 1);
    QThreadSleep::msleep(100);
}

TEST(ThreadPoolDelayedSchedulerTest, schedule_deletingRunnablesCorrectly) {
    QThreadPool threadPool;
    ThreadPoolDelayedScheduler scheduler(threadPool, QString("ThreadPoolDelayedScheduler"));
    TestRunnable* runnable = new TestRunnable();
    scheduler.schedule(runnable, 1);
    QThreadSleep::msleep(100);
}
