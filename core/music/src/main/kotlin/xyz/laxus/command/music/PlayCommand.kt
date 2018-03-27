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

import com.sedmelluq.discord.lavaplayer.tools.FriendlyException
import com.sedmelluq.discord.lavaplayer.tools.FriendlyException.Severity.COMMON
import com.sedmelluq.discord.lavaplayer.track.AudioPlaylist
import com.sedmelluq.discord.lavaplayer.track.AudioTrack
import kotlinx.coroutines.experimental.async
import net.dv8tion.jda.core.Permission
import xyz.laxus.Laxus
import xyz.laxus.command.CommandContext
import xyz.laxus.command.MustHaveArguments
import xyz.laxus.jda.util.editMessage
import xyz.laxus.music.MusicManager
import xyz.laxus.util.formattedInfo
import xyz.laxus.util.noMatch

/**
 * @author Kaidan Gustave
 */
@MustHaveArguments("Specify a song name, or URL link.")
class PlayCommand(manager: MusicManager): MusicCommand(manager) {
    override val name = "Play"
    override val arguments = "[Song|URL]"
    override val botPermissions = arrayOf(
        Permission.VOICE_CONNECT,
        Permission.VOICE_SPEAK
    )

    override suspend fun execute(ctx: CommandContext) {
        val guild = ctx.guild
        val member = ctx.member
        val query = ctx.args
        val voiceChannel = ctx.voiceChannel

        if(!member.inPlayingChannel || voiceChannel === null) {
            if(guild.isPlaying) {
                return ctx.notInPlayingChannel()
            }
            if(voiceChannel === null) {
                return ctx.notInVoiceChannel()
            }
        }

        val loading = async(ctx) {
            ctx.send("Loading...")
        }

        val item = try {
            loadTrack(member, query)
        } catch(e: FriendlyException) {
            val message = loading.await()
            return when(e.severity) {
                COMMON -> message.editMessage("An error occurred${e.message?.let { ": $it" } ?: ""}.").queue()
                else -> message.editMessage("An error occurred.").queue()
            }
        }

        when(item) {
            null -> return ctx.replyWarning(noMatch("results", query))

            is AudioTrack -> {
                item.userData = member
                val info = item.info.formattedInfo
                val position = manager.addTrack(voiceChannel, item)
                loading.await().editMessage {
                    append(Laxus.Success)
                    if(position < 1) {
                        append(" Now playing $info.")
                    } else {
                        append(" Added $info at position $position in the queue.")
                    }
                }.queue()
            }

            is AudioPlaylist -> {
                val tracks = item.tracks.onEach { it.userData = member }
                manager.addTracks(voiceChannel, tracks)
                loading.await().editMessage {
                    append(Laxus.Success)
                    if(manager[ctx.guild]!!.size + 1 == tracks.size) {
                        append(" Now playing `${tracks.size}` tracks from playlist **${item.name}**.")
                    } else {
                        append(" Added `${tracks.size}` tracks from **${item.name}**.")
                    }
                }.queue()
            }

            // This shouldn't happen, but...
            else -> loading.await().editMessage("The loaded item is unsupported by this player.").queue()
        }
    }
}