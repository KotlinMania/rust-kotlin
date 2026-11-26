package io.github.kotlinmania.rustkotlin

import io.github.kotlinmania.syn.Item
import io.github.kotlinmania.syn.ModContent
import io.github.kotlinmania.syn.UseTree
import io.github.kotlinmania.syn.Visibility

internal data class UseLeaf(
    val segments: List<String>,
    val alias: String?,
    val isSelfImport: Boolean,
)

private val EXTERNAL_REEXPORT_SEARCH_CRATES: List<String>
    get() = activeRules().stringList("externalReexportSearchCrates")

internal fun KotlinJ2KEmitter.normalizeNetcLibcSegments(segs: List<String>): List<String> =
    if (segs.firstOrNull()?.removePrefix("r#") == "netc" && FfiInteropCollector.resolveLibcNamespace(headerContext) != null) {
        listOf("libc") + segs.drop(1)
    } else {
        segs
    }

internal fun KotlinJ2KEmitter.isImportedTypeLeaf(lastSeg: String, boundRawAlias: String?, isSelfImport: Boolean): Boolean =
    UseItemImportEmitter(this).isImportedTypeLeaf(lastSeg, boundRawAlias, isSelfImport)

internal fun KotlinJ2KEmitter.isKnownFunctionLeafIdent(rawSeg: String): Boolean =
    UseItemImportEmitter(this).isKnownFunctionLeafIdent(rawSeg)

internal fun KotlinJ2KEmitter.isImportedScreamingConstLeaf(lastSeg: String, boundRawAlias: String?, isSelfImport: Boolean): Boolean =
    UseItemImportEmitter(this).isImportedScreamingConstLeaf(lastSeg, boundRawAlias, isSelfImport)

internal fun KotlinJ2KEmitter.registerUseItemInScope(item: Item.Use) {
    UseItemImportEmitter(this).registerInScope(item)
}

internal fun KotlinJ2KEmitter.currentNamespacePrefix(): String? =
    headerContext.projectNamespace
        ?.trim()
        ?.removeSuffix(".")
        ?.takeIf { it.isNotEmpty() }
        ?: headerContext.packageName
            ?.trim()
            ?.removeSuffix(".")
            ?.takeIf { it.isNotEmpty() && headerContext.portLintSourcePath?.contains('/') != true }
        ?: headerContext.projectGroup
            ?.trim()
            ?.removeSuffix(".")
            ?.takeIf { it.isNotEmpty() }

internal fun KotlinJ2KEmitter.currentSubpackageString(): String {
    val pkg = headerContext.packageName?.trim()?.removeSuffix(".") ?: return ""
    val ns = currentNamespacePrefix() ?: return ""
    return when {
        pkg == ns -> ""
        pkg.startsWith("$ns.") -> pkg.removePrefix("$ns.")
        else -> ""
    }
}

internal fun matchExternalSymbolPackage(
    depCrate: String,
    baseNs: String,
    modPath: String,
    candidates: List<String>,
    expandLibmMath: Boolean = true,
): String? {
    val modPaths =
        if (expandLibmMath && depCrate == "libm" && (modPath == "support" || modPath.startsWith("support."))) {
            listOf(modPath, "math.$modPath")
        } else {
            listOf(modPath)
        }
    for (candMod in modPaths) {
        val exactPkg = if (candMod.isEmpty()) baseNs else "$baseNs.$candMod"
        val matched =
            if (exactPkg in candidates) {
                exactPkg
            } else {
                candidates
                    .filter { it.startsWith("$exactPkg.") }
                    .minByOrNull { it.count { ch -> ch == '.' } }
            }
        if (matched != null) return matched
    }
    return null
}

internal fun KotlinJ2KEmitter.resolveExternalDepReexportPkg(
    explicitModule: String,
    symKt: String,
    nsPrefix: String?,
): String? {
    for (depCrate in EXTERNAL_REEXPORT_SEARCH_CRATES) {
        val depNs =
            headerContext.crateNamespaces[depCrate]
                ?.trim()
                ?.removeSuffix(".")
                ?.takeIf { it.isNotEmpty() } ?: continue
        if (depNs == nsPrefix) continue
        val extCandidates = headerContext.externalCrateSymbolPackages[depCrate]?.get(symKt) ?: continue
        matchExternalSymbolPackage(depCrate, depNs, explicitModule, extCandidates)?.let { return "$it.$symKt" }
    }
    return null
}

private val CORE_ROOT_PRIMITIVE_WRAPPER_SYMBOLS: Set<String>
    get() = activeRules().words("coreRootPrimitiveWrapperSymbols")
private val CORE_MEM_SUBPKG_SYMBOLS: Set<String>
    get() = activeRules().words("coreMemSubpkgSymbols")

internal fun KotlinJ2KEmitter.resolveBestSubpackageForSymbol(ktSymbol: String): String? {
    val nsPrefix = currentNamespacePrefix()
    if (nsPrefix?.endsWith(".core") == true && ktSymbol in CORE_ROOT_PRIMITIVE_WRAPPER_SYMBOLS) {
        return ""
    }
    if (nsPrefix?.endsWith(".core") == true &&
        ktSymbol in CORE_MEM_SUBPKG_SYMBOLS &&
        "mem" in headerContext.crateSymbolSubpackages[ktSymbol].orEmpty()
    ) {
        return "mem"
    }
    val candidates = headerContext.crateSymbolSubpackages[ktSymbol] ?: return null
    return resolveBestSubpackageAmong(candidates)
}

internal fun KotlinJ2KEmitter.resolveBestSubpackageAmong(candidates: List<String>): String? {
    if (candidates.isEmpty()) return null
    val cur = currentSubpackageString()
    if (cur in candidates) return cur
    if (candidates.size == 1) return candidates[0]
    return SubpackageAffinityScorer(cur).pickBest(candidates)
}

private class SubpackageAffinityScorer(
    private val cur: String,
) {
    private val curSegs: List<String> = if (cur.isEmpty()) emptyList() else cur.split('.')
    private val curSignificantSegs: Set<String> = curSegs.filterTo(hashSetOf()) { it !in NON_SIGNIFICANT_SEGS }

    fun pickBest(candidates: List<String>): String? = candidates.maxByOrNull(::score)

    private fun archAndOsBias(candSegs: List<String>): Int {
        var bias = 0
        for (s in candSegs) {
            when (s) {
                in HIGH_ARCH_SEGS -> bias += 150
                in BSD_FAMILY_SEGS -> bias += 65
                in UNIX_BIAS_SEGS -> bias += 60
                in BITS32_PENALTY_SEGS -> bias -= 300
            }
        }
        return bias
    }

    private fun osAffinityBias(candSegs: List<String>): Int {
        var bias = 0
        when {
            "windows" in curSegs -> {
                if ("windows" in candSegs) bias += 5000
                if (candSegs.any { it in UNIX_FAMILY_SEGS || (it in FOREIGN_PLATFORM_SEGS && it != "windows") }) {
                    bias -= 5000
                }
            }
            "wasi" in curSegs || "wasip1" in curSegs -> {
                if ("wasi" in candSegs) bias += 5000
                if ("wasip1" in candSegs) bias += 4000
                if ("unix" in candSegs) bias += 2000
                if (candSegs.any { it in WASI_FOREIGN_PENALTY_SEGS }) bias -= 5000
            }
            curSegs.any { it in UNIX_FAMILY_SEGS } -> {
                if ("unix" in candSegs) bias += 5000
                if (candSegs.any { it in UNIX_FAMILY_SEGS && it in curSegs }) bias += 1500
                if (candSegs.any { it in FOREIGN_PLATFORM_SEGS }) bias -= 5000
            }
            else -> {
                if (("unsupported" in candSegs && "unsupported" !in curSegs) || ("stubs" in candSegs && "stubs" !in curSegs)) {
                    bias -= 4000
                }
                if (candSegs.any { it in WASIP_VERSION_SEGS }) {
                    bias -= 2000
                } else if ("wasi" in candSegs && "wasi" !in curSegs) {
                    bias -= 1000
                }
            }
        }
        return bias
    }

    private fun score(cand: String): Int {
        val candSegs = if (cand.isEmpty()) emptyList() else cand.split('.')
        if (cand.isEmpty() || cur.startsWith("$cand.")) {
            return 20_000 + candSegs.size * 200
        }
        if (cur.isEmpty() || cand.startsWith("$cur.")) {
            val depthDelta = candSegs.size - curSegs.size
            return 10_000 - depthDelta * 40 + archAndOsBias(candSegs) + osAffinityBias(candSegs)
        }
        var common = 0
        while (common < curSegs.size && common < candSegs.size && curSegs[common] == candSegs[common]) {
            common++
        }
        val sharedOsBonus = candSegs.count { it in curSignificantSegs } * 2000
        return common * 800 + sharedOsBonus - candSegs.size * 15 + archAndOsBias(candSegs) + osAffinityBias(candSegs)
    }

    companion object {
        private val HIGH_ARCH_SEGS: Set<String> get() = activeRules().words("highArchSegs")
        private val BSD_FAMILY_SEGS: Set<String> get() = activeRules().words("bsdFamilySegs")
        private val UNIX_BIAS_SEGS: Set<String> get() = activeRules().words("unixBiasSegs")
        private val BITS32_PENALTY_SEGS: Set<String> get() = activeRules().words("bits32PenaltySegs")
        private val UNIX_FAMILY_SEGS: Set<String> get() = activeRules().words("unixFamilySegs")
        private val FOREIGN_PLATFORM_SEGS: Set<String> get() = activeRules().words("foreignPlatformSegs")
        private val NON_SIGNIFICANT_SEGS: Set<String> get() = activeRules().words("nonSignificantSegs")
        private val WASI_FOREIGN_PENALTY_SEGS: Set<String> get() = activeRules().words("wasiForeignPenaltySegs")
        private val WASIP_VERSION_SEGS: Set<String> get() = activeRules().words("wasipVersionSegs")
    }
}

private fun KotlinJ2KEmitter.resolveFlattenedInlineModuleSubpackage(
    explicitModule: String,
    candidates: List<String>,
): String? {
    if (explicitModule.isEmpty() || explicitModule in headerContext.crateSubpackageRawLeafNames) return null
    var curMod = explicitModule
    while ('.' in curMod) {
        val parentMod = curMod.substringBeforeLast('.')
        if (parentMod in candidates) return parentMod
        if (parentMod in headerContext.crateSubpackageRawLeafNames) break
        curMod = parentMod
    }
    if ("" in candidates && headerContext.crateSubpackageRawLeafNames.isNotEmpty()) return ""
    return null
}

internal fun KotlinJ2KEmitter.isResolvedCrateSubpackageModule(resolvedImportPath: String, rawLeafIdent: String): Boolean {
    if (headerContext.crateSubpackageRawLeafNames.isEmpty()) return false
    val nsPrefix = currentNamespacePrefix() ?: return false
    if (!resolvedImportPath.startsWith("$nsPrefix.")) return false
    val cleanRaw = rawLeafIdent.removePrefix("r#")
    if (cleanRaw.firstOrNull()?.isLowerCase() != true || isKnownFunctionLeafIdent(cleanRaw)) return false
    val afterNs = resolvedImportPath.removePrefix("$nsPrefix.")
    if (headerContext.crateSubpackageRawLeafNames[afterNs] == cleanRaw) return true
    val pascalLeaf = rustTypeIdentToKotlin(cleanRaw)
    val leafMod = rustModuleIdentToKotlin(cleanRaw.replace('-', '_'))
    if (afterNs == pascalLeaf || afterNs.endsWith(".$pascalLeaf")) {
        val stripped = if (afterNs == pascalLeaf) "" else afterNs.removeSuffix(".$pascalLeaf")
        if (stripped.isNotEmpty() && headerContext.crateSubpackageRawLeafNames[stripped] == cleanRaw) return true
        val candFromStripped = if (stripped.isEmpty()) leafMod else "$stripped.$leafMod"
        if (headerContext.crateSubpackageRawLeafNames[candFromStripped] == cleanRaw) return true
    }
    val parentSubpkg = afterNs.substringBeforeLast('.', missingDelimiterValue = "")
    val candidateSubpkg = if (parentSubpkg.isEmpty()) leafMod else "$parentSubpkg.$leafMod"
    if (headerContext.crateSubpackageRawLeafNames[candidateSubpkg] == cleanRaw) return true
    val candidateKts =
        listOf(
            rustValueIdentToKotlin(cleanRaw),
            rustFunctionIdentToKotlin(cleanRaw),
            rustConstIdentToKotlin(cleanRaw),
            pascalLeaf,
        )
    return afterNs.substringAfterLast('.') == leafMod &&
        (parentSubpkg.isEmpty() || parentSubpkg in headerContext.crateSubpackageRawLeafNames) &&
        candidateKts.none { parentSubpkg in headerContext.crateSymbolSubpackages[it].orEmpty() }
}

