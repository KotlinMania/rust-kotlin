package io.github.kotlinmania.rustkotlin

import kotlin.time.TimeSource

/**
 * Policy governing how bare `cfg(<flag>)` identifiers and unconfigured `cfg(key = "value")`
 * predicates behave when not explicitly pinned in [CfgTargetProfile].
 */
public enum class UnknownCfgPolicy {
    /**
     * Open-world library default: unknown predicates return tri-state `null` during
     * `#[cfg(...)]` item/field pruning (preserving feature-gated items while allowing
     * complementary `#[cfg(X)]` / `#[cfg(not(X))]` deduplication) and `false` in
     * runtime `cfg!(...)` boolean expressions.
     */
    OPEN_WORLD_NULL,

    /**
     * Closed-world concrete target semantics (matching `rustc` without `--cfg <flag>`):
     * any bare flag or feature not explicitly enabled in [CfgTargetProfile] evaluates
     * to `false`.
     */
    CLOSED_WORLD_FALSE,

    /**
     * Permissive evaluation: unknown bare flags evaluate to `true`.
     */
    PERMISSIVE_TRUE,
}

/**
 * Structured target/features configuration used by `#[cfg(...)]` attribute pruning
 * and `cfg!(...)` boolean expression lowering.
 */
public data class CfgTargetProfile(
    public val pointerWidth: Int = 64,
    public val targetEndian: String = "little",
    public val targetArch: String? = null,
    public val targetOs: String? = null,
    public val targetEnv: String? = null,
    public val targetVendor: String? = null,
    public val targetFamilies: Set<String>? = null,
    public val enabledFeatures: Set<String>? = null,
    public val enabledCfgFlags: Set<String> = emptySet(),
    public val disabledCfgFlags: Set<String> = setOf("doc", "docsrs", "miri", "kani"),
    public val unknownPolicy: UnknownCfgPolicy = UnknownCfgPolicy.OPEN_WORLD_NULL,
    public val useRelativeDirPlatformHints: Boolean = false,
)

/** Process-local timing and active translation context for a single invocation. */
internal object TranslationSession {
    internal var sourceLayoutFile: io.github.kotlinmania.syn.File? = null
    internal var allowStdlibDestructors: Boolean = false

    var timings = false
    var rawAbiMode = false
    var finalStructs = false
    var splitModuleRoots = false
    var currentRelativeSourceDir: String? = null
    var cfgTargetProfile: CfgTargetProfile = CfgTargetProfile()
    var crateTypeAliasDefaults: Map<String, String> = emptyMap()
    var crateOptionFnTypes: Set<String> = emptySet()
    var crateStructFieldTypes: Map<String, String> = emptyMap()
    var crateFnReturnTypes: Map<String, String> = emptyMap()
    var sharedModuleFile: io.github.kotlinmania.syn.File? = null

    fun clear() {
        sourceLayoutFile = null
        allowStdlibDestructors = false
        timings = false
        rawAbiMode = false
        finalStructs = false
        splitModuleRoots = false
        currentRelativeSourceDir = null
        cfgTargetProfile = CfgTargetProfile()
        crateTypeAliasDefaults = emptyMap()
        crateOptionFnTypes = emptySet()
        crateStructFieldTypes = emptyMap()
        crateFnReturnTypes = emptyMap()
        sharedModuleFile = null
    }

    fun <T> measure(stage: String, action: () -> T): T {
        if (!timings) return action()
        val start = TimeSource.Monotonic.markNow()
        try {
            return action()
        } finally {
            println("timing\t$stage\t${start.elapsedNow().inWholeMicroseconds}us")
        }
    }
}
