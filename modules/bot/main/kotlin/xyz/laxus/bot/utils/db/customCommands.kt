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
package xyz.laxus.bot.utils.db

import net.dv8tion.jda.core.entities.Guild
import xyz.laxus.db.DBCustomCommands

val Guild.customCommands get() = DBCustomCommands.getCustomCommands(idLong)

fun Guild.hasCustomCommand(name: String): Boolean {
    return DBCustomCommands.hasCustomCommand(idLong, name)
}

fun Guild.setCustomCommand(name: String, content: String?) {
    if(content === null) {
        DBCustomCommands.removeCustomCommand(idLong, name)
    } else {
        DBCustomCommands.setCustomCommand(idLong, name, content)
    }
}

fun Guild.getCustomCommand(name: String): String? {
    return DBCustomCommands.getCustomCommand(idLong, name)
}