internal fun KotlinJ2KEmitter.scopedAbiKeysForRenderedType(renderedType: String): List<String> {
    val clean =
        renderedType
            .substringBefore('<')
            .trim()
            .removeSuffix("?")
            .trim()
    if (clean.isEmpty()) return emptyList()
    val keys = mutableListOf<String>()
    val nsPrefix = currentNamespacePrefix()

    fun addSubpkgAndAncestors(sub: String, sym: String) {
        var cur = sub
        while (cur.isNotEmpty()) {
            keys += "$cur.$sym"
            cur = cur.substringBeforeLast('.', missingDelimiterValue = "")
        }
    }

    if ('.' in clean) {
        if (nsPrefix != null && clean.startsWith("$nsPrefix.")) {
            val stripped = clean.removePrefix("$nsPrefix.")
            keys += stripped
            val subPart = stripped.substringBeforeLast('.', missingDelimiterValue = "")
            val symPart = stripped.substringAfterLast('.')
            if (subPart.isNotEmpty()) addSubpkgAndAncestors(subPart, symPart)
        }
        val curSub = currentSubpackageString()
        if (curSub.isNotEmpty()) addSubpkgAndAncestors(curSub, clean)
        keys += clean
        keys += clean.substringAfterLast('.')
        return keys.distinct()
    }
    val importedPkg = importedSimpleNameToPkg[clean]
    if (importedPkg != null && nsPrefix != null) {
        if (importedPkg == nsPrefix) {
            keys += clean
        } else if (importedPkg.startsWith("$nsPrefix.")) {
            addSubpkgAndAncestors(importedPkg.removePrefix("$nsPrefix."), clean)
        }
    } else if (importedPkg == null) {
        val bestSub = resolveBestSubpackageForSymbol(clean)
        if (!bestSub.isNullOrEmpty()) addSubpkgAndAncestors(bestSub, clean)
    }
    val curSub = currentSubpackageString()
    if (curSub.isNotEmpty()) addSubpkgAndAncestors(curSub, clean)
    keys += clean
    return keys.distinct()
}

private val KOTLIN_BUILTIN_QUALIFY_SIMPLE_TYPE_NAMES: Set<String>
    get() = activeRules().words("kotlinBuiltinQualifySimpleTypeNames")

internal fun KotlinJ2KEmitter.isDeclaredTopLevelFnKotlinName(ktName: String): Boolean =
    ktName == currentEnclosingFunctionName ||
        inspector.declaredTopLevelFnRawNames.any { rustFunctionIdentToKotlin(it) == ktName }

private val STDLIB_UNIMPORTED_SUBMODULES: Set<String>
    get() = activeRules().words("stdlibUnimportedSubmodules")

internal fun KotlinJ2KEmitter.bindOrQualifyResolvedCrossSymbol(resolvedFullImport: String): String {
    val rawTarget = resolvedFullImport.substringBefore(" as ").trim()
    val explicitAlias =
        if (" as " in resolvedFullImport) {
            resolvedFullImport.substringAfter(" as ").trim()
        } else {
            null
        }
    val resolvedPkg = rawTarget.substringBeforeLast('.', missingDelimiterValue = "")
    val simpleName = explicitAlias ?: rawTarget.substringAfterLast('.')
    val collidedPkg = importedSimpleNameToPkg[simpleName]
    val isDiffNonEmptyPkg = resolvedPkg.isNotEmpty() && resolvedPkg != headerContext.packageName
    return when {
        simpleName in KOTLIN_BUILTIN_QUALIFY_SIMPLE_TYPE_NAMES ->
            if (isDiffNonEmptyPkg) rawTarget else simpleName
        simpleName in inspector.attachableTypeNames ||
            simpleName in inspector.declaredTypeNames ||
            isDeclaredTopLevelFnKotlinName(simpleName) ->
            if (isDiffNonEmptyPkg) rawTarget else simpleName
        collidedPkg != null && collidedPkg != resolvedPkg -> rawTarget
        explicitAlias == null &&
            isDiffNonEmptyPkg &&
            !rawTarget.endsWith(".Result.$simpleName") &&
            !rawTarget.endsWith(".Option.$simpleName") &&
            (
                simpleName in inspector.enumVariantSimpleNames ||
                    simpleName in inspector.importedEnumVariantSimpleNames ||
                    (
                        inspector.hasImportedEnumGlob &&
                            (
                                headerContext.crateEnumVariantTypeArity.containsKey(simpleName) ||
                                    headerContext.crateEnumVariantTypeArity.keys.any { it.endsWith(".$simpleName") } ||
                                    headerContext.externalEnumVariantTypeArity.containsKey(simpleName)
                            )
                    )
            ) -> rawTarget
        isDiffNonEmptyPkg -> {
            importedSimpleNameToPkg[simpleName] = resolvedPkg
            extraTypePathImports += if (explicitAlias != null) "import $rawTarget as $explicitAlias" else "import $rawTarget"
            simpleName
        }
        else -> simpleName
    }
}

internal fun KotlinJ2KEmitter.rewriteCrateRootAliasAndReexportSegments(segs: List<String>): List<String> {
    if (segs.isEmpty()) return segs
    if (headerContext.rootSelfDirModuleAliases.isEmpty() && headerContext.rootExternalModuleReexports.isEmpty()) {
        return segs
    }

    fun tryRewriteSeg(modRaw: String, tail: List<String>, checkLocalShadow: Boolean): List<String>? {
        if (modRaw in headerContext.rootSelfDirModuleAliases) return listOf("crate") + tail
        val reexportTarget = headerContext.rootExternalModuleReexports[modRaw] ?: return null
        if (checkLocalShadow && (modRaw in inspector.localInlineModuleNames || modRaw in inspector.localExternalModuleNames)) {
            return null
        }
        return reexportTarget.split("::") + tail
    }

    var cur = segs
    var guard = 0
    while (guard++ < 4) {
        val firstRaw = cur.firstOrNull()?.removePrefix("r#") ?: break
        val isCrateRootPrefix = firstRaw == "crate" || firstRaw in headerContext.selfCrateAliases
        if (isCrateRootPrefix && cur.size >= 3) {
            tryRewriteSeg(cur[1].removePrefix("r#"), cur.drop(2), checkLocalShadow = true)?.let {
                cur = it
                continue
            }
        }
        if (cur.size >= 2 &&
            firstRaw !in inspector.localInlineModuleNames &&
            firstRaw !in inspector.localExternalModuleNames &&
            !scopeBuilder.isImportedModuleInScope(firstRaw)
        ) {
            tryRewriteSeg(firstRaw, cur.drop(1), checkLocalShadow = false)?.let {
                cur = it
                continue
            }
        }
        break
    }
    return cur
}

internal fun KotlinJ2KEmitter.resolveStdlibFfiSymbolImport(
    rustLeaf: String,
    isAllocCString: Boolean = false,
): String? {
    val ktSym =
        if (isAllocCString || rustLeaf == "CString") {
            "CString"
        } else {
            CoreIntrinsicLowering.STDLIB_CORE_FFI_SYMBOLS[rustLeaf] ?: return null
        }
    if (ktSym in inspector.declaredTypeNames) return null
    val coreNs = CoreIntrinsicLowering.resolveCoreNamespace(headerContext)
    val allocNs = CoreIntrinsicLowering.resolveAllocNamespace(headerContext)
    val nsPrefix = currentNamespacePrefix()
    val ownerNs = if (ktSym == "CString") allocNs else coreNs
    if (nsPrefix != null && nsPrefix == ownerNs) {
        val bestLocal = resolveBestSubpackageForSymbol(ktSym)
        if (bestLocal != null) {
            return if (bestLocal.isEmpty()) "$nsPrefix.$ktSym" else "$nsPrefix.$bestLocal.$ktSym"
        }
    }
    val crateKey = if (ktSym == "CString") "alloc" else "core"
    val extCandidates = headerContext.externalCrateSymbolPackages[crateKey]?.get(ktSym)
    if (!extCandidates.isNullOrEmpty()) {
        val ffiMatch =
            extCandidates
                .filter { ".ffi." in it || it.endsWith(".ffi") }
                .minByOrNull { it.length }
                ?: extCandidates.minByOrNull { it.length }
        if (ffiMatch != null) return "$ffiMatch.$ktSym"
    }
    return "${CoreIntrinsicLowering.defaultStdlibFfiSymbolPackage(ktSym, coreNs, allocNs)}.$ktSym"
}

internal fun KotlinJ2KEmitter.resolveCrossModulePathTarget(
    rawInputSegStrings: List<String>,
    inTypePosition: Boolean = false,
): Pair<String, List<String>>? =
    CrossModulePathTargetResolver(this).resolve(rawInputSegStrings, inTypePosition)

internal fun KotlinJ2KEmitter.resolveKotlinImportPath(
    rawSegsInput: List<String>,
    boundRawAlias: String? = null,
    isSelfImport: Boolean = false,
    forceTypeLeaf: Boolean = false,
): String? =
    KotlinImportPathResolver(this).resolve(rawSegsInput, boundRawAlias, isSelfImport, forceTypeLeaf)

/**
 * Shared base class for single-file Rust module, crate, stdlib, and `use`-tree path resolution.
 *
 * Centralizes crate-root alias rewriting, stdlib `core`/`alloc`/`std`/FFI special-case targets,
 * Kotlin segment casing, intermediate enum/type segment detection, and crate subpackage lookup
 * across [CrossModulePathTargetResolver] and [KotlinImportPathResolver].
 */
