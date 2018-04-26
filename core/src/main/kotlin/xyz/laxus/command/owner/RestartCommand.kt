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
package xyz.laxus.command.owner

import net.dv8tion.jda.core.OnlineStatus.DO_NOT_DISTURB
import xyz.laxus.Laxus
import xyz.laxus.command.Command
import xyz.laxus.command.CommandContext
import xyz.laxus.jda.util.watching

/**
 * @author Kaidan Gustave
 */
class RestartCommand: Command(OwnerGroup) {
    override val name = "Restart"
    override val help = "Restarts Laxus."
    override val hasAdjustableLevel = false

    override suspend fun execute(ctx: CommandContext) {
        Laxus.Log.info("Restarting...")
        ctx.jda.presence.setPresence(DO_NOT_DISTURB, watching("Everything restart..."))

        // Await to prevent shutting down while replying
        ctx.sendWarning("Restarting...")
        ctx.jda.shutdownNow()
    }
}