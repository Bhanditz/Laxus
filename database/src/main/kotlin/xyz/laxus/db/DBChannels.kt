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
@file:Suppress("MemberVisibilityCanBePrivate", "unused")
package xyz.laxus.db

import xyz.laxus.db.schema.*
import xyz.laxus.db.sql.*
import xyz.laxus.db.sql.ResultSetConcur.*
import xyz.laxus.db.sql.ResultSetType.*

/**
 * @author Kaidan Gustave
 */
@TableName("GUILD_CHANNELS") @Columns(
    Column("GUILD_ID", BIGINT, unique = true),
    Column("CHANNEL_ID", BIGINT, unique = true),
    Column("TYPE", "$VARCHAR(50)", unique = true)
)
object DBChannels : Table() {
    private const val GET_CHANNELS = "SELECT CHANNEL_ID FROM GUILD_CHANNELS WHERE GUILD_ID = ? AND TYPE = ?"
    private const val SET_ADD_CHANNELS = "SELECT * FROM GUILD_CHANNELS WHERE GUILD_ID = ? AND TYPE = ?"
    private const val REMOVE_CHANNEL = "SELECT * FROM GUILD_CHANNELS WHERE GUILD_ID = ? AND CHANNEL_ID = ? AND TYPE = ?"

    fun isChannel(guildId: Long, type: Type): Boolean {
        return connection.prepare(SET_ADD_CHANNELS) { statement ->
            statement[1] = guildId
            statement[2] = type.name
            statement.executeQuery { it.next() }
        }
    }

    fun getChannel(guildId: Long, type: Type): Long? {
        connection.prepare(GET_CHANNELS) { statement ->
            statement[1] = guildId
            statement[2] = type.name
            statement.executeQuery {
                if(it.next()) {
                    return it.getLong("CHANNEL_ID")
                }
            }
        }
        return null
    }

    fun getChannels(guildId: Long, type: Type): List<Long> {
        val channels = ArrayList<Long>()
        connection.prepare(GET_CHANNELS) { statement ->
            statement[1] = guildId
            statement[2] = type.name
            statement.executeQuery {
                it.whileNext { channels += it.getLong("CHANNEL_ID") }
            }
        }
        return channels
    }

    fun setChannel(guildId: Long, channelId: Long, type: Type) {
        connection.prepare(SET_ADD_CHANNELS, SCROLL_INSENSITIVE, UPDATABLE) { statement ->
            statement[1] = guildId
            statement[2] = type.name
            statement.executeQuery {
                if(!it.next()) it.insert {
                    it["GUILD_ID"] = guildId
                    it["CHANNEL_ID"] = channelId
                    it["TYPE"] = type.name
                } else {
                    it["GUILD_ID"] = guildId
                    it["CHANNEL_ID"] = channelId
                    it["TYPE"] = type.name
                }
            }
        }
    }

    fun addChannel(guildId: Long, channelId: Long, type: Type) {
        connection.prepare(REMOVE_CHANNEL, SCROLL_INSENSITIVE, UPDATABLE) { statement ->
            statement[1] = guildId
            statement[2] = channelId
            statement[3] = type.name
            statement.executeQuery {
                if(!it.next()) it.insert {
                    it["GUILD_ID"] = guildId
                    it["CHANNEL_ID"] = channelId
                    it["TYPE"] = type.name
                }
            }
        }
    }

    fun removeChannel(guildId: Long, type: Type) {
        removeChannels(guildId, type)
    }

    fun removeChannel(guildId: Long, channelId: Long, type: Type) {
        connection.prepare(REMOVE_CHANNEL, SCROLL_INSENSITIVE, UPDATABLE) { statement ->
            statement[1] = guildId
            statement[2] = channelId
            statement[3] = type.name
            statement.executeQuery {
                if(it.next()) it.deleteRow()
            }
        }
    }

    fun removeChannels(guildId: Long, type: Type) {
        connection.prepare(GET_CHANNELS, SCROLL_INSENSITIVE, UPDATABLE) { statement ->
            statement[1] = guildId
            statement[2] = type.name
            statement.executeQuery {
                it.whileNext { it.deleteRow() }
            }
        }
    }

    fun removeAllChannels(guildId: Long) {
        for(type in Type.values()) {
            removeChannels(guildId, type)
        }
    }

    enum class Type {
        IGNORED,
        MOD_LOG,
        WELCOME
    }
}