internal open class RustModulePathResolver(
    protected val emitter: KotlinJ2KEmitter,
) {
    protected val headerContext
        get() = emitter.headerContext

    protected val inspector
        get() = emitter.inspector

    protected val scopeBuilder
        get() = emitter.scopeBuilder

    protected fun qualifyInCrate(nsPrefix: String, subPkg: String, tail: String): String =
        if (subPkg.isEmpty()) "$nsPrefix.$tail" else "$nsPrefix.$subPkg.$tail"

    protected fun cleanDotPrefix(raw: String?): String? =
        raw
            ?.trim()
            ?.removeSuffix(".")
            ?.takeIf { it.isNotEmpty() }

    protected fun cleanConfiguredCrateNamespace(crateKey: String): String? =
        cleanDotPrefix(headerContext.crateNamespaces[crateKey])

    protected fun isInRustLangCrate(): Boolean =
        headerContext.packageName?.startsWith("io.github.kotlinmania.rustlang.") == true

    protected fun tryResolveCoreMemIntrinsic(
        firstClean: String,
        secondClean: String?,
        thirdClean: String?,
        requireCoreWhenCrateRoot: Boolean,
    ): String? {
        if (secondClean != "intrinsics" || thirdClean == null || thirdClean !in CORE_MEM_INTRINSICS) {
            return null
        }
        val coreNs = CoreIntrinsicLowering.resolveCoreNamespace(headerContext)
        val validRoot =
            firstClean == "core" ||
                firstClean == "std" ||
                (firstClean == "crate" && (!requireCoreWhenCrateRoot || emitter.currentNamespacePrefix() == coreNs))
        return if (validRoot) "$coreNs.mem.${rustFunctionIdentToKotlin(thirdClean)}" else null
    }

    protected fun tryResolveStdlibRcOrArc(
        firstClean: String,
        secondClean: String?,
        symClean: String,
    ): String? {
        if (headerContext.rawAbiMode || !isInRustLangCrate()) return null
        if (firstClean !in ALLOC_STD_CRATE_ROOTS) return null
        val pkg = headerContext.packageName.orEmpty()
        if (firstClean == "crate" &&
            !pkg.startsWith("io.github.kotlinmania.rustlang.std") &&
            !pkg.startsWith("io.github.kotlinmania.rustlang.alloc")
        ) {
            return null
        }
        if (symClean in inspector.declaredTypeNames) return null
        val allocNs = CoreIntrinsicLowering.resolveAllocNamespace(headerContext)
        return when {
            secondClean == "sync" && symClean in ARC_WEAK_SYMBOLS -> "$allocNs.rcs.arc.$symClean"
            secondClean == "rc" && symClean in RC_WEAK_SYMBOLS -> "$allocNs.rcs.rc.$symClean"
            else -> null
        }
    }

    protected fun tryResolveFfiLeafSymbol(rustLeaf: String): String? {
        if (rustLeaf == "CString") {
            return emitter.resolveStdlibFfiSymbolImport("CString", isAllocCString = true)
        }
        if (rustLeaf in CoreIntrinsicLowering.STDLIB_CORE_FFI_SYMBOLS) {
            return emitter.resolveStdlibFfiSymbolImport(rustLeaf)
        }
        return null
    }

    protected fun isIntermediateTypeSegment(
        segment: String,
        idx: Int,
        segments: List<String>,
        baseSegs: List<String> = emptyList(),
    ): Boolean {
        if (segment.firstOrNull()?.isUpperCase() != true) return false
        if (headerContext.crateSubpackageRawLeafNames.isNotEmpty()) {
            val mappedPrefix =
                segments.take(idx + 1).map { rustModuleIdentToKotlin(it.removePrefix("r#").replace('-', '_')) }
            val candidateSubpkg = (baseSegs + mappedPrefix).filter { it.isNotEmpty() }.joinToString(".")
            if (candidateSubpkg.isNotEmpty() &&
                headerContext.crateSubpackageRawLeafNames[candidateSubpkg] == segment.removePrefix("r#")
            ) {
                return false
            }
        }
        val kt = rustTypeIdentToKotlin(segment)
        if (kt in inspector.declaredTypeNames ||
            kt in headerContext.crateSymbolSubpackages ||
            headerContext.externalCrateSymbolPackages.values.any { kt in it }
        ) {
            return true
        }
        if (headerContext.crateSymbolSubpackages.isNotEmpty()) return false
        return idx == segments.lastIndex - 1 && segments.last() == "*"
    }

    protected fun mapImportLeafIdent(
        s: String,
        boundRawAlias: String? = null,
        isSelfImport: Boolean = false,
        forceTypeLeaf: Boolean = false,
    ): String =
        when {
            s == "*" -> "*"
            forceTypeLeaf || emitter.isImportedTypeLeaf(s, boundRawAlias, isSelfImport) -> rustTypeIdentToKotlin(s)
            emitter.isKnownFunctionLeafIdent(s) -> rustFunctionIdentToKotlin(s)
            emitter.isImportedScreamingConstLeaf(s, boundRawAlias, isSelfImport) -> rustConstIdentToKotlin(s)
            s.first().isUpperCase() -> rustTypeIdentToKotlin(s)
            else -> rustValueIdentToKotlin(s)
        }

    protected fun mapRelativeUseTail(
        relSegs: List<String>,
        baseSegs: List<String> = emptyList(),
        boundRawAlias: String? = null,
        isSelfImport: Boolean = false,
        forceTypeLeaf: Boolean = false,
    ): List<String> =
        relSegs.mapIndexed { idx, s ->
            if (idx == relSegs.lastIndex) {
                mapImportLeafIdent(s, boundRawAlias, isSelfImport, forceTypeLeaf)
            } else if (isIntermediateTypeSegment(s, idx, relSegs, baseSegs)) {
                rustTypeIdentToKotlin(s)
            } else {
                rustModuleIdentToKotlin(s)
            }
        }

    protected fun resolveIntermediateTypeUsePath(
        nsPrefix: String,
        relSegs: List<String>,
        mappedRelSegs: List<String>,
        baseSegs: List<String> = emptyList(),
    ): String? {
        if (relSegs.size < 2 || headerContext.crateSymbolSubpackages.isEmpty()) return null
        val interTypeIdx =
            (0 until relSegs.lastIndex).firstOrNull { idx ->
                isIntermediateTypeSegment(relSegs[idx], idx, relSegs, baseSegs)
            } ?: return null
        val typeKt = mappedRelSegs[interTypeIdx]
        val targetPrefix = (baseSegs + mappedRelSegs.take(interTypeIdx)).joinToString(".")
        val candidates = headerContext.crateSymbolSubpackages[typeKt].orEmpty()
        val resolvedSub =
            if (targetPrefix in candidates) {
                targetPrefix
            } else {
                emitter.resolveBestSubpackageAmong(
                    candidates.filter { targetPrefix.isEmpty() || it.startsWith("$targetPrefix.") },
                )
            } ?: return null
        return qualifyInCrate(nsPrefix, resolvedSub, mappedRelSegs.drop(interTypeIdx).joinToString("."))
    }

    protected fun resolveFlattenedOrChildSubpackage(
        explicitModule: String,
        candidates: List<String>,
    ): String? {
        emitter.resolveFlattenedInlineModuleSubpackage(explicitModule, candidates)?.let { return it }
        return emitter.resolveBestSubpackageAmong(
            candidates.filter { explicitModule.isEmpty() || it.startsWith("$explicitModule.") },
        )
    }

    protected fun isCoreSyncAtomicPrefix(
        segs: List<String>,
        firstClean: String,
        secondClean: String?,
        thirdClean: String?,
    ): Boolean =
        !headerContext.rawAbiMode &&
            isInRustLangCrate() &&
            segs.size in 4..5 &&
            firstClean in CORE_STD_CRATE_ROOTS &&
            secondClean == "sync" &&
            thirdClean == "atomic"

    companion object {
        internal val CORE_MEM_INTRINSICS: Set<String> get() = activeRules().words("coreMemIntrinsics")
        private val ALLOC_STD_CRATE_ROOTS = wordSet("alloc std crate")
        private val ARC_WEAK_SYMBOLS = wordSet("Arc Weak")
        private val RC_WEAK_SYMBOLS = wordSet("Rc Weak")
        internal val CORE_STD_CRATE_ROOTS = wordSet("core std crate")
        internal val STDLIB_CORE_SUBMODULES: Set<String> get() = activeRules().words("stdlibCoreSubmodules")
        internal val CORE_INTRINSIC_OR_PRELUDE_LEAVES: Set<String>
            get() = activeRules().words("coreIntrinsicOrPreludeLeaves")
    }
}

