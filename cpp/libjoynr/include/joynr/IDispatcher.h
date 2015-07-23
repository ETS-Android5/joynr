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
#ifndef DISPATCHER_H
#define DISPATCHER_H

#include <QSharedPointer>
#include <string>

namespace joynr
{

class ISubscriptionManager;
class PublicationManager;
class IReplyCaller;
class MessagingQos;
class RequestCaller;
class JoynrMessage;

class IDispatcher
{
public:
    virtual ~IDispatcher()
    {
    }
    virtual void addReplyCaller(const std::string& requestReplyId,
                                QSharedPointer<IReplyCaller> replyCaller,
                                const MessagingQos& qosSettings) = 0;
    virtual void removeReplyCaller(const std::string& requestReplyId) = 0;
    virtual void addRequestCaller(const std::string& participantId,
                                  QSharedPointer<RequestCaller> requestCaller) = 0;
    virtual void removeRequestCaller(const std::string& participantId) = 0;
    virtual void receive(const JoynrMessage& message) = 0;

    virtual void registerSubscriptionManager(ISubscriptionManager* subscriptionManager) = 0;
    virtual void registerPublicationManager(PublicationManager* publicationManager) = 0;
};

} // namespace joynr
#endif // DISPATCHER_H
