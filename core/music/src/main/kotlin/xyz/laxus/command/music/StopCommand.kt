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
package xyz.laxus.command.music

import xyz.laxus.command.CommandContext
import xyz.laxus.music.MusicManager

/**
 * @author Kaidan Gustave
 */
class StopCommand(manager: MusicManager): MusicCommand(manager) {
    override val name = "Stop"
    override val help = "Stops playing music in the server."
    override val defaultLevel get() = Level.ADMINISTRATOR

    override suspend fun execute(ctx: CommandContext) {
        if(!ctx.guild.isPlaying) return ctx.replyWarning {
            "I cannot execute this command because am not playing anything!"
        }

        manager.stop(ctx.guild)
        ctx.replySuccess("Stopped playing.")
    }
}