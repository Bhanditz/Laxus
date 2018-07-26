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
@file:Suppress("MemberVisibilityCanBePrivate", "Unused")
package xyz.laxus

import com.jagrosh.jagtag.JagTag
import com.jagrosh.jagtag.Parser
import io.ktor.client.HttpClient
import io.ktor.client.engine.config
import io.ktor.client.features.HttpPlainText
import io.ktor.client.features.json.JsonFeature
import io.ktor.client.request.HttpRequestPipeline
import io.ktor.client.request.get
import io.ktor.client.request.post
import io.ktor.client.response.HttpResponse
import io.ktor.http.ContentType
import io.ktor.http.contentType
import io.ktor.http.withCharset
import kotlinx.coroutines.experimental.*
import me.kgustave.json.JSObject
import me.kgustave.json.ktor.client.JSKtorSerializer
import me.kgustave.ktor.client.okhttp.OkHttp
import net.dv8tion.jda.core.JDA
import net.dv8tion.jda.core.OnlineStatus
import net.dv8tion.jda.core.Permission.MESSAGE_MANAGE
import net.dv8tion.jda.core.entities.ChannelType.TEXT
import net.dv8tion.jda.core.entities.Guild
import net.dv8tion.jda.core.entities.Message
import net.dv8tion.jda.core.events.Event
import net.dv8tion.jda.core.events.ReadyEvent
import net.dv8tion.jda.core.events.ShutdownEvent
import net.dv8tion.jda.core.events.guild.GuildJoinEvent
import net.dv8tion.jda.core.events.guild.GuildLeaveEvent
import net.dv8tion.jda.core.events.guild.member.GuildMemberJoinEvent
import net.dv8tion.jda.core.events.message.MessageReceivedEvent
import net.dv8tion.jda.core.events.message.guild.GuildMessageDeleteEvent
import org.slf4j.Logger
import org.slf4j.event.Level
import xyz.laxus.command.Command
import xyz.laxus.command.CommandContext
import xyz.laxus.command.CommandMap
import xyz.laxus.entities.TagErrorException
import xyz.laxus.entities.tagMethods
import xyz.laxus.jda.listeners.SuspendedListener
import xyz.laxus.jda.util.listeningTo
import xyz.laxus.logging.LogLevel
import xyz.laxus.logging.filters.NormalFilter
import xyz.laxus.util.collections.CaseInsensitiveHashMap
import xyz.laxus.util.collections.FixedSizeCache
import xyz.laxus.util.collections.concurrentHashMap
import xyz.laxus.util.commandArgs
import xyz.laxus.util.createLogger
import xyz.laxus.util.db.*
import xyz.laxus.util.ktor.*
import java.io.IOException
import java.time.OffsetDateTime
import java.time.OffsetDateTime.now
import java.time.temporal.ChronoUnit.SECONDS
import java.util.concurrent.TimeUnit.HOURS
import kotlin.collections.set
import kotlin.coroutines.experimental.coroutineContext

class Bot internal constructor(builder: Bot.Builder): SuspendedListener {
    companion object Log: Logger by createLogger(Bot::class)

    private val cooldowns = concurrentHashMap<String, OffsetDateTime>()
    private val uses = CaseInsensitiveHashMap<Int>()
    private val callCache = FixedSizeCache<Long, MutableSet<Message>>(builder.callCacheSize)
    private val dBotsKey = builder.dBotsKey
    private val dBotsListKey = builder.dBotsListKey
    private val reminders by lazy { ReminderManager(Laxus.JDA) }

    private val cycleContext = newSingleThreadContext("Cycle Context")
    private val botsListContext = newSingleThreadContext("BotLists Context")

