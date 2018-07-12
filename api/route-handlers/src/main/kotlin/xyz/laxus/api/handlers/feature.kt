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
@file:Suppress("UNUSED_PARAMETER", "unused", "MemberVisibilityCanBePrivate")
@file:JvmName("RouteHandlersFeature")
package xyz.laxus.api.handlers

import io.ktor.application.*
import io.ktor.auth.authenticate
import io.ktor.config.ApplicationConfig
import io.ktor.http.HttpMethod
import io.ktor.routing.*
import io.ktor.util.AttributeKey
import xyz.laxus.api.handlers.annotations.*
import xyz.laxus.api.handlers.internal.ConfiguredConstructorFunction
import xyz.laxus.api.handlers.internal.getClassList
import xyz.laxus.api.handlers.internal.reflect.isAccessibleViaReflection
import xyz.laxus.api.handlers.internal.reflect.runInvocationSafe
import xyz.laxus.api.handlers.internal.routing.PathFunction
import xyz.laxus.api.handlers.internal.routing.PathHandlerInfo
import xyz.laxus.api.handlers.internal.routing.PathLifeCycle
import xyz.laxus.api.ratelimits.rateLimit
import xyz.laxus.util.reflect.hasAnnotation
import java.lang.reflect.Modifier.isStatic
import kotlin.collections.Collection
import kotlin.collections.List
import kotlin.collections.Map
import kotlin.collections.MutableList
import kotlin.collections.all
import kotlin.collections.asSequence
import kotlin.collections.emptyList
import kotlin.collections.filter
import kotlin.collections.find
import kotlin.collections.firstOrNull
import kotlin.collections.forEach
import kotlin.collections.joinToString
import kotlin.collections.mapTo
import kotlin.collections.mutableListOf
import kotlin.collections.mutableMapOf
import kotlin.collections.plus
import kotlin.collections.plusAssign
import kotlin.collections.set
import kotlin.collections.singleOrNull
import kotlin.collections.toMap
import kotlin.reflect.KClass
import kotlin.reflect.KFunction
import kotlin.reflect.full.*

sealed class RouteHandlersConfigurable(internal val application: Application) {
    internal val config get() = application.environment.config

    @RouteHandlers.Dsl abstract fun register(handler: Any)
    @RouteHandlers.Dsl fun register(handler: KClass<*>) {
        register(handler.objectInstance ?: locateAndConstructInstance(handler, config))
    }

    internal companion object {
        private val factoryCache = mutableMapOf<KClass<*>, ConfiguredConstructorFunction<*>>()

        internal fun locateAndConstructInstance(klass: KClass<*>, config: ApplicationConfig, parentInst: Any? = null): Any {
            factoryCache[klass]?.construct(config, parentInst)?.let { return it }

            require(klass.isAccessibleViaReflection) {
                "Cannot construct instance of $klass because it's not accessible!"
            }

            // return immediately if it's an object
            klass.objectInstance?.let { return it }

            val (constructor, configuration) = klass.findAnnotation<Configuration>()?.let { a ->
                klass.primaryConstructor
                    ?.takeIf { it.isAccessibleViaReflection }
                    ?.let { it to a }
            } ?: klass.constructors.asSequence().mapNotNull { c ->
                c.findAnnotation<Configuration>()?.let { c to it }
            }.filter { it.first.isAccessibleViaReflection }.firstOrNull() ?: run {
                if(!klass.isInner) {
                    return klass.createInstance()
                }
                val nestedConst = requireNotNull(klass.constructors.singleOrNull { c ->
                    c.parameters.all { it.isOptional || it == c.instanceParameter }
                }) {
                    "Class should have a single no-arg constructor: $klass"
                }
                return nestedConst.call(parentInst)
            }

            val factory = ConfiguredConstructorFunction(constructor, configuration)
            factoryCache[klass] = factory
            return factory.construct(config, parentInst)
        }
    }
}

