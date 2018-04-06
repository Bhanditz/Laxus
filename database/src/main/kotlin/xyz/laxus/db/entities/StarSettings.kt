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
@file:Suppress("MemberVisibilityCanBePrivate", "UNCHECKED_CAST")
package xyz.laxus.db.entities

import xyz.laxus.db.DBStarSettings
import xyz.laxus.util.ignored
import xyz.laxus.util.hashAll
import java.sql.ResultSet

/**
 * @author Kaidan Gustave
 */
data class StarSettings(
    val guildId: Long,
    var channelId: Long,
    var threshold: Short = 5,
    var maxAge: Int = 72,
    val ignored: MutableSet<Long> = mutableSetOf()
) {
    constructor(res: ResultSet): this(
        guildId = res.getLong("GUILD_ID"),
        channelId = res.getLong("CHANNEL_ID"),
        threshold = res.getShort("THRESHOLD"),
        maxAge = res.getInt("MAX_AGE"),
        ignored = res.getString("IGNORED")?.split('|')?.mapNotNullTo(mutableSetOf()) {
            ignored(null) { it.toLong() }
        } ?: mutableSetOf()
    )

    fun update() {
        DBStarSettings.setSettings(this)
    }

    override fun hashCode(): Int = hashAll(guildId, channelId, threshold, maxAge, ignored)
    override fun equals(other: Any?): Boolean {
        if(this === other) return true
        if(javaClass != other?.javaClass) return false
        other as StarSettings
        if(guildId != other.guildId) return false
        if(channelId != other.channelId) return false
        if(threshold != other.threshold) return false
        if(maxAge != other.maxAge) return false
        return true
    }
}