    val test = builder.test
    val prefix = if(test) Laxus.TestPrefix else Laxus.Prefix
    val startTime: OffsetDateTime = now()
    val groups = builder.groups.sorted()
    val commands: Map<String, Command> = CommandMap(*groups.toTypedArray())
    val parser: Parser = JagTag.newDefaultBuilder().addMethods(tagMethods).build()
    val httpClient = HttpClient(OkHttp.config { okClientBuilder = Laxus.HttpClientBuilder }) {
        authorizationHeaders {
            resolver(host = "bots.discord.pw", authorization = dBotsKey)
            resolver(host = "discordbots.org", authorization = dBotsListKey)
        }

        logging {
            name("BotLists")
            send {
                level = Level.INFO
                mode = ClientCallLogging.Mode.SIMPLE
            }
            receive {
                level = Level.DEBUG
                mode = ClientCallLogging.Mode.DETAILED
            }
            error {
                level = Level.ERROR
                mode = ClientCallLogging.Mode.SIMPLE
            }
        }

        install(JsonFeature) {
            serializer = JSKtorSerializer(charset = Charsets.UTF_8)
        }

        install(HttpPlainText) {
            defaultCharset = Charsets.UTF_8
        }

        install("DefaultContentType") {
            requestPipeline.intercept(HttpRequestPipeline.Before) {
                context.contentType(ContentType.Application.Json.withCharset(Charsets.UTF_8))
            }
        }
    }

    val messageCacheSize get() = callCache.size

    var totalGuilds = 0L
        private set
    var mode = builder.mode
        set(value) {
            field = value
            NormalFilter.level = LogLevel.byLevel(field.level)
        }

    init { NormalFilter.level = LogLevel.byLevel(mode.level) }

    fun getRemainingCooldown(name: String): Int {
        cooldowns[name]?.let { cooldown ->
            val time = now().until(cooldown, SECONDS).toInt()
            if(time <= 0) cooldowns -= name else return time
        }
        return 0
    }

    fun applyCooldown(name: String, seconds: Int) {
        cooldowns[name] = now().plusSeconds(seconds.toLong())
    }

    fun cleanCooldowns() {
        val now = now()
        cooldowns.entries.filter { it.value.isBefore(now) }.forEach { cooldowns -= it.key }
    }

    fun getUses(command: Command): Int {
        return uses.computeIfAbsent(command.name) { 0 }
    }

    fun incrementUses(command: Command) {
        uses[command.name] = (uses[command.name] ?: 0) + 1
    }

    fun searchCommand(query: String): Command? {
        val splitQuery = query.split(commandArgs, 2)
        if(splitQuery.isEmpty())
            return null
        return commands[splitQuery[0]]?.findChild(if(splitQuery.size > 1) splitQuery[1] else "")
    }

    suspend fun updateReminderContext() {
        reminders.update()
    }

    override suspend fun onEvent(event: Event) {
        when(event) {
            is MessageReceivedEvent -> onMessageReceived(event)
            is ReadyEvent -> onReady(event)
            is GuildJoinEvent -> onGuildJoinEvent(event)
            is GuildLeaveEvent -> onGuildLeaveEvent(event)
            is GuildMemberJoinEvent -> onGuildMemberJoin(event) // Does not suspend!
            is GuildMessageDeleteEvent -> onGuildMessageDelete(event) // Does not suspend!
            is ShutdownEvent -> onShutdown(event) // Does not suspend!
        }
    }

