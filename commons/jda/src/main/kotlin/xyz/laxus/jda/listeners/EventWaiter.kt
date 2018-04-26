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
package xyz.laxus.jda.listeners

import kotlinx.coroutines.experimental.*
import net.dv8tion.jda.core.events.Event
import net.dv8tion.jda.core.events.ShutdownEvent
import net.dv8tion.jda.core.hooks.EventListener
import org.slf4j.Logger
import xyz.laxus.util.collections.concurrentHashMap
import xyz.laxus.util.collections.concurrentSet
import xyz.laxus.util.createLogger
import xyz.laxus.util.ignored
import java.util.concurrent.TimeUnit
import kotlin.coroutines.experimental.CoroutineContext
import kotlin.reflect.KClass
import kotlin.reflect.full.allSuperclasses

/**
 * @author Kaidan Gustave
 */
class EventWaiter private constructor(dispatcher: ThreadPoolDispatcher):
    EventListener,
    CoroutineContext by dispatcher,
    AutoCloseable by dispatcher {
    private companion object Log: Logger by createLogger(EventWaiter::class)
    private val tasks = concurrentHashMap<KClass<*>, MutableSet<ITask<*>>>()

    constructor(): this(newFixedThreadPoolContext(3, "EventWaiter"))

    inline fun <reified E: Event> waitFor(
        delay: Long = -1,
        unit: TimeUnit = TimeUnit.SECONDS,
        noinline timeout: (suspend () -> Unit)? = null,
        noinline condition: suspend (E) -> Boolean,
        noinline action: suspend (E) -> Unit
    ) = waitForEvent(E::class, condition, action, delay, unit, timeout)

    inline fun <reified E: Event> receive(
        delay: Long = -1,
        unit: TimeUnit = TimeUnit.SECONDS,
        noinline condition: suspend (E) -> Boolean
    ): Deferred<E?> = receiveEvent(E::class, condition, delay, unit)

    suspend inline fun <reified E: Event> delayUntil(
        delay: Long = -1,
        unit: TimeUnit = TimeUnit.SECONDS,
        noinline condition: suspend (E) -> Boolean
    ): Boolean = delayUntilEvent(E::class, condition, delay, unit)

    fun <E: Event> waitForEvent(
        klazz: KClass<E>,
        condition: suspend (E) -> Boolean,
        action: suspend (E) -> Unit,
        delay: Long = -1,
        unit: TimeUnit = TimeUnit.SECONDS,
        timeout: (suspend () -> Unit)? = null
    ) {
        val eventSet = taskSetType(klazz)
        val waiting = QueuedTask(condition, action)

        Log.debug("Adding task type: '$klazz'")
        eventSet += waiting

        if(delay > 0) {
            launch(this) {
                delay(delay, unit)
                if(eventSet.remove(waiting)) {
                    Log.debug("Removing task type: '$klazz'")
                    timeout?.invoke()
                }
            }
        }
    }

    fun <E: Event> receiveEvent(
        klazz: KClass<E>,
        condition: suspend (E) -> Boolean,
        delay: Long = -1,
        unit: TimeUnit = TimeUnit.SECONDS
    ): Deferred<E?> {
        val deferred = CompletableDeferred<E?>()
        val eventSet = taskSetType(klazz)
        val waiting = AwaitableTask(condition, deferred)

        Log.debug("Adding task type: '$klazz'")
        eventSet += waiting

        if(delay > 0) {
            launch(this) {
                delay(delay, unit)
                eventSet.remove(waiting)
                Log.debug("Removing task type: '$klazz'")
                // The receiveEvent method is supposed to return null
                //if no matching Events are fired within its
                //lifecycle.
                // Regardless of whether or not the AwaitableTask
                //was removed, we invoke this. In the event that
                //it has not completed, we need to make sure the
                //coroutine does not deadlock.
                deferred.complete(null)
            }
        }

        return deferred
    }

    suspend fun <E: Event> delayUntilEvent(
        klazz: KClass<E>,
        condition: suspend (E) -> Boolean,
        delay: Long = -1,
        unit: TimeUnit = TimeUnit.SECONDS
    ): Boolean = receiveEvent(klazz, condition, delay, unit).await() !== null

    override fun onEvent(event: Event) {
        if(event is ShutdownEvent) {
            return close()
        }

        launch(this) {
            val klazz = event::class
            dispatchEventType(event, klazz)
            klazz.allSuperclasses.forEach { dispatchEventType(event, it) }
        }
    }

    @Suppress("UNCHECKED_CAST")
    private fun <E: Event> taskSetType(klazz: KClass<E>): MutableSet<ITask<E>> {
        return tasks.computeIfAbsent(klazz) { concurrentSet() } as MutableSet<ITask<E>>
    }

    @Suppress("UNCHECKED_CAST")
    private suspend fun <T: Event> dispatchEventType(event: T, klazz: KClass<*>) {
        val set = tasks[klazz] ?: return
        val filtered = set.filterTo(hashSetOf()) {
            val waiting = (it as ITask<T>)
            waiting(event)
        }
        Log.debug("Removing ${filtered.size} tasks with type: '$klazz'")
        set -= filtered
    }

    private interface ITask<in T: Event> {
        suspend operator fun invoke(event: T): Boolean
    }

    private class QueuedTask<in T: Event>(
        private val condition: suspend (T) -> Boolean,
        private val action: suspend (T) -> Unit
    ): ITask<T> {
        override suspend fun invoke(event: T): Boolean {
            // Ignore exception, return false
            ignored {
                if(condition(event)) {
                    // Ignore exception, return true
                    ignored { action(event) }
                    return true
                }
            }
            return false
        }
    }

    private class AwaitableTask<in T: Event>(
        private val condition: suspend (T) -> Boolean,
        private val completion: CompletableDeferred<T?>
    ): ITask<T> {
        override suspend fun invoke(event: T): Boolean {
            try {
                if(condition(event)) {
                    completion.complete(event)
                    return true
                }
                return false
            } catch(t: Throwable) {
                // In the case this ever throws an error,
                // we need to complete this exceptionally.
                completion.completeExceptionally(t)
                return true
            }
        }
    }
}
