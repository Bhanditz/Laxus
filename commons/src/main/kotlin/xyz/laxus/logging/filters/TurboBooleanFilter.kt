/*
 * Copyright 2018 Kaidan Gustave
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package xyz.laxus.logging.filters

import ch.qos.logback.classic.Level
import ch.qos.logback.classic.Logger
import ch.qos.logback.classic.turbo.TurboFilter
import ch.qos.logback.core.spi.FilterReply
import org.slf4j.Marker

/**
 * @author Kaidan Gustave
 */
abstract class TurboBooleanFilter: TurboFilter() {
    final override fun decide(
        marker: Marker?, logger: Logger, level: Level,
        format: String?, params: Array<out Any>?, t: Throwable?
    ): FilterReply {
        val decision = filter(marker, logger, level, format, params, t)
        return when(decision) {
            null -> FilterReply.NEUTRAL
            true -> FilterReply.ACCEPT
            false -> FilterReply.DENY
        }
    }

    protected abstract fun filter(
        marker: Marker?, logger: Logger, level: Level,
        format: String?, params: Array<out Any>?, t: Throwable?
    ): Boolean?
}