    private suspend fun onMessageReceived(event: MessageReceivedEvent) {
        // Do not allow bots to trigger any sort of command
        if(event.author.isBot)
            return

        if(event.textChannel?.canTalk() == false)
            return

        val raw = event.message.contentRaw
        val guild = event.guild

        val parts = when {
            raw.startsWith(prefix, true) -> {
                raw.substring(prefix.length).trim().split(commandArgs, 2)
            }

            guild !== null -> {
                val prefixes = event.guild.prefixes

                if(prefixes.isEmpty())
                    return

                val prefix = prefixes.find { raw.startsWith(it, true) } ?: return
                raw.substring(prefix.length).trim().split(commandArgs, 2)
            }

            else -> return
        }

        val name = parts[0].toLowerCase()
        val args = if(parts.size == 2) parts[1] else ""
        if(mode.checkCall(event, this@Bot, name, args)) {
            val ctx = CommandContext(event, args, this@Bot, coroutineContext)
            commands[name]?.let { command ->
                Bot.Log.debug("Call to Command \"${command.fullname}\"")
                return command.run(ctx)
            }

            if(ctx.isGuild) {
                ctx.guild.getCustomCommand(name)?.let { customCommand ->
                    with(parser) {
                        clear()
                        put("user", event.author)
                        put("guild", event.guild)
                        put("channel", event.textChannel)
                        put("args", args)
                    }

                    val parsed = try {
                        parser.parse(customCommand)
                    } catch(e: TagErrorException) {
                        return ctx.replyError {
                            e.message ?: "Custom command \"$name\" could not be processed for an unknown reason!"
                        }
                    }

                    ctx.reply(parsed)
                }
            }
        }
    }

    private suspend fun onReady(event: ReadyEvent) {
        with(event.jda.presence) {
            status = OnlineStatus.ONLINE
            game = listeningTo("type ${prefix}help")
        }

        val si = event.jda.shardInfo
        Log.info("${si?.let { "[${it.shardId} / ${it.shardTotal - 1}]" } ?: "Laxus"} is Online!")

        val toLeave = event.jda.guilds.filter { !it.isGood }
        if(toLeave.isNotEmpty()) {
            toLeave.forEach { it.leave().queue() }
            Log.info("Left ${toLeave.size} bad guilds!")
        }

        // Clear Caches every hour
        if(si === null || si.shardId == 0) {
            launch(cycleContext) {
                while(isActive) {
                    cleanCooldowns()
                    delay(1, HOURS)
                }
            }.invokeOnCompletion {
                if(it is CancellationException) {
                    Log.debug("Cycle context encountered a cancellation: ${it.message}")
                }
            }
        }

        updateReminderContext()

        updateStats(event.jda)
    }

    private suspend fun onGuildJoinEvent(event: GuildJoinEvent) {
        if(event.guild.selfMember.joinDate.plusMinutes(10).isAfter(now())) {
            updateStats(event.jda)
        }
    }

    private suspend fun onGuildLeaveEvent(event: GuildLeaveEvent) {
        updateStats(event.jda)
    }

    private fun onGuildMemberJoin(event: GuildMemberJoinEvent) {
        val guild = event.guild
        // If there's no welcome then we just return.
        val welcome = guild.welcome ?: return
        // We can't even send messages to the channel so we return
        if(!welcome.first.canTalk()) return
        // We prevent possible spam by creating a cooldown key 'welcomes|U:<User ID>|G:<Guild ID>'
        val cooldownKey = "welcomes|U:${event.user.idLong}|G:${guild.idLong}"
        val remaining = getRemainingCooldown(cooldownKey)
        // Still on cooldown - we're done here
        if(remaining > 0) return
        val message = parser.clear()
            .put("guild", guild)
            .put("channel", welcome.first)
            .put("user", event.user)
            .parse(welcome.second)
        // Too long or empty means we can't send, so we just return because it'll break otherwise
        if(message.isEmpty() || message.length > 2000) return
        // Send Message
        welcome.first.sendMessage(message).queue()
        // Apply cooldown
        applyCooldown(cooldownKey, 100)
    }

    private fun onGuildMessageDelete(event: GuildMessageDeleteEvent) {
        val cached = synchronized(callCache) { callCache.remove(event.messageIdLong) } ?: return
        val channel = event.channel
        if(cached.size > 1 && event.guild.selfMember.hasPermission(channel, MESSAGE_MANAGE)) {
            Log.debug("Deleting messages linked to call with Message ID: ${event.messageIdLong}")
            channel.deleteMessages(cached).queue({}, {})
        } else if(cached.size < 3) { // Don't try to delete more than two
            Log.debug("Deleting messages linked to call with Message ID: ${event.messageIdLong}")
            cached.forEach { it.delete().queue({}, {}) }
        }
    }

