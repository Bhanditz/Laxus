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
@file:Suppress("unused")

package xyz.laxus.music

import com.sedmelluq.discord.lavaplayer.player.AudioPlayerManager
import com.sedmelluq.discord.lavaplayer.player.DefaultAudioPlayerManager
import com.sedmelluq.discord.lavaplayer.player.event.*
import com.sedmelluq.discord.lavaplayer.source.soundcloud.SoundCloudAudioSourceManager as SoundCloud
import com.sedmelluq.discord.lavaplayer.source.youtube.YoutubeAudioSourceManager as YouTube
import com.sedmelluq.discord.lavaplayer.track.AudioTrack
import com.sedmelluq.discord.lavaplayer.track.AudioTrackEndReason.*
import kotlinx.coroutines.experimental.newSingleThreadContext
import net.dv8tion.jda.core.entities.Guild
import net.dv8tion.jda.core.entities.Member
import net.dv8tion.jda.core.entities.VoiceChannel
import net.dv8tion.jda.core.events.Event
import net.dv8tion.jda.core.events.ShutdownEvent
import net.dv8tion.jda.core.events.guild.GuildLeaveEvent
import net.dv8tion.jda.core.events.guild.voice.GenericGuildVoiceEvent
import net.dv8tion.jda.core.events.guild.voice.GuildVoiceLeaveEvent
import xyz.laxus.music.lava.userData
import xyz.laxus.util.createLogger
import xyz.laxus.util.formatTrackTime
import java.util.concurrent.ConcurrentHashMap

/**
 * @author Kaidan Gustave
 */
class MusicManager : IMusicManager<MusicManager, MusicQueue>,
    AudioPlayerManager by DefaultAudioPlayerManager() {

    internal companion object {
        internal val LOG = createLogger(MusicManager::class)

        private fun logTrackInfo(track: AudioTrack): String {
            return "Title: ${track.info.title} | Length: ${formatTrackTime(track.duration)} | State: ${track.state}"
        }
    }

    private val queueMap = ConcurrentHashMap<Long, MusicQueue>()
    internal val context by lazy { newSingleThreadContext("AudioCloseContext") }

    init {
        registerSourceManager(YouTube())
        registerSourceManager(SoundCloud())
    }

    override operator fun get(guild: Guild): MusicQueue? = queueMap[guild.idLong]
    override operator fun contains(guild: Guild): Boolean = this[guild] !== null

    override fun stop(guild: Guild) {
        val musicQueue = queueMap[guild.idLong] ?: return
        musicQueue.close()
        queueMap.remove(musicQueue.channel.guild.idLong)
    }

    override fun addTrack(channel: VoiceChannel, track: AudioTrack): Int {
        if(channel.guild !in this) {
            setupPlayer(channel, track)
            return 0
        } else {
            val queue = queueMap[channel.guild.idLong] ?: return -1
            return queue.queue(track)
        }
    }

    override fun addTracks(channel: VoiceChannel, tracks: List<AudioTrack>) {
        if(channel.guild !in this) {
            setupPlayer(channel, tracks[0])
        }

        val queue = this[channel.guild] ?: return
        for(i in 1 until tracks.size) {
            queue.queue(tracks[i])
        }
    }

    override fun onEvent(event: Event) {
        when(event) {
        // Dispose on shutdown
            is ShutdownEvent -> {
                queueMap.forEach { _, u -> u.close() }
                context.close()
                shutdown()
            }

        // Dispose if we leave a guild for whatever reason
            is GuildLeaveEvent -> removeGuild(event.guild.idLong)

        // Dispose if certain events are fired
            is GuildVoiceLeaveEvent -> if(event.isSelf) removeGuild(event.guild.idLong)
        }
    }

    override fun onEvent(event: AudioEvent) {
        when(event) {
            is TrackStartEvent -> {
                LOG.debug("Track Started | Title: ${event.track.info.title}")
            }
            is TrackEndEvent -> {
                when(event.endReason) {
                    null        -> return
                    FINISHED    -> onTrackFinished(event)
                    LOAD_FAILED -> LOG.debug("Track Load Failed | ${logTrackInfo(event.track)}")
                    STOPPED     -> LOG.debug("Track Stopped | ${logTrackInfo(event.track)}")
                    REPLACED    -> LOG.debug("Track Replaced | ${logTrackInfo(event.track)}")
                    CLEANUP     -> LOG.debug("Track Cleanup | ${logTrackInfo(event.track)}")
                }
            }
            is TrackExceptionEvent -> {
                LOG.error("Track Exception | ${logTrackInfo(event.track)}", event.exception)
            }
            is TrackStuckEvent -> {
                LOG.debug("Track Stuck | ${logTrackInfo(event.track)} | ${event.thresholdMs}ms")
            }
        }
    }

    private fun setupPlayer(voiceChannel: VoiceChannel, firstTrack: AudioTrack) {
        require(voiceChannel.guild !in this) {
            "Attempted to join a VoiceChannel on a Guild already being handled!"
        }
        val player = createPlayer()
        player.addListener(this)
        queueMap[voiceChannel.guild.idLong] = MusicQueue(this, voiceChannel, player, firstTrack)
    }

    private fun removeGuild(guildId: Long) {
        if(guildId in queueMap) {
            queueMap.remove(guildId)?.close()
        }
    }

    private fun onTrackFinished(event: TrackEndEvent) {
        LOG.debug("Track Finished | ${logTrackInfo(event.track)}")

        val member = event.track?.userData<Member>() ?: return
        val guildQueue = queueMap[member.guild.idLong] ?: return

        guildQueue.poll()
        if(guildQueue.isDead) {
            queueMap.remove(guildQueue.channel.guild.idLong)
        }
    }

    private val GenericGuildVoiceEvent.isSelf get() = member == guild.selfMember
}