private class CrossModulePathTargetResolver(
    emitter: KotlinJ2KEmitter,
) : RustModulePathResolver(emitter) {
    private sealed class BranchDecision {
        object Continue : BranchDecision()

        data class Done(
            val target: Pair<String, List<String>>?,
        ) : BranchDecision()
    }

    fun resolve(
        rawInputSegStrings: List<String>,
        inTypePosition: Boolean,
    ): Pair<String, List<String>>? {
        val rawSegStrings = emitter.rewriteCrateRootAliasAndReexportSegments(rawInputSegStrings)
        if (rawSegStrings.size < 2) return null
        val firstRaw = rawSegStrings.first().removePrefix("r#")
        if (firstRaw in inspector.localInlineModuleNames) return null
        tryResolveEarlyStdlibTarget(rawSegStrings, firstRaw)?.let { return it }
        if (shouldRejectEarlyCrossModulePath(rawSegStrings, firstRaw)) return null
        if (firstRaw == "core") {
            val coreDecision = evaluateCoreRootBranch(rawSegStrings, inTypePosition)
            if (coreDecision is BranchDecision.Done) return coreDecision.target
        }
        val nsPrefix = emitter.currentNamespacePrefix()
        if (scopeBuilder.isImportedModuleInScope(firstRaw)) {
            return resolveScopedImportedModuleTarget(firstRaw, rawSegStrings.drop(1), nsPrefix, inTypePosition)
        }
        val navDecision = evaluateSelfOrRelativeNavBranch(rawSegStrings, firstRaw, nsPrefix, inTypePosition)
        if (navDecision is BranchDecision.Done) return navDecision.target
        if (firstRaw.firstOrNull()?.isLowerCase() == true) {
            return resolveLowercaseExternalCrateTarget(rawSegStrings, firstRaw, inTypePosition)
        }
        return null
    }

    private fun tryResolveEarlyStdlibTarget(
        rawSegStrings: List<String>,
        firstRaw: String,
    ): Pair<String, List<String>>? {
        val secondClean = rawSegStrings.getOrNull(1)?.removePrefix("r#")
        val thirdClean = rawSegStrings.getOrNull(2)?.removePrefix("r#")
        if (isCoreSyncAtomicPrefix(rawSegStrings, firstRaw, secondClean, thirdClean)) {
            val coreNs = CoreIntrinsicLowering.resolveCoreNamespace(headerContext)
            val fourthSym = rawSegStrings[3].removePrefix("r#")
            if (rawSegStrings.size == 5) {
                if (fourthSym.firstOrNull()?.isUpperCase() == true) {
                    return "$coreNs.sync.atomic.${rustTypeIdentToKotlin(fourthSym)}" to listOf(rawSegStrings[4])
                }
            } else {
                val ktSym =
                    if (fourthSym.firstOrNull()?.isUpperCase() == true) {
                        rustTypeIdentToKotlin(fourthSym)
                    } else {
                        rustFunctionIdentToKotlin(fourthSym)
                    }
                return "$coreNs.sync.atomic.$ktSym" to emptyList()
            }
        }
        if (rawSegStrings.size == 3) {
            tryResolveCoreMemIntrinsic(firstRaw, secondClean, thirdClean, requireCoreWhenCrateRoot = false)?.let {
                return it to emptyList()
            }
        }
        if (rawSegStrings.size in 3..4) {
            tryResolveStdlibRcOrArc(firstRaw, secondClean, rawSegStrings[2].removePrefix("r#"))?.let {
                return it to rawSegStrings.drop(3)
            }
        }
        return null
    }

    private fun shouldRejectEarlyCrossModulePath(
        rawSegStrings: List<String>,
        firstRaw: String,
    ): Boolean {
        if (firstRaw == "std" || firstRaw == "alloc" || firstRaw == "Self" || firstRaw in PRIMITIVE_TYPE_NAMES) {
            return true
        }
        if (rawSegStrings.dropLast(1).any { it.removePrefix("r#") in STDLIB_PRELUDE_REEXPORT_MODULES }) {
            return true
        }
        val secondClean = rawSegStrings.getOrNull(1)?.removePrefix("r#")
        val thirdClean = rawSegStrings.getOrNull(2)?.removePrefix("r#")
        val pkg = headerContext.packageName
        val isAllocCratePkg =
            pkg == "io.github.kotlinmania.rustlang.alloc" ||
                pkg?.startsWith("io.github.kotlinmania.rustlang.alloc.") == true
        return secondClean == "string" && thirdClean == "String" && firstRaw == "crate" && isAllocCratePkg
    }

    private fun evaluateCoreRootBranch(
        rawSegStrings: List<String>,
        inTypePosition: Boolean,
    ): BranchDecision {
        val isCrateCoreModule =
            headerContext.crateSubpackageRawLeafNames["core"] == "core" ||
                "core" in inspector.localExternalModuleNames
        val secondRaw = rawSegStrings.getOrNull(1)?.removePrefix("r#")
        if (!isCrateCoreModule || secondRaw in STDLIB_MACRO_OR_PRIMITIVE_MODULES || secondRaw in STDLIB_CORE_SUBMODULES) {
            val inCoreCrate = emitter.currentNamespacePrefix() == CoreIntrinsicLowering.resolveCoreNamespace(headerContext)
            val leafClean = rawSegStrings.last().removePrefix("r#")
            if (inCoreCrate &&
                rawSegStrings.size >= 3 &&
                secondRaw !in INTRINSICS_OR_FFI &&
                secondRaw !in STDLIB_MACRO_OR_PRIMITIVE_MODULES &&
                leafClean !in CORE_INTRINSIC_OR_PRELUDE_LEAVES
            ) {
                return BranchDecision.Done(resolve(listOf("crate") + rawSegStrings.drop(1), inTypePosition))
            }
            return BranchDecision.Done(null)
        }
        return BranchDecision.Continue
    }

    private fun resolveScopedImportedModuleTarget(
        firstRaw: String,
        rest: List<String>,
        nsPrefix: String?,
        inTypePosition: Boolean,
    ): Pair<String, List<String>>? {
        val modBinding = scopeBuilder.resolveModuleBinding(firstRaw) ?: return null
        val importedPath = modBinding.importedPath ?: return null
        val coreNs = CoreIntrinsicLowering.resolveCoreNamespace(headerContext)
        if (firstRaw == "intrinsics" && rest.size == 1) {
            val intrinsicName = rest[0].removePrefix("r#")
            if (intrinsicName in CORE_MEM_INTRINSICS &&
                (importedPath == "core.intrinsics" || importedPath == "std.intrinsics" || importedPath == "$coreNs.intrinsics")
            ) {
                return "$coreNs.mem.${rustFunctionIdentToKotlin(intrinsicName)}" to emptyList()
            }
        }
        if (rest.size == 1 &&
            !headerContext.rawAbiMode &&
            (importedPath.endsWith(".os.raw") || importedPath.endsWith(".ffi"))
        ) {
            tryResolveFfiLeafSymbol(rest[0].removePrefix("r#"))?.let { return it to emptyList() }
        }
        val isSameCrateImport = nsPrefix != null && (importedPath == nsPrefix || importedPath.startsWith("$nsPrefix."))
        if (isSameCrateImport && rest.isNotEmpty()) {
            if (importedPath == nsPrefix) {
                return resolvePeeledImportTarget(listOf("crate"), rest, inTypePosition)
            }
            return resolveSameCrateBoundSubpackageTarget(importedPath.removePrefix("$nsPrefix."), rest, nsPrefix, inTypePosition)
        }
        val matchedExtCrate = matchImportedExternalCrate(importedPath, nsPrefix)
        if (matchedExtCrate != null && rest.isNotEmpty()) {
            return resolvePeeledImportTarget(listOf(matchedExtCrate.first) + matchedExtCrate.second, rest, inTypePosition)
        }
        return null
    }

    private fun resolvePeeledImportTarget(
        prefixSegs: List<String>,
        rest: List<String>,
        inTypePosition: Boolean,
    ): Pair<String, List<String>>? {
        for (trailingCount in 0..1) {
            if (rest.size <= trailingCount) continue
            val effectiveRest = rest.dropLast(trailingCount)
            val midSegs = effectiveRest.dropLast(1)
            val allMidLower = midSegs.all { it.removePrefix("r#").firstOrNull()?.isLowerCase() == true }
            if (allMidLower) {
                val resolved =
                    emitter.resolveKotlinImportPath(
                        prefixSegs + effectiveRest,
                        forceTypeLeaf = inTypePosition || trailingCount > 0,
                    )
                if (resolved != null) {
                    return resolved to rest.takeLast(trailingCount)
                }
            }
        }
        return null
    }

    private fun resolveSameCrateBoundSubpackageTarget(
        boundSubpkg: String,
        rest: List<String>,
        nsPrefix: String,
        inTypePosition: Boolean,
    ): Pair<String, List<String>>? {
        val subpkgLeafMap = headerContext.crateSubpackageRawLeafNames
        val symSubpkgMap = headerContext.crateSymbolSubpackages
        val boundExistsInCrate =
            boundSubpkg in subpkgLeafMap ||
                subpkgLeafMap.keys.any { it.startsWith("$boundSubpkg.") } ||
                symSubpkgMap.values.any { pkgs -> pkgs.any { it == boundSubpkg || it.startsWith("$boundSubpkg.") } }
        var resolvedBoundSubpkg: String? = if (boundExistsInCrate) boundSubpkg else null
        if (resolvedBoundSubpkg == null) {
            var anc = boundSubpkg
            while ('.' in anc) {
                anc = anc.substringBeforeLast('.')
                if (anc in subpkgLeafMap || symSubpkgMap.values.any { anc in it }) {
                    resolvedBoundSubpkg = anc
                    break
                }
            }
            if (resolvedBoundSubpkg == null && symSubpkgMap.values.any { "" in it }) {
                resolvedBoundSubpkg = ""
            }
        }
        var modSubpkg = resolvedBoundSubpkg ?: return null
        var idx = 0
        while (idx < rest.lastIndex) {
            val segClean = rest[idx].removePrefix("r#")
            val nextSub = "$modSubpkg.${rustModuleIdentToKotlin(segClean.replace('-', '_'))}"
            if (subpkgLeafMap[nextSub] == segClean || subpkgLeafMap.keys.any { it.startsWith("$nextSub.") }) {
                modSubpkg = nextSub
                idx++
            } else {
                break
            }
        }
        val symRaw = rest[idx]
        val trailing = rest.drop(idx + 1)
        val symKt = mapImportLeafIdent(symRaw, forceTypeLeaf = inTypePosition || trailing.isNotEmpty())
        val candidateSymKts =
            if (!inTypePosition && trailing.isEmpty()) {
                listOf(
                    symKt,
                    rustConstIdentToKotlin(symRaw),
                    rustFunctionIdentToKotlin(symRaw),
                    rustTypeIdentToKotlin(symRaw),
                    rustValueIdentToKotlin(symRaw),
                ).distinct()
            } else {
                listOf(symKt)
            }
        for (candSymKt in candidateSymKts) {
            val symCandidates = symSubpkgMap[candSymKt].orEmpty()
            val scopedCandidates =
                when {
                    modSubpkg in symCandidates -> listOf(modSubpkg)
                    modSubpkg.isEmpty() -> symCandidates
                    else -> symCandidates.filter { it.startsWith("$modSubpkg.") }
                }
            val finalSubpkg = emitter.resolveBestSubpackageAmong(scopedCandidates)
            if (finalSubpkg != null) {
                return qualifyInCrate(nsPrefix, finalSubpkg, candSymKt) to trailing
            }
        }
        if (trailing.isEmpty() && !inTypePosition) {
            val leafModKt = rustModuleIdentToKotlin(symRaw.removePrefix("r#").replace('-', '_'))
            val childModSubpkg = if (modSubpkg.isEmpty()) leafModKt else "$modSubpkg.$leafModKt"
            if (childModSubpkg in subpkgLeafMap && symSubpkgMap["call"]?.contains(childModSubpkg) == true) {
                return "$nsPrefix.$childModSubpkg.call as ${symRaw.removePrefix("r#")}" to emptyList()
            }
        }
        emitter.resolveExternalDepReexportPkg(modSubpkg, symKt, nsPrefix)?.let { return it to trailing }
        return qualifyInCrate(nsPrefix, modSubpkg, symKt) to trailing
    }

    private fun matchImportedExternalCrate(
        importedPath: String,
        nsPrefix: String?,
    ): Pair<String, List<String>>? {
        if (importedPath in headerContext.crateNamespaces) {
            return importedPath to emptyList()
        }
        return headerContext.crateNamespaces.entries
            .mapNotNull { (k, v) ->
                val cleanNs = v.trim().removeSuffix(".")
                when {
                    cleanNs.isEmpty() || cleanNs == nsPrefix -> null
                    importedPath == cleanNs -> Triple(k, cleanNs.length, emptyList<String>())
                    importedPath.startsWith("$cleanNs.") -> {
                        val subSegs = importedPath.removePrefix("$cleanNs.").split('.').filter { it.isNotEmpty() }
                        Triple(k, cleanNs.length, subSegs)
                    }
                    importedPath.startsWith("$k.") -> {
                        val subSegs = importedPath.removePrefix("$k.").split('.').filter { it.isNotEmpty() }
                        Triple(k, k.length, subSegs)
                    }
                    else -> null
                }
            }.maxByOrNull { it.second }
            ?.let { it.first to it.third }
    }

    private fun evaluateSelfOrRelativeNavBranch(
        rawSegStrings: List<String>,
        firstRaw: String,
        nsPrefix: String?,
        inTypePosition: Boolean,
    ): BranchDecision {
        val firstModKt = rustModuleIdentToKotlin(firstRaw.replace('-', '_'))
        val curSub = emitter.currentSubpackageString()
        val childSubpkg = if (curSub.isEmpty()) firstModKt else "$curSub.$firstModKt"
        val isChildCrateMod =
            firstRaw !in NAV_KEYWORDS &&
                firstRaw !in headerContext.selfCrateAliases &&
                (
                    firstRaw in inspector.localExternalModuleNames ||
                        (
                            headerContext.isModuleRootSource &&
                                nsPrefix != null &&
                                firstModKt.isNotEmpty() &&
                                headerContext.crateSubpackageRawLeafNames[childSubpkg] == firstRaw
                        )
                )
        val isSelfOrRelNav =
            firstRaw in NAV_KEYWORDS || firstRaw in headerContext.selfCrateAliases || isChildCrateMod
        if (!isSelfOrRelNav) return BranchDecision.Continue
        val effectiveSegs = if (isChildCrateMod) listOf("self") + rawSegStrings else rawSegStrings
        val navCount =
            effectiveSegs
                .takeWhile {
                    val c = it.removePrefix("r#")
                    c in NAV_KEYWORDS || c in headerContext.selfCrateAliases
                }.size
                .coerceAtLeast(1)
        val afterNav = effectiveSegs.drop(navCount)
        if (afterNav.isEmpty()) return BranchDecision.Done(null)
        if (afterNav.size == 1) {
            val direct = emitter.resolveKotlinImportPath(effectiveSegs, forceTypeLeaf = inTypePosition)
            return BranchDecision.Done(direct?.let { it to emptyList() })
        }
        val hasCrateInventory =
            headerContext.crateSubpackageRawLeafNames.isNotEmpty() ||
                headerContext.crateSymbolSubpackages.isNotEmpty()
        for (trailingCount in 0..1) {
            if (afterNav.size <= trailingCount) continue
            val candidateSegs = effectiveSegs.dropLast(trailingCount)
            val midSegs = afterNav.dropLast(1 + trailingCount)
            val resolved =
                emitter.resolveKotlinImportPath(
                    candidateSegs,
                    forceTypeLeaf = inTypePosition || trailingCount > 0,
                ) ?: continue
            if (midSegs.isEmpty()) {
                return BranchDecision.Done(resolved to effectiveSegs.takeLast(trailingCount))
            }
            val allMidLower = midSegs.all { it.removePrefix("r#").firstOrNull()?.isLowerCase() == true }
            if (nsPrefix != null && resolved.startsWith("$nsPrefix.") && hasCrateInventory) {
                val afterNs = resolved.removePrefix("$nsPrefix.")
                val candSubpkg = afterNs.substringBeforeLast('.', missingDelimiterValue = "")
                val symSimple = afterNs.substringAfterLast('.')
                val expectedRawLeaf = midSegs.last().removePrefix("r#")
                if ((candSubpkg.isEmpty() && expectedRawLeaf in headerContext.crateFlatFileModules) ||
                    headerContext.crateSubpackageRawLeafNames[candSubpkg] == expectedRawLeaf ||
                    headerContext.crateSymbolSubpackages[symSimple]?.contains(candSubpkg) == true
                ) {
                    return BranchDecision.Done(resolved to rawSegStrings.takeLast(trailingCount))
                }
                if (allMidLower) {
                    val bestSub = emitter.resolveBestSubpackageForSymbol(symSimple)
                    if (bestSub != null) {
                        return BranchDecision.Done(qualifyInCrate(nsPrefix, bestSub, symSimple) to rawSegStrings.takeLast(trailingCount))
                    }
                }
            } else if (allMidLower) {
                return BranchDecision.Done(resolved to rawSegStrings.takeLast(trailingCount))
            }
        }
        return BranchDecision.Done(null)
    }

    private fun resolveLowercaseExternalCrateTarget(
        rawSegStrings: List<String>,
        firstRaw: String,
        inTypePosition: Boolean,
    ): Pair<String, List<String>>? {
        val firstAsType = rustTypeIdentToKotlin(firstRaw)
        if (firstAsType in inspector.declaredTypeNames ||
            firstRaw in inspector.declaredTypeNames ||
            firstAsType in emitter.activeTypeParamNames ||
            emitter.resolveImportedUppercaseTypeBinding(firstRaw) != null ||
            firstAsType in headerContext.crateSymbolSubpackages ||
            firstRaw in headerContext.crateSymbolSubpackages
        ) {
            return null
        }
        if (!inTypePosition && rawSegStrings.size == 2 && !isKnownExternalCrateRoot(firstRaw)) {
            return null
        }
        val midSegs = rawSegStrings.drop(1).dropLast(1)
        if (midSegs.all { it.removePrefix("r#").firstOrNull()?.isLowerCase() == true }) {
            val resolved = emitter.resolveKotlinImportPath(rawSegStrings, forceTypeLeaf = inTypePosition) ?: return null
            return resolved to emptyList()
        }
        val penultClean = if (rawSegStrings.size >= 3) rawSegStrings[rawSegStrings.size - 2].removePrefix("r#") else null
        if (!inTypePosition &&
            penultClean != null &&
            penultClean.firstOrNull()?.isUpperCase() == true &&
            rawSegStrings.drop(1).dropLast(2).all { it.removePrefix("r#").firstOrNull()?.isLowerCase() == true } &&
            isKnownExternalCrateRoot(firstRaw)
        ) {
            val resolved = emitter.resolveKotlinImportPath(rawSegStrings.dropLast(1), forceTypeLeaf = true) ?: return null
            return resolved to listOf(rawSegStrings.last())
        }
        return null
    }

    private fun isKnownExternalCrateRoot(firstRaw: String): Boolean {
        val modKt = rustModuleIdentToKotlin(firstRaw)
        return modKt in headerContext.crateNamespaces ||
            firstRaw in headerContext.crateNamespaces ||
            firstRaw in STDLIB_CRATE_ROOTS ||
            (
                !headerContext.isBatchCrateOutput &&
                    !headerContext.projectGroup.isNullOrBlank() &&
                    firstRaw !in STDLIB_UNIMPORTED_SUBMODULES
            )
    }

    companion object {
        private val INTRINSICS_OR_FFI = setOf("intrinsics", "ffi")
        private val NAV_KEYWORDS = setOf("crate", "super", "self")
    }
}

