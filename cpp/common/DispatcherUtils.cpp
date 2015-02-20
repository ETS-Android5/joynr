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
#include "joynr/DeclareMetatypeUtil.h"
#include "joynr/DispatcherUtils.h"

#include <QDateTime>
#include "qjson/parser.h"
#include "qjson/serializer.h"
#include <limits>
#include <cassert>

namespace joynr
{

using namespace joynr_logging;

Logger* DispatcherUtils::logger = Logging::getInstance()->getLogger("MSG", "DispatcherUtils");

DispatcherUtils::DispatcherUtils()
{
}

// Dispatcher Utils

QDateTime DispatcherUtils::convertTtlToAbsoluteTime(qint64 ttl_ms)
{
    QDateTime now = QDateTime::currentDateTimeUtc();
    QDateTime expiryDate = now.addMSecs(ttl_ms);

    // check for overflow
    if (ttl_ms > 0) {
        bool positiveOverflow = expiryDate.toMSecsSinceEpoch() < now.toMSecsSinceEpoch();
        if (positiveOverflow) {
            return getMaxAbsoluteTime();
        }
    } else if (ttl_ms < 0) {
        bool negativeOverflow = expiryDate.toMSecsSinceEpoch() > now.toMSecsSinceEpoch();
        if (negativeOverflow) {
            return QDateTime::fromMSecsSinceEpoch(std::numeric_limits<qint64>::min(), Qt::UTC);
        }
    }

    return expiryDate;
}

QDateTime DispatcherUtils::getMaxAbsoluteTime()
{
    return QDateTime::fromMSecsSinceEpoch(std::numeric_limits<qint64>::max(), Qt::UTC);
}

qint64 DispatcherUtils::convertAbsoluteTimeToTtl(const QDateTime& date)
{
    // todo optimise using appropriate operator from QDateTime
    return date.toMSecsSinceEpoch() - QDateTime::currentMSecsSinceEpoch();
}

QString DispatcherUtils::convertAbsoluteTimeToTtlString(const QDateTime& date)
{
    return QString::number(convertAbsoluteTimeToTtl(date));
}

} // namespace joynr