class RouteHandlers
private constructor(configuration: RouteHandlersConfig): RouteHandlersConfigurable(configuration.application) {
    private val lifecycles = mutableListOf<PathLifeCycle>()

    internal val handleMissingBody = configuration.handleMissing.body
    internal val handleMissingHeader = configuration.handleMissing.header
    internal val handleMissingParam = configuration.handleMissing.param
    internal val handleMissingQueryParam = configuration.handleMissing.queryParam

    init { configuration.handlers.forEach { register(it) } }

    @RouteHandlers.Dsl override fun register(handler: Any) = registerWithRoute(handler, handler::class)

    private fun registerWithRoute(
        handler: Any,
        klazz: KClass<*>,
        route: RoutePath = validateClass(klazz),
        ancestors: List<RoutePath> = emptyList(),
        rateLimited: RateLimited? = klazz.findAnnotation(),
        handlerInfo: MutableList<PathHandlerInfo> = mutableListOf(),
        firstPass: Boolean = true
    ) {
        require(route !in ancestors) { "Detected recursive route resolution!" }

        val prefix = ancestors.joinToString("/", prefix = "/") { it.path.removePrefix("/") }
        val eligible = (klazz.memberFunctions + klazz.memberExtensionFunctions).filterInvalid()
        val authenticated = klazz.findAnnotation<Authenticated>()
        val handles = eligible.entries.mapTo(mutableListOf()) { entry ->
            val methodAnnotation = checkNotNull(entry.key.annotationClass.findAnnotation() ?: entry.key as? Method)
            val method = HttpMethod.parse(methodAnnotation.value)
            val annotationClass = entry.key.annotationClass

            application.log.debug("Registering handle: $klazz - ${method.value}: ${entry.value.name}")

            val pathExtension = annotationClass.findAnnotation<PathExtension>()?.let {
                val annotationMethods = annotationClass.java.methods.filter {
                    !isStatic(it.modifiers) && it.returnType == String::class.java
                }
                annotationMethods.find {
                    return@find it.isAnnotationPresent(PathExtension::class.java)
                } ?: annotationMethods.firstOrNull()
            }

            val path = pathExtension?.invoke(entry.key) as String? ?: ""
            return@mapTo PathFunction(this, handler, method, entry.value, path, authenticated, rateLimited)
        }

        val info = PathHandlerInfo(prefix + route.path, PathLifeCycle(handler, klazz), handles)
        handlerInfo += info
        lifecycles += info.lifecycle

        findSubRoutes(klazz, handler).forEach {
            val subKlazz = it::class
            val subRoute = checkNotNull(subKlazz.findAnnotation<RoutePath>()) {
                "$subKlazz is not annotated with @RoutePath!"
            }
            registerWithRoute(
                handler = it,
                klazz = subKlazz,
                route = subRoute,
                ancestors = ancestors + route,
                rateLimited = subKlazz.findAnnotation(),
                handlerInfo = handlerInfo,
                firstPass = false
            )
        }

        if(firstPass) application.routing {
            handlerInfo.forEach { info ->
                info.functions.forEach { function ->
                    val path = "${info.path.removeSuffix("/")}/${function.path.removePrefix("/").removeSuffix("/")}"
                    application.log.debug("Registering $function")
                    route(path) {
                        function.rateLimited?.let {
                            rateLimit(it.limit, it.reset, it.unit) {
                                function.authenticated?.let {
                                    authenticate(it.name) {
                                        method(function.method) { handle(function.func) }
                                    }
                                    return@rateLimit
                                }
                                method(function.method) { handle(function.func) }
                                return@rateLimit
                            }
                        }
                        function.authenticated?.let {
                            authenticate(it.name) {
                                method(function.method) { handle(function.func) }
                            }
                            return@route
                        }
                        method(function.method) { handle(function.func) }
                        return@route
                    }
                }
            }
        }
    }

    private fun findSubRoutes(klazz: KClass<*>, handler: Any): List<Any> {
        require(klazz.isInstance(handler)) { "$handler is not an instance of $klazz" }

        val subRoutes = mutableListOf<Any>()

        // member properties
        for(prop in klazz.memberProperties) {
            if(prop.isAccessibleViaReflection && prop.hasAnnotation<SubPath>()) {
                subRoutes += runInvocationSafe { prop.call(handler) } ?: continue
            }
        }

        // nested classes
        for(subKlass in klazz.nestedClasses) {
            if(subKlass.hasAnnotation<SubPath>() || subKlass.hasAnnotation<RoutePath>()) {
                require(subKlass.isAccessibleViaReflection) { "Cannot access annotated nested class $subKlass!" }
                subRoutes += locateAndConstructInstance(subKlass, config, handler)
            }
        }

        return subRoutes
    }

    companion object Feature: ApplicationFeature<Application, RouteHandlersConfig, RouteHandlers> {
        override val key = AttributeKey<RouteHandlers>("RouteHandlers")

        override fun install(pipeline: Application, configure: RouteHandlersConfig.() -> Unit): RouteHandlers {
            val routeHandlers = RouteHandlers(RouteHandlersConfig(pipeline).apply(configure))

            // monitor for lifecycle events

            pipeline.environment.monitor.subscribe(ApplicationStarting) { application ->
                routeHandlers.lifecycles.forEach { info ->
                    info.initializers.forEach { it.run(application) }
                }
            }

            pipeline.environment.monitor.subscribe(ApplicationStopping) { application ->
                routeHandlers.lifecycles.forEach { info ->
                    info.destroyers.forEach { it.run(application) }
                }
            }

            return routeHandlers
        }

        private fun validateClass(klass: KClass<*>, sub: RoutePath? = null): RoutePath {
            require(!klass.isAbstract) { "Cannot process abstract class type: $klass" }
            require(!klass.isInner) { "Cannot process inner class type: $klass" }
            return sub ?: requireNotNull(klass.findAnnotation()) {
                "Could not find @RoutePath on class: $klass"
            }
        }

        private fun Collection<KFunction<*>>.filterInvalid(): Map<Annotation, KFunction<*>> {
            return this.asSequence().filter { f ->
                !f.isInline && f.typeParameters.isEmpty() &&
                !f.isExternal && f.isAccessibleViaReflection
            }.mapNotNull { f ->
                f.annotations.firstOrNull {
                    it.annotationClass.hasAnnotation<Method>() || it is Method
                }?.let { it to f }
            }.toMap()
        }
    }

    @DslMarker
    @MustBeDocumented
    @Retention(AnnotationRetention.SOURCE)
    @Target(AnnotationTarget.FUNCTION, AnnotationTarget.PROPERTY)
    internal annotation class Dsl
}