private class KotlinImportPathResolver(
    emitter: KotlinJ2KEmitter,
) : RustModulePathResolver(emitter) {
    fun resolve(
        rawSegsInput: List<String>,
        boundRawAlias: String? = null,
        isSelfImport: Boolean = false,
        forceTypeLeaf: Boolean = false,
    ): String? {
        val rewrittenSegs = emitter.rewriteCrateRootAliasAndReexportSegments(rawSegsInput)
        if (rewrittenSegs.isEmpty()) return null
        val segs = emitter.normalizeNetcLibcSegments(rewrittenSegs)
        val root = segs.first()
        if (root in PRIMITIVE_TYPE_NAMES) return null
        if (root.firstOrNull()?.isUpperCase() == true && segs.size > 1) {
            tryResolveUppercaseRootUsePath(segs, root, boundRawAlias, isSelfImport, forceTypeLeaf)?.let { return it }
        }
        if (root == "self") {
            return resolveSelfUsePath(segs.drop(1), boundRawAlias, isSelfImport, forceTypeLeaf)
        }
        if (root == "super") {
            return resolveSuperUsePath(segs, boundRawAlias, isSelfImport, forceTypeLeaf)
        }
        if (root in inspector.localExternalModuleNames) {
            return resolve(listOf("self") + segs, boundRawAlias, isSelfImport, forceTypeLeaf)
        }
        tryRedirectImplicitLocalOrCrateRoot(segs, root, boundRawAlias, isSelfImport, forceTypeLeaf)?.let { return it }
        if (root in IGNORED_USE_ROOTS) return null
        if (root in STDLIB_CRATE_ROOTS && segs.getOrNull(1) in STDLIB_MACRO_OR_PRIMITIVE_MODULES) return null
        if (!isSelfImport) {
            tryResolveEarlyStdlibOrFfiUsePath(segs, forceTypeLeaf)?.let { return it }
        }
        if (root == "libc" && FfiInteropCollector.resolveLibcNamespace(headerContext) == null) return null
        if (!isSelfImport && headerContext.rawAbiMode && segs.last().removePrefix("r#") == "c_void") return null
        val isCrateSelfRoot = root == "crate" || root in headerContext.selfCrateAliases
        val mapped =
            if (isCrateSelfRoot) {
                listOf(rustModuleIdentToKotlin(root)) +
                    mapRelativeUseTail(segs.drop(1), emptyList(), boundRawAlias, isSelfImport, forceTypeLeaf)
            } else {
                mapRelativeUseTail(segs, emptyList(), boundRawAlias, isSelfImport, forceTypeLeaf)
            }
        if (mapped.size < 2) return null
        return if (isCrateSelfRoot) {
            resolveCrateRootUsePath(segs, mapped)
        } else {
            resolveExternalCrateUsePath(segs, root, mapped, isSelfImport)
        }
    }

    private fun tryResolveUppercaseRootUsePath(
        segs: List<String>,
        root: String,
        boundRawAlias: String?,
        isSelfImport: Boolean,
        forceTypeLeaf: Boolean,
    ): String? {
        val rootTypeKt = rustTypeIdentToKotlin(root)
        if (rootTypeKt in inspector.declaredTypeNames) {
            return resolve(listOf("self") + segs, boundRawAlias, isSelfImport, forceTypeLeaf)
        }
        val scopedImportedPath = scopeBuilder.resolveModuleBinding(root)?.importedPath?.takeIf { '.' in it }
        if (scopedImportedPath != null) {
            return "$scopedImportedPath.${formatUppercaseUseTail(segs.drop(1), boundRawAlias, isSelfImport)}"
        }
        val importedPkg = emitter.importedSimpleNameToPkg[rootTypeKt]
        if (importedPkg != null) {
            return "$importedPkg.${formatUppercaseUseTail(segs, boundRawAlias, isSelfImport)}"
        }
        if (headerContext.crateSymbolSubpackages[rootTypeKt]?.isNotEmpty() == true) {
            val subPkg = emitter.resolveBestSubpackageForSymbol(rootTypeKt)
            val nsPrefix = emitter.currentNamespacePrefix()
            if (nsPrefix != null) {
                return qualifyInCrate(nsPrefix, subPkg.orEmpty(), formatUppercaseUseTail(segs, boundRawAlias, isSelfImport))
            }
        }
        val importedGlobs = mutableListOf<UseLeaf>()
        inspector.allUseItems.forEach { collectUseLeaves(it.tree, emptyList(), importedGlobs) }
        val packages =
            importedGlobs
                .filter { leaf ->
                    val firstChar = leaf.segments.firstOrNull()?.firstOrNull()
                    leaf.segments.lastOrNull() == "*" && firstChar?.isLowerCase() == true
                }.mapNotNull { resolve(it.segments, isSelfImport = it.isSelfImport) }
                .map { it.removeSuffix(".*") }
                .distinct()
        return if (packages.size == 1) {
            "${packages.single()}.${formatUppercaseUseTail(segs, boundRawAlias, isSelfImport)}"
        } else {
            null
        }
    }

    private fun formatUppercaseUseTail(
        tailSegs: List<String>,
        boundRawAlias: String?,
        isSelfImport: Boolean,
    ): String =
        tailSegs.joinToString(".") { seg ->
            val cleanSeg = seg.removePrefix("r#")
            when {
                cleanSeg == "*" -> "*"
                emitter.isImportedScreamingConstLeaf(cleanSeg, boundRawAlias, isSelfImport) -> rustConstIdentToKotlin(cleanSeg)
                else -> rustTypeIdentToKotlin(cleanSeg)
            }
        }

    private fun isBlockedNonEnumCrateGlob(
        relSegs: List<String>,
        baseSegs: List<String>,
    ): Boolean {
        if (relSegs.last() != "*" || headerContext.crateSymbolSubpackages.isEmpty()) return false
        val isEnumGlob =
            relSegs.size >= 2 &&
                isIntermediateTypeSegment(relSegs[relSegs.lastIndex - 1], relSegs.lastIndex - 1, relSegs, baseSegs)
        return !isEnumGlob
    }

    private fun tryResolveRelativeSubpackageLeaf(
        nsPrefix: String,
        relSegs: List<String>,
        mappedTail: List<String>,
        baseSegs: List<String>,
        allowSingleSuperFallback: Boolean,
    ): String? {
        resolveIntermediateTypeUsePath(nsPrefix, relSegs, mappedTail, baseSegs)?.let { return it }
        val leafSym = mappedTail.last()
        if (leafSym == "*" || headerContext.crateSymbolSubpackages.isEmpty()) return null
        val explicitModule = (baseSegs + mappedTail.dropLast(1)).joinToString(".")
        val candidates = headerContext.crateSymbolSubpackages[leafSym]
        if (candidates != null && explicitModule !in candidates) {
            resolveFlattenedOrChildSubpackage(explicitModule, candidates)?.let {
                return qualifyInCrate(nsPrefix, it, leafSym)
            }
            if (allowSingleSuperFallback && relSegs.size == 1) {
                emitter.resolveExternalDepReexportPkg(explicitModule, leafSym, nsPrefix)?.let { return it }
                emitter.resolveBestSubpackageForSymbol(leafSym)?.let { return qualifyInCrate(nsPrefix, it, leafSym) }
            }
        } else if (allowSingleSuperFallback && candidates == null && relSegs.size == 1) {
            emitter.resolveExternalDepReexportPkg(explicitModule, leafSym, nsPrefix)?.let { return it }
        }
        return null
    }

    private fun computeSuperBaseSegs(
        curSubSegs: List<String>,
        superCount: Int,
        firstRem: String,
    ): List<String> {
        var baseSegs = if (superCount >= curSubSegs.size) emptyList() else curSubSegs.dropLast(superCount)
        if (headerContext.includedSubdirModuleMap.isEmpty()) return baseSegs
        if (baseSegs.size == 1) {
            val includedRoots = headerContext.includedSubdirModuleMap.values.mapTo(hashSetOf(), ::rustModuleIdentToKotlin)
            val expectedRoot = headerContext.includedSubdirModuleMap[firstRem]?.let(::rustModuleIdentToKotlin)
            if (baseSegs[0] in includedRoots && expectedRoot != baseSegs[0]) {
                baseSegs = emptyList()
            }
        }
        if (baseSegs.isEmpty()) {
            headerContext.includedSubdirModuleMap[firstRem]?.let { baseSegs = listOf(rustModuleIdentToKotlin(it)) }
        }
        return baseSegs
    }

    private fun resolveSelfUsePath(
        afterSelf: List<String>,
        boundRawAlias: String?,
        isSelfImport: Boolean,
        forceTypeLeaf: Boolean,
    ): String? {
        if (afterSelf.isEmpty()) return null
        val curPkg = cleanDotPrefix(headerContext.packageName) ?: return null
        val curSubpkg = emitter.currentSubpackageString()
        val curSubSegs = if (curSubpkg.isEmpty()) emptyList() else curSubpkg.split('.')
        if (isBlockedNonEnumCrateGlob(afterSelf, curSubSegs)) return null
        val mappedTail = mapRelativeUseTail(afterSelf, curSubSegs, boundRawAlias, isSelfImport, forceTypeLeaf)
        val nsPrefix = emitter.currentNamespacePrefix()
        if (nsPrefix != null) {
            val resolved =
                tryResolveRelativeSubpackageLeaf(nsPrefix, afterSelf, mappedTail, curSubSegs, allowSingleSuperFallback = false)
            if (resolved != null) return resolved
        }
        return "$curPkg.${mappedTail.joinToString(".")}"
    }

    private fun resolveSuperUsePath(
        segs: List<String>,
        boundRawAlias: String?,
        isSelfImport: Boolean,
        forceTypeLeaf: Boolean,
    ): String? {
        val superCount = segs.takeWhile { it == "super" }.size
        val rem = segs.drop(superCount)
        if (rem.isEmpty()) return null
        val nsPrefix = emitter.currentNamespacePrefix() ?: return null
        val curSubpkg = emitter.currentSubpackageString()
        val curSubSegs = if (curSubpkg.isEmpty()) emptyList() else curSubpkg.split('.')
        val baseSegs = computeSuperBaseSegs(curSubSegs, superCount, rem.first())
        if (isBlockedNonEnumCrateGlob(rem, baseSegs)) return null
        val mappedRem = mapRelativeUseTail(rem, baseSegs, boundRawAlias, isSelfImport, forceTypeLeaf)
        tryResolveRelativeSubpackageLeaf(nsPrefix, rem, mappedRem, baseSegs, allowSingleSuperFallback = true)?.let { return it }
        if (!isSelfImport && !headerContext.rawAbiMode && isInRustLangCrate()) {
            val resolvedModSegs = baseSegs + rem.dropLast(1).map { rustModuleIdentToKotlin(it.removePrefix("r#")) }
            if (resolvedModSegs == OS_RAW_SEGS || resolvedModSegs == FFI_SEGS) {
                tryResolveFfiLeafSymbol(rem.last().removePrefix("r#"))?.let { return it }
            }
        }
        if (baseSegs.isEmpty() && rem[0].removePrefix("r#") in headerContext.crateFlatFileModules) {
            return if (rem.size >= 2) "$nsPrefix.${mappedRem.drop(1).joinToString(".")}" else nsPrefix
        }
        return "$nsPrefix.${(baseSegs + mappedRem).joinToString(".")}"
    }

    private fun tryRedirectImplicitLocalOrCrateRoot(
        segs: List<String>,
        root: String,
        boundRawAlias: String?,
        isSelfImport: Boolean,
        forceTypeLeaf: Boolean,
    ): String? {
        if (root in IGNORED_USE_ROOTS ||
            root in STDLIB_CRATE_ROOTS ||
            root == "crate" ||
            root in headerContext.selfCrateAliases ||
            root in PROC_OR_SERDE_DERIVE_CRATES
        ) {
            return null
        }
        val cleanRoot = root.removePrefix("r#")
        val rootCrateKey = rustModuleIdentToKotlin(cleanRoot)
        val isConfiguredExtCrate =
            cleanRoot in headerContext.crateNamespaces ||
                rootCrateKey in headerContext.crateNamespaces ||
                rootCrateKey in headerContext.externalCrateSymbolPackages
        if (isConfiguredExtCrate) return null
        if (segs.size == 1 && !isSelfImport) {
            val leafKt = mapImportLeafIdent(cleanRoot, boundRawAlias, isSelfImport, forceTypeLeaf)
            val isKnownLocalCrateSymbol =
                leafKt in headerContext.crateSymbolSubpackages ||
                    cleanRoot in inspector.declaredTypeNames ||
                    rustTypeIdentToKotlin(cleanRoot) in inspector.declaredTypeNames ||
                    cleanRoot in inspector.declaredTopLevelFnRawNames ||
                    cleanRoot in inspector.declaredConstNames
            return if (isKnownLocalCrateSymbol) {
                resolve(listOf("crate") + segs, boundRawAlias, isSelfImport, forceTypeLeaf)
            } else {
                null
            }
        }
        if (segs.size >= 2) {
            val targetModPkg = segs.dropLast(1).joinToString(".") { rustModuleIdentToKotlin(it.removePrefix("r#")) }
            val cleanLast = segs.last().removePrefix("r#")
            val verifiedInCrateSymbols = isVerifiedCrateSubmoduleSymbol(targetModPkg, cleanLast)
            val rootKnownCrateModule =
                verifiedInCrateSymbols ||
                    cleanRoot in headerContext.includedSubdirModuleMap ||
                    cleanRoot in headerContext.crateSubpackageRawLeafNames.values ||
                    rootCrateKey in headerContext.crateSubpackageRawLeafNames.keys
            if (rootKnownCrateModule) {
                return resolve(listOf("crate") + segs, boundRawAlias, isSelfImport, forceTypeLeaf)
            }
            if (cleanRoot in inspector.localInlineModuleNames) {
                return resolve(listOf("self") + segs.drop(1), boundRawAlias, isSelfImport, forceTypeLeaf)
            }
        }
        return null
    }

    private fun isVerifiedCrateSubmoduleSymbol(targetModPkg: String, cleanLast: String): Boolean {
        val symSubpkgMap = headerContext.crateSymbolSubpackages
        if (symSubpkgMap.isEmpty()) return false
        if (cleanLast == "*") {
            return symSubpkgMap.values.any { pkgs -> pkgs.any { it == targetModPkg || it.startsWith("$targetModPkg.") } }
        }
        val candidateLeafNames =
            listOf(
                rustTypeIdentToKotlin(cleanLast),
                rustFunctionIdentToKotlin(cleanLast),
                rustConstIdentToKotlin(cleanLast),
                rustValueIdentToKotlin(cleanLast),
            ).distinct()
        return candidateLeafNames.any { sym ->
            val pkgs = symSubpkgMap[sym].orEmpty()
            pkgs.any { it == targetModPkg || it.startsWith("$targetModPkg.") } ||
                headerContext.crateStructFieldTypes.keys.any { k ->
                    k == "$targetModPkg.$sym" || k.startsWith("$targetModPkg.$sym.")
                } ||
                headerContext.crateFnReturnTypes.keys.any { k ->
                    k == "$targetModPkg.$sym" || k.startsWith("$targetModPkg.$sym.")
                }
        }
    }

    private fun tryResolveEarlyStdlibOrFfiUsePath(
        segs: List<String>,
        forceTypeLeaf: Boolean,
    ): String? {
        val cleanSegs = segs.map { it.removePrefix("r#") }
        if (!headerContext.rawAbiMode &&
            cleanSegs.size == 2 &&
            cleanSegs[0] == "libc" &&
            cleanSegs[1] in CoreIntrinsicLowering.STDLIB_CORE_FFI_SYMBOLS
        ) {
            emitter.resolveStdlibFfiSymbolImport(cleanSegs[1])?.let { return it }
        }
        if (cleanSegs.size == 3) {
            tryResolveCoreMemIntrinsic(cleanSegs[0], cleanSegs[1], cleanSegs[2], requireCoreWhenCrateRoot = true)?.let {
                return it
            }
        }
        if (isCoreSyncAtomicPrefix(cleanSegs, cleanSegs[0], cleanSegs.getOrNull(1), cleanSegs.getOrNull(2))) {
            val coreNs = CoreIntrinsicLowering.resolveCoreNamespace(headerContext)
            if (cleanSegs.size == 5 && cleanSegs[3] == "Ordering") {
                return "$coreNs.sync.atomic.Ordering.${rustTypeIdentToKotlin(cleanSegs[4])}"
            }
            if (cleanSegs.size == 4 && cleanSegs[3] != "*") {
                val s = cleanSegs[3]
                val ktSym =
                    when {
                        forceTypeLeaf || s.firstOrNull()?.isUpperCase() == true -> rustTypeIdentToKotlin(s)
                        emitter.isKnownFunctionLeafIdent(s) -> rustFunctionIdentToKotlin(s)
                        else -> rustValueIdentToKotlin(s)
                    }
                return "$coreNs.sync.atomic.$ktSym"
            }
        }
        if (headerContext.rawAbiMode) return null
        val isStdOrCrateFfi =
            (cleanSegs.size == 3 && (cleanSegs[0] == "core" || cleanSegs[0] == "std") && cleanSegs[1] == "ffi") ||
                (cleanSegs.size == 4 && cleanSegs[0] == "std" && cleanSegs[1] == "os" && cleanSegs[2] == "raw") ||
                (cleanSegs.size == 3 && cleanSegs[0] == "crate" && cleanSegs[1] == "ffi" && isInRustLangCrate()) ||
                (
                    cleanSegs.size == 4 &&
                        cleanSegs[0] == "crate" &&
                        cleanSegs[1] == "os" &&
                        cleanSegs[2] == "raw" &&
                        headerContext.packageName?.startsWith("io.github.kotlinmania.rustlang.std") == true
                )
        if (isStdOrCrateFfi) {
            tryResolveFfiLeafSymbol(cleanSegs.last())?.let { return it }
        }
        if (cleanSegs.size == 3 && cleanSegs[0] == "alloc" && cleanSegs[1] == "ffi" && cleanSegs[2] == "CString") {
            emitter.resolveStdlibFfiSymbolImport("CString", isAllocCString = true)?.let { return it }
        }
        if (cleanSegs.size == 3) {
            tryResolveStdlibRcOrArc(cleanSegs[0], cleanSegs[1], cleanSegs[2])?.let { return it }
        }
        return null
    }

    private fun resolveCrateRootUsePath(
        segs: List<String>,
        mapped: List<String>,
    ): String? {
        val nsPrefix = emitter.currentNamespacePrefix()
        val curPkg = headerContext.packageName.orEmpty()
        if (nsPrefix != null && ".detect.os." in curPkg && mapped.last() == "Feature" && "detect" in segs) {
            val osTailSegs = curPkg.substringAfter(".detect.os.").split('.').filter { it.isNotEmpty() }
            val archSub = osTailSegs.firstOrNull { cand -> "detect.arch.$cand" in headerContext.crateSubpackageRawLeafNames }
            if (archSub != null) return "$nsPrefix.detect.arch.$archSub.Feature"
        }
        val afterRoot = segs.drop(1)
        val mappedTail = mapped.drop(1)
        val leafSym = mappedTail.last()
        if (afterRoot.last() == "*" && headerContext.crateSymbolSubpackages.isNotEmpty()) {
            val isRootOrPreludeGlob = afterRoot.size == 1 || (afterRoot.size == 2 && afterRoot[0] == "prelude")
            if (isRootOrPreludeGlob && nsPrefix != null && headerContext.packageName != nsPrefix) {
                return "$nsPrefix.*"
            }
            if (afterRoot.size >= 2 &&
                isIntermediateTypeSegment(afterRoot[afterRoot.lastIndex - 1], afterRoot.lastIndex - 1, afterRoot)
            ) {
                val enumKt = rustTypeIdentToKotlin(afterRoot[afterRoot.lastIndex - 1])
                val sub = emitter.resolveBestSubpackageForSymbol(enumKt)
                if (nsPrefix != null && sub != null) {
                    return qualifyInCrate(nsPrefix, sub, "$enumKt.*")
                }
            }
            return null
        }
        if (afterRoot.size == 1 && leafSym != "*" && nsPrefix != null && headerContext.crateSymbolSubpackages.isNotEmpty()) {
            resolveSingleSegmentCrateRootLeaf(nsPrefix, afterRoot[0], leafSym)?.let { return it }
        }
        if (nsPrefix != null) {
            resolveIntermediateTypeUsePath(nsPrefix, afterRoot, mappedTail)?.let { return it }
            if (afterRoot.size >= 2 && leafSym != "*") {
                resolveMultiSegmentCrateRootLeaf(nsPrefix, afterRoot, mappedTail, leafSym)?.let { return it }
            }
        }
        val includedPrefix =
            headerContext.includedSubdirModuleMap[afterRoot.first()]
                ?.let(::rustModuleIdentToKotlin)
                ?.takeIf { it.isNotEmpty() }
        val tail =
            when {
                segs.size >= 3 && segs[1] in headerContext.crateFlatFileModules -> leafSym
                includedPrefix != null -> "$includedPrefix.${mappedTail.joinToString(".")}"
                else -> mappedTail.joinToString(".")
            }
        return if (nsPrefix != null) "$nsPrefix.$tail" else tail
    }

    private fun resolveSingleSegmentCrateRootLeaf(
        nsPrefix: String,
        rawSeg: String,
        leafSym: String,
    ): String? {
        val rawLeaf = rawSeg.removePrefix("r#")
        val isLowerIdent = rawLeaf.firstOrNull()?.isLowerCase() == true
        if (isLowerIdent && !emitter.isKnownFunctionLeafIdent(rawSeg)) {
            val leafMod = rustModuleIdentToKotlin(rawLeaf.replace('-', '_'))
            if (leafMod in headerContext.crateSubpackageRawLeafNames) {
                return "$nsPrefix.$leafMod"
            }
        }
        if (!isLowerIdent || emitter.isKnownFunctionLeafIdent(rawSeg)) {
            emitter.resolveBestSubpackageForSymbol(leafSym)?.let { return qualifyInCrate(nsPrefix, it, leafSym) }
        }
        if (isLowerIdent) {
            val typeKt = rustTypeIdentToKotlin(rawSeg)
            emitter.resolveBestSubpackageForSymbol(typeKt)?.let { return qualifyInCrate(nsPrefix, it, typeKt) }
            emitter.resolveBestSubpackageForSymbol(leafSym)?.let { return qualifyInCrate(nsPrefix, it, leafSym) }
        }
        if (leafSym !in inspector.declaredTypeNames &&
            leafSym !in inspector.declaredConstNames.values &&
            !emitter.isDeclaredTopLevelFnKotlinName(leafSym)
        ) {
            for (extCrate in STDLIB_SEARCH_CRATES) {
                val extPkgs = headerContext.externalCrateSymbolPackages[extCrate]?.get(leafSym)
                if (!extPkgs.isNullOrEmpty()) {
                    return "${extPkgs.first()}.$leafSym"
                }
            }
        }
        return null
    }

    private fun resolveMultiSegmentCrateRootLeaf(
        nsPrefix: String,
        afterRoot: List<String>,
        mappedTail: List<String>,
        leafSym: String,
    ): String? {
        val explicitModule = mappedTail.dropLast(1).joinToString(".")
        val rawLastClean = afterRoot.last().removePrefix("r#")
        val isLowerModuleCandidate =
            rawLastClean.firstOrNull()?.isLowerCase() == true && !emitter.isKnownFunctionLeafIdent(rawLastClean)
        val expectedLeafKt = if (isLowerModuleCandidate) rustModuleIdentToKotlin(rawLastClean.replace('-', '_')) else ""
        val exactChild = if (explicitModule.isEmpty()) expectedLeafKt else "$explicitModule.$expectedLeafKt"
        if (isLowerModuleCandidate && exactChild in headerContext.crateSubpackageRawLeafNames) {
            return "$nsPrefix.$exactChild"
        }
        val normalizedExplicitModule =
            if (explicitModule == "os.wasi.ffi" || explicitModule.startsWith("os.wasi.ffi.")) {
                explicitModule.replaceFirst("os.wasi.ffi", "os.unix.ffi")
            } else {
                explicitModule
            }
        val candidates = headerContext.crateSymbolSubpackages[leafSym]
        if (!candidates.isNullOrEmpty()) {
            val sub =
                when {
                    explicitModule in candidates -> explicitModule
                    normalizedExplicitModule in candidates -> normalizedExplicitModule
                    else ->
                        emitter.resolveFlattenedInlineModuleSubpackage(explicitModule, candidates)
                            ?: emitter.resolveFlattenedInlineModuleSubpackage(normalizedExplicitModule, candidates)
                            ?: emitter.resolveBestSubpackageAmong(candidates.filter { it.startsWith("$explicitModule.") })
                            ?: emitter.resolveBestSubpackageAmong(candidates.filter { it.startsWith("$normalizedExplicitModule.") })
                }
            if (sub != null) return qualifyInCrate(nsPrefix, sub, leafSym)
        }
        emitter.resolveExternalDepReexportPkg(explicitModule, leafSym, nsPrefix)?.let { return it }
        if (isLowerModuleCandidate && exactChild !in headerContext.crateSubpackageRawLeafNames) {
            val matchingModSubpkgs =
                headerContext.crateSubpackageRawLeafNames.entries
                    .filter { (subKey, rawLeaf) ->
                        rawLeaf == rawLastClean && (explicitModule.isEmpty() || subKey.startsWith("$explicitModule."))
                    }.map { it.key }
            emitter.resolveBestSubpackageAmong(matchingModSubpkgs)?.let { return "$nsPrefix.$it" }
        }
        if (!candidates.isNullOrEmpty()) {
            emitter.resolveBestSubpackageForSymbol(leafSym)?.let { return qualifyInCrate(nsPrefix, it, leafSym) }
        }
        return null
    }

    private fun resolveExternalCrateUsePath(
        segs: List<String>,
        root: String,
        mapped: List<String>,
        isSelfImport: Boolean,
    ): String? {
        val crateKey = resolveEffectiveExternalCrateKey(root, mapped.first())
        val customCrateNs = cleanConfiguredCrateNamespace(crateKey)
        val flattenSubmodules =
            headerContext.projectGroup == "io.github.kotlinmania" &&
                crateKey in FLAT_KOTLINMANIA_CRATES &&
                mapped.size > 2
        val effectiveTail = if (flattenSubmodules) mapped.last() else mapped.drop(1).joinToString(".")
        if (customCrateNs != null) {
            return resolveConfiguredExternalCrateUsePath(
                segs = segs,
                root = root,
                crateKey = crateKey,
                customCrateNs = customCrateNs,
                mapped = mapped,
                effectiveTail = effectiveTail,
                isSelfImport = isSelfImport,
            )
        }
        val joined = "$crateKey.$effectiveTail"
        val groupPrefix = cleanDotPrefix(headerContext.projectGroup)
        if (groupPrefix != null && root in STDLIB_CRATE_ROOTS) {
            val coreNs = CoreIntrinsicLowering.resolveCoreNamespace(headerContext)
            return when {
                mapped.size == 3 && mapped[1] == "cell" && mapped[2] == "OnceCell" -> "$coreNs.cell.once.OnceCell"
                mapped.size == 3 && mapped[1] == "cell" && mapped[2] in STDLIB_CELL_TYPES -> "$coreNs.cell.${mapped[2]}"
                else -> null
            }
        }
        if ((
                headerContext.isBatchCrateOutput ||
                    headerContext.portLintDirective == "tests" ||
                    headerContext.testClassName != null
            ) &&
            root !in STDLIB_CRATE_ROOTS &&
            root !in headerContext.crateNamespaces &&
            crateKey !in headerContext.crateNamespaces
        ) {
            return null
        }
        return if (groupPrefix != null && !joined.startsWith("$groupPrefix.")) "$groupPrefix.$joined" else joined
    }

    private fun resolveEffectiveExternalCrateKey(root: String, rawCrateKey: String): String {
        val pkg = headerContext.packageName
        val isSelfProcMacro2 =
            headerContext.projectNamespace == "io.github.kotlinmania.procmacro2" ||
                pkg == "io.github.kotlinmania.procmacro2" ||
                pkg?.startsWith("io.github.kotlinmania.procmacro2.") == true
        if (headerContext.projectGroup != "io.github.kotlinmania") return rawCrateKey
        return when {
            root == "proc_macro" && !isSelfProcMacro2 && headerContext.crateNamespaces["procmacro"] == null -> "procmacro2"
            root == "serde_derive" && headerContext.crateNamespaces["serdederive"] == null -> "serderive"
            root == "serde_derive_internals" && headerContext.crateNamespaces["serdederiveinternals"] == null ->
                "serderive.internals"
            else -> rawCrateKey
        }
    }

    private fun resolveConfiguredExternalCrateUsePath(
        segs: List<String>,
        root: String,
        crateKey: String,
        customCrateNs: String,
        mapped: List<String>,
        effectiveTail: String,
        isSelfImport: Boolean,
    ): String? {
        val candidateCrates =
            when (crateKey) {
                "std" -> if (isSelfImport) listOf("alloc", "core") else listOf("std", "alloc", "core")
                "alloc" -> if (isSelfImport) listOf("core") else listOf("alloc", "core")
                else -> if (isSelfImport) emptyList() else listOf(crateKey)
            }
        val anyCandidateExtPkgs = candidateCrates.any { headerContext.externalCrateSymbolPackages[it] != null }
        val secondSeg = segs.getOrNull(1)?.removePrefix("r#")
        if (root in STDLIB_CRATE_ROOTS && secondSeg in STDLIB_PRIMITIVE_OR_PRELUDE_SECOND_SEGS) {
            return null
        }
        if (!anyCandidateExtPkgs && root in STDLIB_CRATE_ROOTS && secondSeg in STDLIB_UNINDEXED_SKIP_SECOND_SEGS) {
            return null
        }
        val mappedLeaf = mapped.lastOrNull()
        if (anyCandidateExtPkgs && mappedLeaf != null && mappedLeaf != "*") {
            resolveIndexedExternalSymbol(candidateCrates, mapped, segs.last(), mappedLeaf)?.let { return it }
            if (root in STDLIB_CRATE_ROOTS) return null
        } else if (root in STDLIB_CRATE_ROOTS && mappedLeaf == "*") {
            if (anyCandidateExtPkgs && mapped.size >= 4) {
                resolveExternalPenultOwnerType(candidateCrates, mapped, mappedLeaf)?.let { return it }
            }
            val subTail = mapped.drop(1).dropLast(1).joinToString(".")
            val hasExternalWildcardPkg =
                anyCandidateExtPkgs &&
                    mapped.size >= 3 &&
                    candidateCrates.any { searchCrate ->
                        val searchNs = cleanConfiguredCrateNamespace(searchCrate) ?: return@any false
                        val searchExtPkgs = headerContext.externalCrateSymbolPackages[searchCrate] ?: return@any false
                        val candidateSubPkg = if (subTail.isEmpty()) searchNs else "$searchNs.$subTail"
                        searchExtPkgs.values.any { pkgs -> candidateSubPkg in pkgs }
                    }
            if (!hasExternalWildcardPkg && (mapped.size != 3 || mapped[1] !in STDLIB_ALLOWED_WILDCARD_SUBMODULES)) {
                return null
            }
        }
        if (root in STDLIB_CRATE_ROOTS) {
            val coreNs = CoreIntrinsicLowering.resolveCoreNamespace(headerContext)
            if (mapped.size == 3 && mapped[1] == "mem" && mapped[2] == "MaybeUninit") {
                return "$coreNs.mem.maybeuninit.MaybeUninit"
            }
            if (mapped.size == 3 && mapped[1] == "cell" && mapped[2] == "OnceCell") {
                return "$coreNs.cell.once.OnceCell"
            }
        }
        return "$customCrateNs.$effectiveTail"
    }

    private fun resolveIndexedExternalSymbol(
        candidateCrates: List<String>,
        mapped: List<String>,
        lastRaw: String,
        mappedLeaf: String,
    ): String? {
        val midPath = mapped.drop(1).dropLast(1).joinToString(".")
        for (searchCrate in candidateCrates) {
            val searchNs = cleanConfiguredCrateNamespace(searchCrate) ?: continue
            val candidates = headerContext.externalCrateSymbolPackages[searchCrate]?.get(mappedLeaf).orEmpty()
            if (candidates.isNotEmpty()) {
                matchExternalSymbolPackage(searchCrate, searchNs, midPath, candidates)?.let { return "$it.$mappedLeaf" }
            }
        }
        val cleanLastRaw = lastRaw.removePrefix("r#")
        if (cleanLastRaw.firstOrNull()?.isLowerCase() == true && !emitter.isKnownFunctionLeafIdent(cleanLastRaw)) {
            val subTail = mapped.drop(1).joinToString(".")
            for (searchCrate in candidateCrates) {
                val searchNs = cleanConfiguredCrateNamespace(searchCrate) ?: continue
                val searchExtPkgs = headerContext.externalCrateSymbolPackages[searchCrate] ?: continue
                val candidateSubPkg = if (subTail.isEmpty()) searchNs else "$searchNs.$subTail"
                if (searchExtPkgs.values.any { pkgs -> pkgs.any { it == candidateSubPkg || it.startsWith("$candidateSubPkg.") } }) {
                    return candidateSubPkg
                }
            }
        }
        for (searchCrate in candidateCrates) {
            val candidates = headerContext.externalCrateSymbolPackages[searchCrate]?.get(mappedLeaf)
            if (!candidates.isNullOrEmpty()) return "${candidates.first()}.$mappedLeaf"
        }
        return resolveExternalPenultOwnerType(candidateCrates, mapped, mappedLeaf)
    }

    private fun resolveExternalPenultOwnerType(
        candidateCrates: List<String>,
        mapped: List<String>,
        mappedLeaf: String,
    ): String? {
        if (mapped.size < 3 || mapped[mapped.size - 2].firstOrNull()?.isUpperCase() != true) return null
        val penultType = mapped[mapped.size - 2]
        val ownerMidPath = mapped.drop(1).dropLast(2).joinToString(".")
        for (searchCrate in candidateCrates) {
            val searchNs = cleanConfiguredCrateNamespace(searchCrate) ?: continue
            val ownerCandidates = headerContext.externalCrateSymbolPackages[searchCrate]?.get(penultType) ?: continue
            val resolvedOwnerPkg =
                matchExternalSymbolPackage(searchCrate, searchNs, ownerMidPath, ownerCandidates, expandLibmMath = false)
                    ?: ownerCandidates.firstOrNull()
            if (resolvedOwnerPkg != null) return "$resolvedOwnerPkg.$penultType.$mappedLeaf"
        }
        return null
    }

    companion object {
        private val OS_RAW_SEGS = listOf("os", "raw")
        private val FFI_SEGS = listOf("ffi")
        private val PROC_OR_SERDE_DERIVE_CRATES = wordSet("proc_macro serde_derive serde_derive_internals")
        private val STDLIB_SEARCH_CRATES = listOf("core", "alloc", "std")
        private val STDLIB_CELL_TYPES: Set<String> get() = activeRules().words("stdlibCellTypes")
        private val STDLIB_PRIMITIVE_OR_PRELUDE_SECOND_SEGS: Set<String>
            get() = activeRules().words("stdlibPrimitiveOrPreludeSecondSegs")
        private val STDLIB_UNINDEXED_SKIP_SECOND_SEGS: Set<String>
            get() = activeRules().words("stdlibUnindexedSkipSecondSegs")
        private val STDLIB_ALLOWED_WILDCARD_SUBMODULES: Set<String>
            get() = activeRules().words("stdlibAllowedWildcardSubmodules")
    }
}

