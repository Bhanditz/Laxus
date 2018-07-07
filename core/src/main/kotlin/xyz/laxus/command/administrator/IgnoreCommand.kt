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
package xyz.laxus.command.administrator

import xyz.laxus.command.*
import xyz.laxus.jda.util.findRoles
import xyz.laxus.jda.util.findTextChannels
import xyz.laxus.util.db.isIgnored
import xyz.laxus.util.multipleRoles
import xyz.laxus.util.multipleTextChannels
import xyz.laxus.util.noMatch

/**
 * @author Kaidan Gustave
 */
@ExperimentalCommand("Ignoring is an experimental feature.")
class IgnoreCommand: EmptyCommand(AdministratorGroup) {
    override val name = "Ignore"
    override val help = "Ignores a channel, user, or role from using the bot."
    override val children = arrayOf(
        IgnoreChannelCommand(),
        IgnoreRoleCommand()
    )

    @MustHaveArguments("Specify a channel to ignore.")
    private inner class IgnoreChannelCommand: Command(this@IgnoreCommand) {
        override val name = "Channel"
        override val arguments = "[Channel]"
        override val help = "Ignores a text channel from using the bot."

        override suspend fun execute(ctx: CommandContext) {
            val query = ctx.args
            val channels = ctx.guild.findTextChannels(query)
            val found = when {
                channels.isEmpty() -> return ctx.replyError(noMatch("text channels", query))
                channels.size > 1 -> return ctx.replyError(channels.multipleTextChannels(query))
                else -> channels[0]
            }

            if(found.isIgnored) {
                found.isIgnored = false
                ctx.replySuccess("Successfully un-ignored ${found.asMention}!")
            } else {
                found.isIgnored = true
                ctx.replySuccess("Successfully ignored ${found.asMention}!")
            }
        }
    }

    @MustHaveArguments("Specify a role to ignore.")
    private inner class IgnoreRoleCommand: Command(this@IgnoreCommand) {
        override val name = "Role"
        override val arguments = "[Role]"
        override val help = "Ignores a role from using the bot."

        override suspend fun execute(ctx: CommandContext) {
            val query = ctx.args
            val roles = ctx.guild.findRoles(query)
            val found = when {
                roles.isEmpty() -> return ctx.replyError(noMatch("roles", query))
                roles.size > 1 -> return ctx.replyError(roles.multipleRoles(query))
                else -> roles[0]
            }

            if(found.isIgnored) {
                found.isIgnored = false
                ctx.replySuccess("Successfully un-ignored **${found.name}**!")
            } else {
                found.isIgnored = true
                ctx.replySuccess("Successfully ignored **${found.name}**!")
            }
        }
    }
}