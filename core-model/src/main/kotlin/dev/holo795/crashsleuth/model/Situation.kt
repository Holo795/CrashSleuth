package dev.holo795.crashsleuth.model

import kotlinx.serialization.Serializable

/**
 * Catalogue of the problems CrashSleuth knows how to recognise. Identifiers are stable: signatures,
 * reports and translations refer to them.
 */
@Serializable
enum class Situation(val stage: Stage) {
    DEP_MISSING(Stage.STARTUP),
    DEP_VERSION(Stage.STARTUP),
    DEP_CYCLE(Stage.STARTUP),
    WRONG_LOADER(Stage.STARTUP),
    WRONG_MC(Stage.STARTUP),
    DUPLICATE(Stage.STARTUP),
    JAVA_VERSION(Stage.STARTUP),
    CLIENT_ONLY_ON_SERVER(Stage.STARTUP),
    MIXIN_CONFLICT(Stage.STARTUP),
    PLUGIN_API(Stage.STARTUP),
    CORRUPT_JAR(Stage.STARTUP),
    CONFIG_BROKEN(Stage.STARTUP),
    DATAPACK_BROKEN(Stage.STARTUP),
    REGISTRY_MISMATCH(Stage.WORLD),
    MOD_MISMATCH(Stage.WORLD),
    CORRUPT_CHUNK(Stage.WORLD),
    CORRUPT_ENTITY(Stage.WORLD),
    CORRUPT_PLAYERDATA(Stage.WORLD),
    TICK_ENTITY(Stage.GAME),
    TICK_BLOCK_ENTITY(Stage.GAME),
    OUT_OF_MEMORY(Stage.GAME),
    STACK_OVERFLOW(Stage.GAME),
    RENDER(Stage.GAME),
    NATIVE_CRASH(Stage.GAME),
    HANG(Stage.NO_CRASH),
    DEADLOCK(Stage.NO_CRASH),
    LAG(Stage.NO_CRASH),
    LOG_SPAM(Stage.NO_CRASH),
    SILENT_ERROR(Stage.NO_CRASH),
    UNCAUGHT_EXCEPTION(Stage.GAME),
}

@Serializable
enum class Stage { STARTUP, WORLD, GAME, NO_CRASH }