internal fun KotlinJ2KEmitter.renderItemUse(
    item: Item.Use,
    indent: String,
    bodyTokensForPubUseFilter: KotlinCodeTokenIndex? = null,
): String = UseItemImportEmitter(this).render(item, indent, bodyTokensForPubUseFilter)

internal fun collectUseLeaves(
    tree: UseTree,
    prefix: List<String>,
    out: MutableList<UseLeaf>,
) {
    when (tree) {
        is UseTree.Path -> tree.tree?.let { collectUseLeaves(it, prefix + tree.ident.toString(), out) }
        is UseTree.Name -> {
            val seg = tree.ident.toString()
            val alias =
                tree.rename
                    ?.ident
                    ?.cleanIdent()
                    ?.takeIf { it != "_" }
            if (seg != "self") {
                out.add(UseLeaf(segments = prefix + seg, alias = alias, isSelfImport = false))
            } else if (prefix.isNotEmpty()) {
                out.add(UseLeaf(segments = prefix, alias = alias, isSelfImport = true))
            }
        }
        is UseTree.Glob -> out.add(UseLeaf(segments = prefix + "*", alias = null, isSelfImport = false))
        is UseTree.Group -> tree.items.toList().forEach { collectUseLeaves(it, prefix, out) }
    }
}

internal fun KotlinJ2KEmitter.collectInlineModUses(modItem: Item.Mod, out: MutableList<Item.Use>) {
    val content = modItem.content as? ModContent.Inline ?: return
    for (sub in content.items) {
        if (itemHasDocOnlyCfg(sub)) continue
        when (sub) {
            is Item.Use -> out += sub
            is Item.Mod -> collectInlineModUses(sub, out)
            else -> {}
        }
    }
}

