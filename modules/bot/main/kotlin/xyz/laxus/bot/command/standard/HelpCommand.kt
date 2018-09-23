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
package xyz.laxus.bot.command.standard

import net.dv8tion.jda.core.entities.User
import xyz.laxus.bot.Laxus
import xyz.laxus.bot.command.Command
import xyz.laxus.bot.command.CommandContext
import xyz.laxus.bot.utils.jda.await
import xyz.laxus.bot.utils.jda.boldFormattedName

/**
 * @author Kaidan Gustave
 */
class HelpCommand: Command(StandardGroup) {
    override val name = "Help"
    override val help = "Gets a list of all commands."
    override val guildOnly = false
    override val hasAdjustableLevel = false

    override suspend fun execute(ctx: CommandContext) {
        val prefix = ctx.bot.prefix
        val message = buildString {
            // Header
            appendln("**Available Commands in ${if(ctx.isGuild) ctx.textChannel.asMention else "Direct Messages"}**")

            // For each group
            ctx.bot.groups.filter { !it.unlisted && it.check(ctx) }.sorted().forEach g@ { g ->
                // They can't use the command group so we don't display it here
                if(!g.check(ctx))
                    return@g

                // Which commands are even available?
                val available = g.commands.filter { !it.unlisted && (ctx.level.test(ctx)) }

                // None, we skip this group
                if(available.isEmpty())
                    return@g

                // Group header
                appendln()
                appendln("__${g.name} Commands__")
                appendln()

                // For each available command
                available.forEach c@ { c ->
                    append("`").append(prefix).append(c.name)
                    val arguments = c.arguments
                    if(arguments.isNotBlank()) {
                        append(" $arguments")
                    }
                    append("` - ${c.help}")
                    appendln()
                }
            }

            val owner = runCatching<User> { ctx.jda.retrieveUserById(Laxus.DevId).await() }.getOrNull()

            // Additional help
            appendln()
            append("For additional help ")
            if(owner !== null) {
                append("contact ${owner.boldFormattedName} or ")
            }
            append("join my support server: **<${Laxus.ServerInvite}>**")
        }

        if(ctx.isGuild) {
            ctx.reactSuccess()
        }
        ctx.replyInDM(message)
    }
}
