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
@file:Suppress("Unused")
package xyz.laxus.util

import com.sedmelluq.discord.lavaplayer.track.AudioTrackInfo
import net.dv8tion.jda.core.entities.*
import xyz.laxus.jda.util.filterMassMentions
import java.time.OffsetDateTime
import kotlin.math.roundToLong

fun noMatch(lookedFor: String, query: String): String = "Could not find any $lookedFor matching \"$query\"!"

fun List<User>.multipleUsers(argument: String): String = listOut("user", argument) { it.formattedName(true) }
fun List<Member>.multipleMembers(argument: String): String = listOut("member", argument) { it.user.formattedName(true) }
fun List<TextChannel>.multipleTextChannels(argument: String): String = listOut("text channel", argument) { it.asMention }
fun List<VoiceChannel>.multipleVoiceChannels(argument: String): String = listOut("voice channel", argument) { it.name }
fun List<Role>.multipleRoles(argument: String): String = listOut("role", argument) { it.name }

inline fun <reified T> List<T>.listOut(kind: String, argument: String, conversion: (T) -> String): String = buildString {
    append("Multiple ${kind}s found matching \"$argument\":\n")
    val s = this@listOut.size
    for(i in 0 until 4) {
        append("${this@listOut[i].let(conversion)}\n")
        if(i == 3 && s > 4)
            append("And ${s - 4} other $kind${if(s - 4 > 1) "s..." else "..."}")
        if(i + 1 == s)
            break
    }
}

fun User.formattedName(boldName: Boolean = false): String {
    return "${if(boldName) "**$name**" else name}#$discriminator"
}

val OffsetDateTime.readableFormat get() = "${dayOfWeek.niceName}, ${month.niceName} $dayOfMonth, $year"

val AudioTrackInfo.formattedInfo get() = "**${filterMassMentions(title)}** `[${formatTrackTime(length)}]`"

fun formatTrackTime(duration: Long): String {
    if(duration == Long.MAX_VALUE) return "LIVE"

    var seconds = (duration / 1000.0).roundToLong()
    val hours = seconds / (60 * 60)
    seconds %= (60L * 60L)
    val minutes = seconds / 60
    seconds %= 60
    return (if(hours > 0) "$hours:" else "") +
           "${if(minutes < 10) "0$minutes" else "$minutes"}:" +
           (if(seconds < 10) "0$seconds" else "$seconds")
}