/**
 * Classifies `use` leaves, registers module/symbol bindings in lexical scope, and renders
 * filtered Kotlin `import` directives from Rust `Item.Use` trees.
 */
private class UseItemImportEmitter(
    private val emitter: KotlinJ2KEmitter,
) {
    private val headerContext get() = emitter.headerContext
    private val inspector get() = emitter.inspector
    private val scopeBuilder get() = emitter.scopeBuilder

    fun isImportedTypeLeaf(lastSeg: String, boundRawAlias: String?, isSelfImport: Boolean): Boolean {
        if (isSelfImport || lastSeg == "*") return false
        val cleanLast = lastSeg.removePrefix("r#").trim()
        if (!headerContext.rawAbiMode && (cleanLast in CoreIntrinsicLowering.STDLIB_CORE_FFI_SYMBOLS || cleanLast == "CString")) {
            return true
        }
        val effectiveBound = (boundRawAlias ?: lastSeg).removePrefix("r#").trim()
        val effectiveBoundModKt = rustModuleIdentToKotlin(effectiveBound.replace('-', '_'))
        val isUnaliasedLower = boundRawAlias == null && effectiveBound.firstOrNull()?.isLowerCase() == true
        if (isUnaliasedLower &&
            (
                effectiveBoundModKt in headerContext.crateNamespaces ||
                    effectiveBoundModKt in headerContext.externalCrateSymbolPackages ||
                    effectiveBound in headerContext.externalCrateSymbolPackages
            )
        ) {
            return false
        }
        if (effectiveBound in inspector.referencedUnqualifiedTypeIdents) return true
        if (isUnaliasedLower &&
            (
                effectiveBoundModKt in headerContext.crateSubpackageRawLeafNames ||
                    effectiveBound in headerContext.crateSubpackageRawLeafNames.values
            )
        ) {
            return false
        }
        if (boundRawAlias != null && boundRawAlias.first().isUpperCase() && boundRawAlias.any(Char::isLowerCase)) {
            return true
        }
        val typeKt = rustTypeIdentToKotlin(effectiveBound)
        val fnKt = rustFunctionIdentToKotlin(effectiveBound)
        if (typeKt in headerContext.crateSymbolSubpackages && fnKt !in headerContext.crateSymbolSubpackages) return true
        return headerContext.externalCrateSymbolPackages.values.any { symMap ->
            typeKt in symMap && (fnKt == typeKt || fnKt !in symMap)
        }
    }

    fun isKnownFunctionLeafIdent(rawSeg: String): Boolean {
        val cleaned = rawSeg.removePrefix("r#").trim()
        if (cleaned.isEmpty() || cleaned == "*" || cleaned.first().isUpperCase()) return false
        if (cleaned in inspector.declaredTopLevelFnRawNames) return true
        if (cleaned in inspector.declaredConstNames || cleaned in inspector.declaredTypeNames) return false
        val fnKt = rustFunctionIdentToKotlin(cleaned)
        if (fnKt in headerContext.crateSymbolSubpackages && cleaned !in headerContext.crateSymbolSubpackages) return true
        return headerContext.externalCrateSymbolPackages.values.any { symMap -> fnKt in symMap && cleaned !in symMap }
    }

    fun isImportedScreamingConstLeaf(lastSeg: String, boundRawAlias: String?, isSelfImport: Boolean): Boolean {
        if (isSelfImport || lastSeg == "*") return false
        val cleaned = lastSeg.removePrefix("r#").trim()
        if (cleaned in FILE_OR_DIR_NAMES || isKnownFunctionLeafIdent(lastSeg)) return false
        val effectiveBound = (boundRawAlias ?: lastSeg).removePrefix("r#").trim()
        if (effectiveBound in inspector.referencedUnqualifiedTypeIdents ||
            effectiveBound in inspector.referencedPathPrefixTypeIdents
        ) {
            return false
        }
        val trimmedUnderscores = cleaned.trim('_')
        return trimmedUnderscores.length >= 2 && KotlinNamingPolicy.Default.isScreamingSnakeCase(trimmedUnderscores)
    }

    fun registerInScope(item: Item.Use) {
        val leaves = mutableListOf<UseLeaf>()
        collectUseLeaves(item.tree, emptyList(), leaves)
        for (leaf in leaves) {
            val lastSeg = leaf.segments.lastOrNull() ?: continue
            if (lastSeg == "*") continue
            val boundRawName = leaf.alias ?: lastSeg
            val importedAsType = isImportedTypeLeaf(lastSeg, leaf.alias, leaf.isSelfImport)
            val importedAsConst = !importedAsType && isImportedScreamingConstLeaf(lastSeg, leaf.alias, leaf.isSelfImport)
            val rewrittenSegs = emitter.rewriteCrateRootAliasAndReexportSegments(emitter.normalizeNetcLibcSegments(leaf.segments))
            val importedPath =
                emitter.resolveKotlinImportPath(rewrittenSegs, leaf.alias, leaf.isSelfImport)
                    ?: fallbackScopedImportPath(rewrittenSegs)
            val isKnownSubpkgModule = emitter.isResolvedCrateSubpackageModule(importedPath, lastSeg)
            val isStdlibUnloweredModule =
                !leaf.isSelfImport &&
                    leaf.alias == null &&
                    leaf.segments.firstOrNull()?.removePrefix("r#") in STDLIB_CRATE_ROOTS &&
                    lastSeg.removePrefix("r#") in UNLOWERED_STD_MODULES
            val isModuleImport =
                !isStdlibUnloweredModule &&
                    (
                        leaf.isSelfImport ||
                            isKnownSubpkgModule ||
                            (!importedAsType && !importedAsConst && boundRawName.first().isLowerCase() && boundRawName != "_")
                    )
            val kotlinName =
                when {
                    isKnownSubpkgModule -> rustModuleIdentToKotlin(boundRawName)
                    importedAsType -> rustTypeIdentToKotlin(boundRawName)
                    isKnownFunctionLeafIdent(lastSeg) -> rustFunctionIdentToKotlin(boundRawName)
                    importedAsConst -> rustConstIdentToKotlin(boundRawName)
                    isModuleImport -> rustModuleIdentToKotlin(boundRawName)
                    boundRawName.first().isUpperCase() -> rustTypeIdentToKotlin(boundRawName)
                    else -> rustValueIdentToKotlin(boundRawName)
                }
            val normalizedPath = if (isKnownSubpkgModule) normalizeSubpkgModulePath(importedPath, lastSeg) else importedPath
            if (!isModuleImport && !isKnownSubpkgModule && leaf.alias != null) {
                recordSamePackageRenameIfNeeded(kotlinName, normalizedPath)
            }
            scopeBuilder.bindModuleImport(
                rawName = boundRawName,
                kotlinName = kotlinName,
                importedPath = normalizedPath,
                isModuleImport = isModuleImport,
                vis = item.vis,
            )
        }
    }

    private fun fallbackScopedImportPath(rewrittenSegs: List<String>): String {
        val head = rewrittenSegs.firstOrNull()?.removePrefix("r#")
        val tailStr = rewrittenSegs.drop(1).joinToString(".") { rustModuleIdentToKotlin(it.removePrefix("r#").replace('-', '_')) }
        val ns = emitter.currentNamespacePrefix()
        return when {
            head == "crate" && ns != null -> if (tailStr.isEmpty()) ns else "$ns.$tailStr"
            head == "super" && headerContext.packageName?.contains('.') == true -> {
                val parentPkg = headerContext.packageName!!.substringBeforeLast('.')
                if (tailStr.isEmpty()) parentPkg else "$parentPkg.$tailStr"
            }
            else -> rewrittenSegs.joinToString(".")
        }
    }

    private fun normalizeSubpkgModulePath(importedPath: String, lastSeg: String): String {
        val ns = emitter.currentNamespacePrefix()
        val afterNs = if (ns != null) importedPath.removePrefix("$ns.") else importedPath
        val cleanRaw = lastSeg.removePrefix("r#")
        val pascalLeaf = rustTypeIdentToKotlin(cleanRaw)
        val leafMod = rustModuleIdentToKotlin(cleanRaw.replace('-', '_'))
        return when {
            ns != null && headerContext.crateSubpackageRawLeafNames[afterNs] == cleanRaw -> "$ns.$afterNs"
            ns != null &&
                afterNs.endsWith(".$pascalLeaf") &&
                headerContext.crateSubpackageRawLeafNames[afterNs.removeSuffix(".$pascalLeaf")] == cleanRaw ->
                "$ns.${afterNs.removeSuffix(".$pascalLeaf")}"
            else -> {
                val pkgPrefix = importedPath.substringBeforeLast('.', missingDelimiterValue = "")
                if (pkgPrefix.isEmpty()) leafMod else "$pkgPrefix.$leafMod"
            }
        }
    }

    private fun recordSamePackageRenameIfNeeded(aliasKt: String, fullImport: String) {
        val importPkg = fullImport.substringBeforeLast('.', missingDelimiterValue = "")
        val finalSimple = fullImport.substringAfterLast('.')
        if (headerContext.packageName != null &&
            importPkg == headerContext.packageName &&
            finalSimple.isNotEmpty() &&
            finalSimple != "*" &&
            aliasKt != finalSimple
        ) {
            emitter.samePackageUseRenames[aliasKt] = finalSimple
        }
    }

    private fun isStdOrSelfStdCrate(root: String?): Boolean =
        root in STDLIB_CRATE_ROOTS ||
            (root == "crate" && headerContext.packageName?.startsWith("io.github.kotlinmania.rustlang.") == true)

    private fun expandOsPreludeLeaves(leaves: List<UseLeaf>): List<UseLeaf> =
        buildList {
            for (leaf in leaves) {
                val segs = leaf.segments
                val root = segs.firstOrNull()
                if (isStdOrSelfStdCrate(root) && segs.size == 5 && segs[1] == "os" && segs[3] == "prelude" && segs[4] == "*") {
                    val stdRoot = if (root == "crate") "crate" else "std"
                    val osSyms =
                        when (segs[2]) {
                            "windows" -> "windows" to WINDOWS_OS_PRELUDE_SYMS
                            "unix", "wasi" -> "unix" to UNIX_OS_PRELUDE_SYMS
                            else -> null
                        }
                    if (osSyms != null) {
                        for (sym in osSyms.second) {
                            add(UseLeaf(segments = listOf(stdRoot, "os", osSyms.first, "ffi", sym), alias = null, isSelfImport = false))
                        }
                        continue
                    }
                }
                add(leaf)
            }
        }

    fun render(
        item: Item.Use,
        indent: String,
        bodyTokensForPubUseFilter: KotlinCodeTokenIndex?,
    ): String {
        val rawLeaves = mutableListOf<UseLeaf>()
        collectUseLeaves(item.tree, emptyList(), rawLeaves)
        val inRustLangPkg = headerContext.packageName?.startsWith("io.github.kotlinmania.rustlang.") == true
        val lines = mutableListOf<String>()
        for (leaf in expandOsPreludeLeaves(rawLeaves)) {
            val root = leaf.segments.firstOrNull()
            val isStdOrSelf = isStdOrSelfStdCrate(root)
            val isMappedOrSelf =
                (root in STDLIB_CRATE_ROOTS && (root in headerContext.crateNamespaces || inRustLangPkg)) ||
                    (root == "crate" && inRustLangPkg)
            if (leaf.isSelfImport && isStdOrSelf) continue
            if (isMappedOrSelf &&
                leaf.segments.size == 2 &&
                leaf.segments[1].firstOrNull()?.isLowerCase() == true &&
                !isKnownFunctionLeafIdent(leaf.segments[1]) &&
                emitter.resolveBestSubpackageForSymbol(rustTypeIdentToKotlin(leaf.segments[1])) == null
            ) {
                continue
            }
            if (isStdOrSelf && leaf.segments.getOrNull(1)?.removePrefix("r#") in FLOAT_OR_CONSTS_SEGS) continue
            if (root == "crate" && leaf.segments.getOrNull(1) == "drops" && leaf.segments.lastOrNull() in DROP_MARKER_LEAVES) {
                continue
            }
            val fullImport = emitter.resolveKotlinImportPath(leaf.segments, leaf.alias, leaf.isSelfImport) ?: continue
            if (CONST_PANIC_OR_ERROR_SUFFIXES.any(fullImport::endsWith)) continue
            val lastSeg = leaf.segments.last()
            if (emitter.isResolvedCrateSubpackageModule(fullImport, lastSeg)) continue
            val finalSimpleName = fullImport.substringAfterLast('.')
            if (leaf.alias == null &&
                (
                    finalSimpleName == "String" ||
                        (
                            root in STDLIB_CRATE_ROOTS &&
                                (finalSimpleName in KOTLIN_STDLIB_SHADOWED_IMPORTS || finalSimpleName in STDLIB_ERASED_OR_BUILTIN_IMPORTS)
                        )
                )
            ) {
                continue
            }
            val importPkg = fullImport.substringBeforeLast('.', missingDelimiterValue = "")
            if (headerContext.packageName != null && importPkg == headerContext.packageName) {
                val rawAlias = leaf.alias
                if (rawAlias != null && (isImportedTypeLeaf(lastSeg, rawAlias, leaf.isSelfImport) || rawAlias.first().isUpperCase())) {
                    recordSamePackageRenameIfNeeded(rustTypeIdentToKotlin(rawAlias), fullImport)
                }
                continue
            }
            val aliasIdent =
                leaf.alias?.let { rawAlias ->
                    when {
                        isImportedTypeLeaf(lastSeg, rawAlias, leaf.isSelfImport) -> rustTypeIdentToKotlin(rawAlias)
                        isImportedScreamingConstLeaf(lastSeg, rawAlias, leaf.isSelfImport) -> rustConstIdentToKotlin(rawAlias)
                        rawAlias.first().isUpperCase() -> rustTypeIdentToKotlin(rawAlias)
                        leaf.isSelfImport || lastSeg.first().isLowerCase() -> rustModuleIdentToKotlin(rawAlias)
                        else -> rustValueIdentToKotlin(rawAlias)
                    }
                }
            val localBoundIdent = aliasIdent ?: finalSimpleName
            if (localBoundIdent in STDLIB_ERASED_OR_BUILTIN_IMPORTS && root in STDLIB_CRATE_ROOTS) continue
            if (leaf.alias == null && isLocallyDeclaredSymbol(localBoundIdent)) continue
            val isPubGlob = item.vis is Visibility.Public && localBoundIdent == "*"
            if (isPubGlob && !shouldKeepPubWildcard(root, importPkg, bodyTokensForPubUseFilter)) {
                continue
            }
            if (shouldFilterUnusedLeaf(item, localBoundIdent) &&
                bodyTokensForPubUseFilter != null &&
                localBoundIdent != "*" &&
                !bodyTokensForPubUseFilter.hasIdent(localBoundIdent)
            ) {
                continue
            }
            if (localBoundIdent != "*" && importPkg.isNotEmpty() && localBoundIdent !in emitter.importedSimpleNameToPkg) {
                emitter.importedSimpleNameToPkg[localBoundIdent] = importPkg
            }
            val aliasSuffix = if (aliasIdent != null && aliasIdent != finalSimpleName) " as $aliasIdent" else ""
            lines += "${indent}import $fullImport$aliasSuffix"
        }
        return lines.distinct().joinToString("\n")
    }

    private fun isLocallyDeclaredSymbol(localBoundIdent: String): Boolean =
        localBoundIdent in inspector.declaredTypeNames ||
            localBoundIdent in inspector.declaredConstNames.values ||
            localBoundIdent in inspector.declaredConstNames.keys ||
            emitter.isDeclaredTopLevelFnKotlinName(localBoundIdent)

    private fun shouldKeepPubWildcard(
        root: String?,
        importPkg: String,
        bodyTokensForPubUseFilter: KotlinCodeTokenIndex?,
    ): Boolean {
        if (root !in STDLIB_CRATE_ROOTS || bodyTokensForPubUseFilter == null || bodyTokensForPubUseFilter.identifiers.isEmpty()) {
            return false
        }
        val symbolsInPkg =
            headerContext.externalCrateSymbolPackages.values.flatMap { symMap ->
                symMap.entries.mapNotNull { (sym, pkgs) -> if (importPkg in pkgs) sym else null }
            }
        return symbolsInPkg.isEmpty() || symbolsInPkg.any(bodyTokensForPubUseFilter::hasIdent)
    }

    private fun shouldFilterUnusedLeaf(item: Item.Use, localBoundIdent: String): Boolean =
        item.vis is Visibility.Public ||
            TranslationSession.sharedModuleFile != null ||
            headerContext.isModuleRootSource ||
            headerContext.isBatchCrateOutput ||
            localBoundIdent in emitter.witnessedSuppressedTraitNames

    companion object {
        private val FILE_OR_DIR_NAMES = wordSet("FILE DIR")
        private val UNLOWERED_STD_MODULES: Set<String> get() = activeRules().words("unloweredStdModules")
        private val WINDOWS_OS_PRELUDE_SYMS: List<String> get() = activeRules().stringList("windowsOsPreludeSyms")
        private val UNIX_OS_PRELUDE_SYMS: List<String> get() = activeRules().stringList("unixOsPreludeSyms")
        private val FLOAT_OR_CONSTS_SEGS: Set<String> get() = activeRules().words("floatOrConstsSegs")
        private val DROP_MARKER_LEAVES: Set<String> get() = activeRules().words("dropMarkerLeaves")
        private val CONST_PANIC_OR_ERROR_SUFFIXES: List<String>
            get() = activeRules().stringList("constPanicOrErrorSuffixes")
    }
}