class RouteHandlersConfig
internal constructor(application: Application): RouteHandlersConfigurable(application) {
    internal val handlers = mutableListOf<Any>()
    internal val websocket = null as Any?
    internal val handleMissing = MissingConfig()

    init {
        config.propertyOrNull("ktor.application.routes")
            ?.getClassList(application.environment.classLoader)
            ?.asSequence()
            ?.map { requireNotNull(it) { "Cannot load class!" } }
            ?.forEach { register(it) }
    }

    @RouteHandlers.Dsl override fun register(handler: Any) {
        this.handlers += handler
    }

    @RouteHandlers.Dsl fun handleMissing(block: MissingConfig.() -> Unit) {
        this.handleMissing.block()
    }
}

class MissingConfig internal constructor() {
    internal var body: suspend ApplicationCall.() -> Nothing = { genericError("body") }
    internal var header: suspend ApplicationCall.(String) -> Nothing = { genericError("header") }
    internal var param: suspend ApplicationCall.() -> Nothing = { genericError("param") }
    internal var queryParam: suspend ApplicationCall.() -> Nothing = { genericError("query param") }

    @RouteHandlers.Dsl fun body(block: suspend ApplicationCall.() -> Nothing) {
        this.body = block
    }

    @RouteHandlers.Dsl fun header(block: suspend ApplicationCall.(String) -> Nothing) {
        this.header = block
    }

    @RouteHandlers.Dsl fun param(block: suspend ApplicationCall.() -> Nothing) {
        this.param = block
    }

    @RouteHandlers.Dsl fun queryParam(block: suspend ApplicationCall.() -> Nothing) {
        this.queryParam = block
    }

    private companion object {
        @JvmStatic private fun genericError(identifier: String): Nothing {
            throw IllegalArgumentException("Missing $identifier")
        }
    }
}

private fun wrap(route: Route, handle: PathFunction): Route {
    var target = route
    handle.authenticated?.let {
        target = target.authenticate(it.name) {}
    }
    handle.rateLimited?.let {
        target = target.rateLimit(it.limit, it.reset, it.unit) {}
    }
    return target
}

@RouteHandlers.Dsl inline fun <reified T> RouteHandlersConfigurable.register() = register(T::class)
@RouteHandlers.Dsl fun Application.routeHandlers(configure: RouteHandlersConfig.() -> Unit) = install(RouteHandlers, configure)