    private fun onShutdown(event: ShutdownEvent) {
        val identifier = event.jda.shardInfo?.let { "Shard [${it.shardId} / ${it.shardTotal - 1}]" } ?: "JDA"
        Log.info("$identifier has shutdown.")
        groups.forEach { it.dispose() }
        val cancellation = CancellationException("JDA fired shutdown!")
        cycleContext.cancel(cancellation)
        cycleContext.close()
        botsListContext.cancel(cancellation)
        botsListContext.close()
        reminders.close(cancellation)
    }

    private suspend fun updateStats(jda: JDA) {
        val body = JSObject {
            "server_count" to jda.guildCache.size()
            jda.shardInfo?.let {
                "shard_id" to it.shardId
                "shard_count" to it.shardTotal
            }
        }

        dBotsKey?.let {
            // Run this as a child job
            val job = launch(botsListContext, start = CoroutineStart.LAZY) {
                // Send POST request to bots.discord.pw
                httpClient.post<HttpResponse>(
                    url = "https://bots.discord.pw/api/bots/${jda.selfUser.id}/stats"
                ) { this.body = body }.close()
            }

            job.invokeOnCompletion { e ->
                if(e is IOException) {
                    Log.error("Failed to send information to bots.discord.pw", e)
                }
                if(e is CancellationException) {
                    Log.debug("Request to discordbots.org was cancelled: ${e.message}")
                }
            }

            job.start()
        }

        dBotsListKey?.let {
            // Run this as a child job
            val job = launch(botsListContext) {
                // Send POST request to discordbots.org
                httpClient.post<HttpResponse>(
                    url = "https://discordbots.org/api/bots/${jda.selfUser.id}/stats"
                ) { this.body = body }.close()
            }

            job.invokeOnCompletion { e ->
                if(e is IOException) {
                    Log.error("Failed to send information to bots.discord.pw", e)
                }
                if(e is CancellationException) {
                    Log.debug("Request to discordbots.org was cancelled: ${e.message}")
                }
            }

            job.start()
        }

        // If we're not sharded there's no reason to send a GET request
        if(jda.shardInfo === null || dBotsKey === null) {
            this.totalGuilds = jda.guildCache.size()
            return
        }

        try {
            // Send GET request to bots.discord.pw
            val json = httpClient.get<JSObject>("https://bots.discord.pw/api/bots/${jda.selfUser.id}/stats")
            this.totalGuilds = json.array("stats").asSequence()
                .mapNotNull { it as? JSObject }
                .map { it.optLong("server_count") ?: 0L }.sum()
        } catch (t: Throwable) {
            Log.error("Failed to retrieve bot shard information from bots.discord.pw", t)
        }
    }

    private val Guild.isGood: Boolean get() {
        if(isBlacklisted)
            return false
        if(isJoinWhitelisted)
            return true
        return members.count { it.user.isBot } <= 30 || getMemberById(Laxus.DevId) !== null
    }

    internal fun linkCall(id: Long, message: Message) {
        if(!message.isFromType(TEXT)) return
        Log.debug("Linking response (ID: ${message.idLong}) to call message ID: $id")
        val messages = callCache[id] ?: HashSet<Message>().also {
            callCache[id]
        }
        messages.add(message)
        callCache.computeIfAbsent(id) { HashSet() }.add(message)
    }

    interface CallVerifier {
        fun checkCall(event: MessageReceivedEvent, bot: Bot, name: String, args: String): Boolean = true
    }

    internal class Builder {
        val groups = ArrayList<Command.Group>()
        var test = false
        var dBotsKey: String? = null
        var dBotsListKey: String? = null
        var callCacheSize = 300
        var mode = RunMode.SERVICE
    }
}

internal inline fun bot(build: Bot.Builder.() -> Unit): Bot = Bot(Bot.Builder().apply(build))
