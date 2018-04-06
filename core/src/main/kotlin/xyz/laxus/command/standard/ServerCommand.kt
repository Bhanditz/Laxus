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
package xyz.laxus.command.standard

import net.dv8tion.jda.core.Permission.*
import net.dv8tion.jda.core.entities.Member
import xyz.laxus.Laxus
import xyz.laxus.command.AutoCooldown
import xyz.laxus.command.Command
import xyz.laxus.command.CommandContext
import xyz.laxus.jda.menus.orderedMenu
import xyz.laxus.jda.menus.orderedMenuBuilder
import xyz.laxus.jda.menus.paginator
import xyz.laxus.jda.menus.paginatorBuilder
import xyz.laxus.jda.util.*
import xyz.laxus.util.db.*
import xyz.laxus.util.formattedName
import xyz.laxus.util.readableFormat
import java.util.Comparator
import kotlin.collections.ArrayList

/**
 * @author Kaidan Gustave
 */
class ServerCommand: Command(StandardGroup) {
    override val name = "Server"
    override val aliases = arrayOf("Guild")
    override val help = "Gets info on the server."
    override val guildOnly = true
    override val cooldown = 10
    override val cooldownScope = CooldownScope.USER_GUILD
    override val botPermissions = arrayOf(MESSAGE_EMBED_LINKS, MESSAGE_MANAGE)
    override val children = arrayOf(
        ServerJoinsCommand(),
        ServerOwnerCommand(),
        ServerSettingsCommand(),
        ServerStatsCommand()
    )

    private val builder = orderedMenuBuilder {
        useCancelButton { true }
        description { "Choose a field to get info on:" }
        timeout { delay { 20 } }
        allowTextInput { false }
        finalAction { it.delete().await() }
        waiter { Laxus.Waiter }
    }

    override suspend fun execute(ctx: CommandContext) {
        if(ctx.args.isNotEmpty()) {
            return ctx.replyError("No server info category matching \"${ctx.args}\" was found.")
        }
        builder.clearChoices()
        val menu = orderedMenu(builder) {
            for(child in children) {
                if(ctx.level.test(ctx)) {
                    choice(child.name) {
                        it.delete().await()
                        child.run(ctx)
                    }
                }
            }
            user      { ctx.author }
            color     { ctx.selfMember.color }
        }
        menu.displayIn(ctx.channel)
        ctx.invokeCooldown()
    }

    @AutoCooldown
    private inner class ServerJoinsCommand: Command(this@ServerCommand) {
        override val name = "Joins"
        override val help = "Gets a full list of the server's members in the order they joined."
        override val guildOnly = true
        override val cooldown = 10
        override val cooldownScope = CooldownScope.CHANNEL
        override val botPermissions = arrayOf(MESSAGE_EMBED_LINKS, MESSAGE_ADD_REACTION, MESSAGE_MANAGE)

        private val builder = paginatorBuilder {
            timeout          { delay { 20 } }
            showPageNumbers  { true }
            numberItems      { true }
            waitOnSinglePage { true }
            waiter           { Laxus.Waiter }
        }

        override suspend fun execute(ctx: CommandContext) {
            val joins = ArrayList(ctx.guild.members)
            joins.sortedWith(Comparator.comparing(Member::getJoinDate))
            val names = joins.map { it.user.formattedName(true) }

            builder.clearItems()
            val paginator = paginator(builder) {
                text        { _,_ -> "Joins for ${ctx.guild.name}" }
                items       { + names }
                finalAction { it.delete().await() }
                user        { ctx.author }
            }

            paginator.displayIn(ctx.channel)
        }
    }

    private inner class ServerOwnerCommand : Command(this@ServerCommand) {
        override val name = "Owner"
        override val help = "Gets info on the owner of this server."
        override val guildOnly = true

        override suspend fun execute(ctx: CommandContext) {
            val member = ctx.guild.owner
            val user = member.user
            val embed = InfoCommand.infoEmbed(ctx, user, member)
            ctx.reply(embed)
        }
    }

    private inner class ServerSettingsCommand : Command(this@ServerCommand) {
        override val name = "Settings"
        override val aliases = arrayOf("Config", "Configurations")
        override val help = "Gets info on this server's settings."
        override val guildOnly = true
        override val defaultLevel = Level.MODERATOR
        override val botPermissions = arrayOf(MESSAGE_EMBED_LINKS)

        override suspend fun execute(ctx: CommandContext) {
            val guild = ctx.guild
            ctx.reply(embed {
                author {
                    value = "Settings for ${guild.name} (ID: ${guild.id})"

                    icon = guild.iconUrl
                }
                color { ctx.selfMember.color }

                field("Prefixes", true) {
                    append("`${ctx.bot.prefix}`")
                    guild.prefixes.forEach {
                        append(", `$it`")
                    }
                }

                field("Moderator Role", true) {
                    append(guild.modRole?.name ?: "None")
                }

                field("Moderation Log", true) {
                    append(guild.modLog?.name ?: "None")
                }

                field("Muted Role", true) {
                    append(guild.mutedRole?.name ?: "None")
                }

                field("Cases", true) {
                    append("${guild.cases.size} Cases")
                }
            })
        }
    }

    private inner class ServerStatsCommand: Command(this@ServerCommand) {
        override val name = "Stats"
        override val aliases = arrayOf("Statistics")
        override val help = "Gets statistics on this server."
        override val guildOnly = true
        override val botPermissions = arrayOf(MESSAGE_EMBED_LINKS)

        override suspend fun execute(ctx: CommandContext) {
            val guild = ctx.guild
            ctx.reply(embed {
                title { "Stats for ${guild.name}" }
                guild.iconUrl?.let {
                    url { it }
                    thumbnail { it }
                }
                color { ctx.member.color }

                field {
                    val members = guild.members
                    name = "Members"
                    appendln("Total: ${members.size}")
                    if(guild.hasModRole) {
                        val modRole = guild.modRole
                        appendln("Moderators: ${members.filter { modRole in it.roles }.size}")
                    }
                    appendln("Administrators: ${members.filter { it.isAdmin }.size}")
                    appendln("Bots: ${members.filter { it.user.isBot }.size}")
                    this.inline = true
                }

                field {
                    val textChannels = guild.textChannels
                    val visible = textChannels.filter { ctx.member canView it }
                    name = "Text Channels"
                    appendln("Total: ${textChannels.size}")
                    appendln("Visible: ${visible.size}")
                    appendln("Hidden: ${textChannels.size - visible.size}")
                    this.inline = true
                }

                field {
                    val voiceChannels = ctx.guild.voiceChannels
                    val unlocked = voiceChannels.filter { ctx.member canJoin it }
                    name = "Voice Channels"
                    appendln("Total: ${voiceChannels.size}")
                    appendln("Unlocked: ${unlocked.size}")
                    appendln("Locked: ${voiceChannels.size - unlocked.size}")
                    this.inline = true
                }

                footer {
                    value = "Created ${ctx.guild.creationTime.readableFormat}"
                }
            })
        }
    }
}
