package io.github.kotlinmania.rustkotlin

import io.github.kotlinmania.procmacro2.Delimiter
import io.github.kotlinmania.procmacro2.Group
import io.github.kotlinmania.procmacro2.TokenStream
import io.github.kotlinmania.procmacro2.TokenTree
import io.github.kotlinmania.rustkotlin.reused.starlark.AssignCount
import io.github.kotlinmania.syn.Attribute
import io.github.kotlinmania.syn.FnArg
import io.github.kotlinmania.syn.ForeignItem
import io.github.kotlinmania.syn.GenericParam
import io.github.kotlinmania.syn.Generics
import io.github.kotlinmania.syn.Item
import io.github.kotlinmania.syn.Lit
import io.github.kotlinmania.syn.Macro
import io.github.kotlinmania.syn.ModContent
import io.github.kotlinmania.syn.Path
import io.github.kotlinmania.syn.Signature
import io.github.kotlinmania.syn.StaticMutability
import io.github.kotlinmania.syn.SynType
import io.github.kotlinmania.syn.UseTree
import io.github.kotlinmania.syn.Visibility
import io.github.kotlinmania.syn.span

internal data class UseLeaf(
    val segments: List<String>,
    val alias: String?,
    val isSelfImport: Boolean,
)

internal fun KotlinJ2KEmitter.isImportedTypeLeaf(lastSeg: String, boundRawAlias: String?, isSelfImport: Boolean): Boolean {
    if (isSelfImport || lastSeg == "*") return false
    val cleanLast = lastSeg.removePrefix("r#").trim()
    if (!headerContext.rawAbiMode && (cleanLast in CoreIntrinsicLowering.STDLIB_CORE_FFI_SYMBOLS || cleanLast == "CString")) {
        return true
    }
    val effectiveBound = (boundRawAlias ?: lastSeg).removePrefix("r#").trim()
    val effectiveBoundModKt = rustModuleIdentToKotlin(effectiveBound.replace('-', '_'))
    if (!isSelfImport &&
        boundRawAlias == null &&
        effectiveBound.firstOrNull()?.isLowerCase() == true &&
        (
            effectiveBoundModKt in headerContext.crateNamespaces ||
                effectiveBoundModKt in headerContext.externalCrateSymbolPackages ||
                effectiveBound in headerContext.externalCrateSymbolPackages
        )
    ) {
        return false
    }
    if (effectiveBound in inspector.referencedUnqualifiedTypeIdents) return true
    if (boundRawAlias != null && boundRawAlias.first().isUpperCase() && boundRawAlias.any(Char::isLowerCase)) {
        return true
    }
    val typeKt = rustTypeIdentToKotlin(effectiveBound)
    val fnKt = rustFunctionIdentToKotlin(effectiveBound)
    if (typeKt in headerContext.crateSymbolSubpackages && fnKt !in headerContext.crateSymbolSubpackages) {
        return true
    }
    if (headerContext.externalCrateSymbolPackages.values.any { symMap ->
            typeKt in symMap && (fnKt == typeKt || fnKt !in symMap)
        }
    ) {
        return true
    }
    return false
}

internal fun KotlinJ2KEmitter.isKnownFunctionLeafIdent(rawSeg: String): Boolean {
    val cleaned = rawSeg.removePrefix("r#").trim()
    if (cleaned.isEmpty() || cleaned == "*") return false
    if (cleaned in inspector.declaredTopLevelFnRawNames) return true
    if (cleaned in inspector.declaredConstNames || cleaned in inspector.declaredTypeNames) return false
    val fnKt = rustFunctionIdentToKotlin(cleaned)
    if (fnKt in headerContext.crateSymbolSubpackages && cleaned !in headerContext.crateSymbolSubpackages) {
        return true
    }
    return headerContext.externalCrateSymbolPackages.values.any { symMap ->
        fnKt in symMap && cleaned !in symMap
    }
}

internal fun KotlinJ2KEmitter.isImportedScreamingConstLeaf(lastSeg: String, boundRawAlias: String?, isSelfImport: Boolean): Boolean {
    if (isSelfImport || lastSeg == "*") return false
    val cleaned = lastSeg.removePrefix("r#").trim()
    if (cleaned in setOf("FILE", "DIR")) return false
    if (isKnownFunctionLeafIdent(lastSeg)) return false
    val effectiveBound = (boundRawAlias ?: lastSeg).removePrefix("r#").trim()
    if (effectiveBound in inspector.referencedUnqualifiedTypeIdents ||
        effectiveBound in inspector.referencedPathPrefixTypeIdents
    ) {
        return false
    }
    val trimmedUnderscores = cleaned.trim('_')
    return trimmedUnderscores.length >= 2 && KotlinNamingPolicy.Default.isScreamingSnakeCase(trimmedUnderscores)
}

internal fun KotlinJ2KEmitter.registerUseItemInScope(item: Item.Use) {
    val leaves = mutableListOf<UseLeaf>()
    collectUseLeaves(item.tree, emptyList(), leaves)
    for (leaf in leaves) {
        if (leaf.segments.isEmpty()) continue
        val lastSeg = leaf.segments.last()
        if (lastSeg == "*") continue
        val boundRawName = leaf.alias ?: lastSeg
        val importedAsType = isImportedTypeLeaf(lastSeg, leaf.alias, leaf.isSelfImport)
        val importedAsConst = !importedAsType && isImportedScreamingConstLeaf(lastSeg, leaf.alias, leaf.isSelfImport)
        val importedPath = resolveKotlinImportPath(leaf.segments, leaf.alias, leaf.isSelfImport) ?: leaf.segments.joinToString(".")
        val isKnownSubpkgModule = isResolvedCrateSubpackageModule(importedPath, lastSeg)
        val isStdlibUnloweredModule =
            !leaf.isSelfImport &&
                leaf.alias == null &&
                leaf.segments.firstOrNull()?.removePrefix("r#") in STDLIB_CRATE_ROOTS &&
                lastSeg.removePrefix("r#") in setOf("env", "fs", "process", "net", "path", "time", "collections")
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
        val normalizedImportedPath =
            if (isKnownSubpkgModule) {
                val pkgPrefix = importedPath.substringBeforeLast('.', missingDelimiterValue = "")
                val modLeaf = rustModuleIdentToKotlin(lastSeg)
                if (pkgPrefix.isEmpty()) modLeaf else "$pkgPrefix.$modLeaf"
            } else {
                importedPath
            }
        scopeBuilder.bindModuleImport(
            rawName = boundRawName,
            kotlinName = kotlinName,
            importedPath = normalizedImportedPath,
            isModuleImport = isModuleImport,
            vis = item.vis,
        )
    }
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

internal fun KotlinJ2KEmitter.resolveBestSubpackageForSymbol(ktSymbol: String): String? {
    val candidates = headerContext.crateSymbolSubpackages[ktSymbol] ?: return null
    if (candidates.isEmpty()) return null
    val cur = currentSubpackageString()
    if (cur in candidates) return cur
    if (candidates.size == 1) return candidates[0]

    val curSegs = if (cur.isEmpty()) emptyList() else cur.split('.')

    fun archAndOsBias(candSegs: List<String>): Int {
        var bias = 0
        for (s in candSegs) {
            when (s) {
                "b64", "aarch64", "x8664", "notx32" -> bias += 150
                "freebsdlike", "netbsdlike", "freebsd" -> bias += 65
                "apple", "bsd", "linuxlike", "linux", "gnu", "unix" -> bias += 60
                "b32", "x32", "x86", "arm", "mips", "mips32", "powerpc", "sparc", "riscv32", "i386", "uclibc" -> bias -= 300
            }
        }
        return bias
    }

    val curSignificantSegs =
        curSegs
            .filter { it !in setOf("new", "sys", "unix", "bsd", "linuxlike", "freebsdlike", "netbsdlike", "solarish") }
            .toSet()

    fun score(cand: String): Int {
        val candSegs = if (cand.isEmpty()) emptyList() else cand.split('.')
        val isAncestor = cand.isEmpty() || cur.startsWith("$cand.")
        if (isAncestor) {
            return 20_000 + candSegs.size * 200
        }
        val isDescendant = cur.isEmpty() || cand.startsWith("$cur.")
        if (isDescendant) {
            val depthDelta = candSegs.size - curSegs.size
            return 10_000 - depthDelta * 40 + archAndOsBias(candSegs)
        }
        var common = 0
        while (common < curSegs.size && common < candSegs.size && curSegs[common] == candSegs[common]) {
            common++
        }
        val sharedOsBonus = candSegs.count { it in curSignificantSegs } * 2000
        return common * 300 + sharedOsBonus - candSegs.size * 15 + archAndOsBias(candSegs)
    }

    return candidates.maxByOrNull(::score)
}

internal fun KotlinJ2KEmitter.isResolvedCrateSubpackageModule(resolvedImportPath: String, rawLeafIdent: String): Boolean {
    if (headerContext.crateSubpackageRawLeafNames.isEmpty()) return false
    val nsPrefix = currentNamespacePrefix() ?: return false
    if (!resolvedImportPath.startsWith("$nsPrefix.")) return false
    val afterNs = resolvedImportPath.removePrefix("$nsPrefix.")
    val parentSubpkg = afterNs.substringBeforeLast('.', missingDelimiterValue = "")
    val leafMod = rustModuleIdentToKotlin(rawLeafIdent.replace('-', '_'))
    val candidateSubpkg = if (parentSubpkg.isEmpty()) leafMod else "$parentSubpkg.$leafMod"
    return headerContext.crateSubpackageRawLeafNames[candidateSubpkg] == rawLeafIdent.removePrefix("r#")
}

internal fun KotlinJ2KEmitter.scopedAbiKeysForRenderedType(renderedType: String): List<String> {
    val clean = renderedType.trim().removeSuffix("?")
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
            if (subPart.isNotEmpty()) {
                addSubpkgAndAncestors(subPart, symPart)
            }
        }
        val curSub = currentSubpackageString()
        if (curSub.isNotEmpty()) {
            addSubpkgAndAncestors(curSub, clean)
        }
        keys += clean
        keys += clean.substringAfterLast('.')
        return keys.distinct()
    }
    val importedPkg = importedSimpleNameToPkg[clean]
    if (importedPkg != null && nsPrefix != null) {
        if (importedPkg == nsPrefix) {
            keys += clean
        } else if (importedPkg.startsWith("$nsPrefix.")) {
            val sub = importedPkg.removePrefix("$nsPrefix.")
            addSubpkgAndAncestors(sub, clean)
        }
    }
    val curSub = currentSubpackageString()
    if (curSub.isNotEmpty()) {
        addSubpkgAndAncestors(curSub, clean)
    }
    keys += clean
    return keys.distinct()
}

private val KOTLIN_BUILTIN_QUALIFY_SIMPLE_TYPE_NAMES =
    setOf(
        "String",
        "Boolean",
        "Char",
        "Byte",
        "Short",
        "Int",
        "Long",
        "UByte",
        "UShort",
        "UInt",
        "ULong",
        "Float",
        "Double",
        "Unit",
        "Nothing",
        "Any",
    )

internal fun KotlinJ2KEmitter.isDeclaredTopLevelFnKotlinName(ktName: String): Boolean =
    ktName == currentEnclosingFunctionName ||
        inspector.declaredTopLevelFnRawNames.any { rustFunctionIdentToKotlin(it) == ktName }

private val STDLIB_UNIMPORTED_SUBMODULES =
    setOf(
        "alloc",
        "any",
        "arch",
        "ascii",
        "bool",
        "borrow",
        "boxed",
        "cell",
        "char",
        "clone",
        "cmp",
        "collections",
        "convert",
        "core",
        "default",
        "env",
        "error",
        "f32",
        "f64",
        "ffi",
        "fmt",
        "fs",
        "hash",
        "hint",
        "i128",
        "i16",
        "i32",
        "i64",
        "i8",
        "io",
        "isize",
        "iter",
        "marker",
        "mem",
        "net",
        "num",
        "ops",
        "option",
        "panic",
        "path",
        "pin",
        "process",
        "ptr",
        "rc",
        "result",
        "slice",
        "std",
        "str",
        "string",
        "sync",
        "task",
        "thread",
        "time",
        "u128",
        "u16",
        "u32",
        "u64",
        "u8",
        "usize",
        "vec",
    )

internal fun KotlinJ2KEmitter.bindOrQualifyResolvedCrossSymbol(resolvedFullImport: String): String {
    val resolvedPkg = resolvedFullImport.substringBeforeLast('.', missingDelimiterValue = "")
    val simpleName = resolvedFullImport.substringAfterLast('.')
    val collidedPkg = importedSimpleNameToPkg[simpleName]
    return when {
        simpleName in KOTLIN_BUILTIN_QUALIFY_SIMPLE_TYPE_NAMES ->
            if (resolvedPkg.isNotEmpty() && resolvedPkg != headerContext.packageName) resolvedFullImport else simpleName
        simpleName in inspector.attachableTypeNames ||
            simpleName in inspector.declaredTypeNames ||
            isDeclaredTopLevelFnKotlinName(simpleName) ->
            if (resolvedPkg.isNotEmpty() && resolvedPkg != headerContext.packageName) resolvedFullImport else simpleName
        collidedPkg != null && collidedPkg != resolvedPkg -> resolvedFullImport
        resolvedPkg.isNotEmpty() && resolvedPkg != headerContext.packageName -> {
            importedSimpleNameToPkg[simpleName] = resolvedPkg
            extraTypePathImports += "import $resolvedFullImport"
            simpleName
        }
        else -> simpleName
    }
}

internal fun KotlinJ2KEmitter.resolveCrossModulePathTarget(
    rawSegStrings: List<String>,
    inTypePosition: Boolean = false,
): Pair<String, List<String>>? {
    if (rawSegStrings.size < 2) return null
    val firstRaw = rawSegStrings.first().removePrefix("r#")
    if (firstRaw in inspector.localInlineModuleNames) return null
    if (firstRaw == "std" || firstRaw == "alloc" || firstRaw == "Self" || firstRaw in PRIMITIVE_TYPE_NAMES) return null
    if (rawSegStrings.dropLast(1).any { it.removePrefix("r#") in STDLIB_PRELUDE_REEXPORT_MODULES }) {
        return null
    }
    if (firstRaw == "core") {
        val isCrateCoreModule =
            headerContext.crateSubpackageRawLeafNames["core"] == "core" ||
                "core" in inspector.localExternalModuleNames
        val secondRaw = rawSegStrings.getOrNull(1)?.removePrefix("r#")
        val stdlibCoreSubmodules =
            setOf(
                "ffi",
                "mem",
                "ptr",
                "option",
                "result",
                "fmt",
                "marker",
                "cmp",
                "ops",
                "slice",
                "str",
                "iter",
                "convert",
                "default",
                "hash",
                "clone",
                "any",
                "arch",
                "cell",
                "hint",
                "num",
                "panic",
                "pin",
                "sync",
                "task",
                "time",
                "char",
                "borrow",
                "error",
            )
        if (!isCrateCoreModule || secondRaw in STDLIB_MACRO_OR_PRIMITIVE_MODULES || secondRaw in stdlibCoreSubmodules) {
            return null
        }
    }

    val nsPrefix = currentNamespacePrefix()
    if (scopeBuilder.isImportedModuleInScope(firstRaw)) {
        val modBinding = scopeBuilder.resolveModuleBinding(firstRaw) ?: return null
        val importedPath = modBinding.importedPath ?: return null
        val rest = rawSegStrings.drop(1)
        if (importedPath in headerContext.crateNamespaces && rest.isNotEmpty()) {
            val midSegs = rest.dropLast(1)
            if (midSegs.all { it.removePrefix("r#").firstOrNull()?.isLowerCase() == true }) {
                val resolved =
                    resolveKotlinImportPath(listOf(importedPath) + rest, forceTypeLeaf = inTypePosition)
                        ?: return null
                return resolved to emptyList()
            }
        }
        val firstModCheck = rustModuleIdentToKotlin(firstRaw.replace('-', '_'))
        val isExternalImport =
            firstModCheck in headerContext.crateNamespaces ||
                importedPath in headerContext.crateNamespaces ||
                headerContext.crateNamespaces.values.any { importedPath == it || importedPath.startsWith("$it.") }
        if (!isExternalImport) {
            if (nsPrefix == null || !importedPath.startsWith("$nsPrefix.")) return null
            val boundSubpkg = importedPath.removePrefix("$nsPrefix.")
            if (boundSubpkg !in headerContext.crateSubpackageRawLeafNames) return null
            var modSubpkg = boundSubpkg
            var idx = 0
            while (idx < rest.lastIndex) {
                val segClean = rest[idx].removePrefix("r#")
                val nextSub = "$modSubpkg.${rustModuleIdentToKotlin(segClean.replace('-', '_'))}"
                if (headerContext.crateSubpackageRawLeafNames[nextSub] == segClean) {
                    modSubpkg = nextSub
                    idx++
                } else {
                    break
                }
            }
            val symRaw = rest[idx]
            val trailing = rest.drop(idx + 1)
            val symKt =
                when {
                    inTypePosition || trailing.isNotEmpty() -> rustTypeIdentToKotlin(symRaw)
                    isKnownFunctionLeafIdent(symRaw) -> rustFunctionIdentToKotlin(symRaw)
                    isImportedScreamingConstLeaf(symRaw, null, false) -> rustConstIdentToKotlin(symRaw)
                    symRaw.first().isUpperCase() -> rustTypeIdentToKotlin(symRaw)
                    else -> rustValueIdentToKotlin(symRaw)
                }
            return "$nsPrefix.$modSubpkg.$symKt" to trailing
        }
    }

    val firstModKt = rustModuleIdentToKotlin(firstRaw.replace('-', '_'))
    val curSub = currentSubpackageString()
    val childSubpkg = if (curSub.isEmpty()) firstModKt else "$curSub.$firstModKt"
    val isChildCrateMod =
        firstRaw != "crate" &&
            firstRaw != "super" &&
            firstRaw != "self" &&
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
        firstRaw == "crate" ||
            firstRaw == "super" ||
            firstRaw == "self" ||
            firstRaw in headerContext.selfCrateAliases ||
            isChildCrateMod
    if (isSelfOrRelNav) {
        val effectiveSegs = if (isChildCrateMod) listOf("self") + rawSegStrings else rawSegStrings
        val navCount =
            effectiveSegs
                .takeWhile {
                    val c = it.removePrefix("r#")
                    c == "crate" || c == "super" || c == "self" || c in headerContext.selfCrateAliases
                }.size
                .coerceAtLeast(1)
        val afterNav = effectiveSegs.drop(navCount)
        if (afterNav.isEmpty()) return null
        if (afterNav.size == 1) {
            val direct = resolveKotlinImportPath(effectiveSegs, forceTypeLeaf = inTypePosition) ?: return null
            return direct to emptyList()
        }
        val hasCrateInventory =
            headerContext.crateSubpackageRawLeafNames.isNotEmpty() ||
                headerContext.crateSymbolSubpackages.isNotEmpty()
        // Try longest module prefix first, then peel 1 trailing member (e.g. `core::GUID::from_u128`).
        for (trailingCount in 0..1) {
            if (afterNav.size <= trailingCount) continue
            val candidateSegs = effectiveSegs.dropLast(trailingCount)
            val midSegs = afterNav.dropLast(1 + trailingCount)
            val resolved =
                resolveKotlinImportPath(
                    candidateSegs,
                    forceTypeLeaf = inTypePosition || trailingCount > 0,
                ) ?: continue
            if (midSegs.isEmpty()) {
                return resolved to effectiveSegs.takeLast(trailingCount)
            }
            if (nsPrefix != null && resolved.startsWith("$nsPrefix.") && hasCrateInventory) {
                val afterNs = resolved.removePrefix("$nsPrefix.")
                val candSubpkg = afterNs.substringBeforeLast('.', missingDelimiterValue = "")
                val symSimple = afterNs.substringAfterLast('.')
                val expectedRawLeaf = midSegs.last().removePrefix("r#")
                if ((candSubpkg.isEmpty() && expectedRawLeaf in headerContext.crateFlatFileModules) ||
                    headerContext.crateSubpackageRawLeafNames[candSubpkg] == expectedRawLeaf ||
                    headerContext.crateSymbolSubpackages[symSimple]?.contains(candSubpkg) == true
                ) {
                    return resolved to rawSegStrings.takeLast(trailingCount)
                }
                if (midSegs.all { it.removePrefix("r#").firstOrNull()?.isLowerCase() == true }) {
                    val bestSub = resolveBestSubpackageForSymbol(symSimple)
                    if (bestSub != null) {
                        val relocated = if (bestSub.isEmpty()) "$nsPrefix.$symSimple" else "$nsPrefix.$bestSub.$symSimple"
                        return relocated to rawSegStrings.takeLast(trailingCount)
                    }
                }
            } else if (midSegs.all { it.removePrefix("r#").firstOrNull()?.isLowerCase() == true }) {
                return resolved to rawSegStrings.takeLast(trailingCount)
            }
        }
        return null
    }

    if (firstRaw.firstOrNull()?.isLowerCase() == true) {
        val firstAsType = rustTypeIdentToKotlin(firstRaw)
        if (firstAsType in inspector.declaredTypeNames ||
            firstRaw in inspector.declaredTypeNames ||
            firstAsType in activeTypeParamNames ||
            resolveImportedUppercaseTypeBinding(firstRaw) != null ||
            firstAsType in headerContext.crateSymbolSubpackages ||
            firstRaw in headerContext.crateSymbolSubpackages
        ) {
            return null
        }
        if (!inTypePosition && rawSegStrings.size == 2) {
            val modKt = rustModuleIdentToKotlin(firstRaw)
            val isKnownExternalCrate =
                modKt in headerContext.crateNamespaces ||
                    (!headerContext.projectGroup.isNullOrBlank() && firstRaw !in STDLIB_UNIMPORTED_SUBMODULES)
            if (!isKnownExternalCrate) {
                return null
            }
        }
        val midSegs = rawSegStrings.drop(1).dropLast(1)
        if (midSegs.all { it.removePrefix("r#").firstOrNull()?.isLowerCase() == true }) {
            val resolved = resolveKotlinImportPath(rawSegStrings, forceTypeLeaf = inTypePosition) ?: return null
            return resolved to emptyList()
        }
        if (!inTypePosition &&
            rawSegStrings.size >= 3 &&
            rawSegStrings.drop(1).dropLast(2).all { it.removePrefix("r#").firstOrNull()?.isLowerCase() == true } &&
            rawSegStrings[rawSegStrings.size - 2].removePrefix("r#").firstOrNull()?.isUpperCase() == true
        ) {
            val modKt = rustModuleIdentToKotlin(firstRaw)
            val isKnownExternalCrate =
                modKt in headerContext.crateNamespaces ||
                    (!headerContext.projectGroup.isNullOrBlank() && firstRaw !in STDLIB_UNIMPORTED_SUBMODULES)
            if (isKnownExternalCrate) {
                val resolved = resolveKotlinImportPath(rawSegStrings.dropLast(1), forceTypeLeaf = true) ?: return null
                return resolved to listOf(rawSegStrings.last())
            }
        }
    }
    return null
}

private fun KotlinJ2KEmitter.isIntermediateTypeSegmentInUsePath(
    segment: String,
    idx: Int,
    segments: List<String>,
    baseSegs: List<String> = emptyList(),
): Boolean {
    if (segment.firstOrNull()?.isUpperCase() != true) return false
    if (headerContext.crateSubpackageRawLeafNames.isNotEmpty()) {
        val candidateSubpkg =
            (baseSegs + segments.take(idx + 1).map { rustModuleIdentToKotlin(it.removePrefix("r#").replace('-', '_')) })
                .filter { it.isNotEmpty() }
                .joinToString(".")
        if (candidateSubpkg.isNotEmpty() &&
            headerContext.crateSubpackageRawLeafNames[candidateSubpkg] == segment.removePrefix("r#")
        ) {
            return false
        }
    }
    val kt = rustTypeIdentToKotlin(segment)
    if (kt in inspector.declaredTypeNames || kt in headerContext.crateSymbolSubpackages) return true
    if (headerContext.crateSymbolSubpackages.isNotEmpty()) return false
    return idx == segments.lastIndex - 1 && segments.last() == "*"
}

internal fun KotlinJ2KEmitter.resolveKotlinImportPath(
    segs: List<String>,
    boundRawAlias: String? = null,
    isSelfImport: Boolean = false,
    forceTypeLeaf: Boolean = false,
): String? {
    if (segs.isEmpty()) return null
    val root = segs.first()
    // A same-file type or an imported type's members retain type casing.
    // Resolve an unqualified type glob through an unambiguous lexical glob use;
    // this does not inspect any other Rust file or invent a cross-file symbol table.
    if (root.firstOrNull()?.isUpperCase() == true && segs.size > 1) {
        val rootTypeKt = rustTypeIdentToKotlin(root)
        if (rootTypeKt in inspector.declaredTypeNames) {
            return resolveKotlinImportPath(listOf("self") + segs, boundRawAlias, isSelfImport, forceTypeLeaf)
        }
        if (headerContext.crateSymbolSubpackages[rootTypeKt]?.isNotEmpty() == true) {
            val subPkg = resolveBestSubpackageForSymbol(rootTypeKt)
            val nsPrefix = currentNamespacePrefix()
            if (nsPrefix != null) {
                val ownerPkg = if (subPkg.isNullOrEmpty()) nsPrefix else "$nsPrefix.$subPkg"
                val tail = segs.map { if (it == "*") it else rustTypeIdentToKotlin(it) }.joinToString(".")
                return "$ownerPkg.$tail"
            }
        }
        val importedGlobs = mutableListOf<UseLeaf>()
        inspector.allUseItems.forEach { collectUseLeaves(it.tree, emptyList(), importedGlobs) }
        val packages =
            importedGlobs
                .filter {
                    it.segments.lastOrNull() == "*" && it.segments
                        .firstOrNull()
                        ?.firstOrNull()
                        ?.isLowerCase() == true
                }.mapNotNull { resolveKotlinImportPath(it.segments, isSelfImport = it.isSelfImport) }
                .map { it.removeSuffix(".*") }
                .distinct()
        if (packages.size == 1) {
            val tail = segs.map { if (it == "*") it else rustTypeIdentToKotlin(it) }.joinToString(".")
            return "${packages.single()}.$tail"
        }
    }
    if (root == "self") {
        val afterSelf = segs.drop(1)
        if (afterSelf.isEmpty()) return null
        val rawCurPkg = headerContext.packageName?.trim()?.removeSuffix(".")
        val curPkg = rawCurPkg?.takeIf { it.isNotEmpty() } ?: return null
        val curSubpkg = currentSubpackageString()
        val curSubSegs = if (curSubpkg.isEmpty()) emptyList() else curSubpkg.split('.')
        val isEnumGlobSelf =
            afterSelf.last() == "*" &&
                afterSelf.size >= 2 &&
                isIntermediateTypeSegmentInUsePath(afterSelf[afterSelf.lastIndex - 1], afterSelf.lastIndex - 1, afterSelf, curSubSegs)
        if (afterSelf.last() == "*" && !isEnumGlobSelf && headerContext.crateSymbolSubpackages.isNotEmpty()) {
            return null
        }
        val mappedTail =
            afterSelf.mapIndexed { idx, s ->
                if (idx == afterSelf.lastIndex) {
                    when {
                        s == "*" -> "*"
                        forceTypeLeaf || isImportedTypeLeaf(s, boundRawAlias, isSelfImport) -> rustTypeIdentToKotlin(s)
                        isKnownFunctionLeafIdent(s) -> rustFunctionIdentToKotlin(s)
                        isImportedScreamingConstLeaf(s, boundRawAlias, isSelfImport) -> rustConstIdentToKotlin(s)
                        s.first().isUpperCase() -> rustTypeIdentToKotlin(s)
                        else -> rustValueIdentToKotlin(s)
                    }
                } else {
                    if (isIntermediateTypeSegmentInUsePath(s, idx, afterSelf, curSubSegs)) {
                        rustTypeIdentToKotlin(s)
                    } else {
                        rustModuleIdentToKotlin(s)
                    }
                }
            }
        return "$curPkg.${mappedTail.joinToString(".")}"
    }
    if (root == "super") {
        var superCount = 0
        while (superCount < segs.size && segs[superCount] == "super") {
            superCount++
        }
        val rem = segs.drop(superCount)
        if (rem.isEmpty()) return null
        val nsPrefix = currentNamespacePrefix() ?: return null
        val curSubpkg = currentSubpackageString()
        val curSubSegs = if (curSubpkg.isEmpty()) emptyList() else curSubpkg.split('.')
        var baseSegs =
            if (superCount >= curSubSegs.size) {
                emptyList()
            } else {
                curSubSegs.dropLast(superCount)
            }
        if (baseSegs.size == 1 && headerContext.includedSubdirModuleMap.isNotEmpty()) {
            val includedSubdirs = headerContext.includedSubdirModuleMap.values
            val includedRoots = includedSubdirs.map(::rustModuleIdentToKotlin).toSet()
            if (baseSegs[0] in includedRoots) {
                val expectedRoot = headerContext.includedSubdirModuleMap[rem.first()]?.let(::rustModuleIdentToKotlin)
                if (expectedRoot != baseSegs[0]) {
                    baseSegs = emptyList()
                }
            }
        }
        if (baseSegs.isEmpty() && headerContext.includedSubdirModuleMap.isNotEmpty()) {
            headerContext.includedSubdirModuleMap[rem.first()]?.let { subRoot ->
                baseSegs = listOf(rustModuleIdentToKotlin(subRoot))
            }
        }
        val isEnumGlobSuper =
            rem.last() == "*" &&
                rem.size >= 2 &&
                isIntermediateTypeSegmentInUsePath(rem[rem.lastIndex - 1], rem.lastIndex - 1, rem, baseSegs)
        if (rem.last() == "*" && !isEnumGlobSuper && headerContext.crateSymbolSubpackages.isNotEmpty()) {
            return null
        }
        val mappedRem =
            rem.mapIndexed { idx, s ->
                if (idx == rem.lastIndex) {
                    when {
                        s == "*" -> "*"
                        forceTypeLeaf || isImportedTypeLeaf(s, boundRawAlias, isSelfImport) -> rustTypeIdentToKotlin(s)
                        isKnownFunctionLeafIdent(s) -> rustFunctionIdentToKotlin(s)
                        isImportedScreamingConstLeaf(s, boundRawAlias, isSelfImport) -> rustConstIdentToKotlin(s)
                        s.first().isUpperCase() -> rustTypeIdentToKotlin(s)
                        else -> rustValueIdentToKotlin(s)
                    }
                } else {
                    if (isIntermediateTypeSegmentInUsePath(s, idx, rem, baseSegs)) rustTypeIdentToKotlin(s) else rustModuleIdentToKotlin(s)
                }
            }
        if (rem.size == 1 && mappedRem[0] != "*" && headerContext.crateSymbolSubpackages.isNotEmpty()) {
            val targetPrefix = baseSegs.joinToString(".")
            val candidates = headerContext.crateSymbolSubpackages[mappedRem[0]]
            if (candidates != null && targetPrefix !in candidates) {
                val best = resolveBestSubpackageForSymbol(mappedRem[0])
                if (best != null) {
                    return if (best.isEmpty()) "$nsPrefix.${mappedRem[0]}" else "$nsPrefix.$best.${mappedRem[0]}"
                }
            }
        }
        val combined = baseSegs + mappedRem
        return "$nsPrefix.${combined.joinToString(".")}"
    }
    if (root in inspector.localExternalModuleNames) {
        return resolveKotlinImportPath(listOf("self") + segs, boundRawAlias, isSelfImport, forceTypeLeaf)
    }
    if (root !in IGNORED_USE_ROOTS &&
        root !in STDLIB_CRATE_ROOTS &&
        root != "crate" &&
        root !in headerContext.selfCrateAliases &&
        root !in setOf("proc_macro", "serde_derive", "serde_derive_internals")
    ) {
        val cleanRoot = root.removePrefix("r#")
        val rootCrateKey = rustModuleIdentToKotlin(cleanRoot)
        val isConfiguredExtCrate =
            cleanRoot in headerContext.crateNamespaces ||
                rootCrateKey in headerContext.crateNamespaces ||
                rootCrateKey in headerContext.externalCrateSymbolPackages
        if (!isConfiguredExtCrate) {
            if (segs.size == 1 && !isSelfImport) {
                val leafKt =
                    when {
                        forceTypeLeaf || isImportedTypeLeaf(cleanRoot, boundRawAlias, isSelfImport) -> rustTypeIdentToKotlin(cleanRoot)
                        isKnownFunctionLeafIdent(cleanRoot) -> rustFunctionIdentToKotlin(cleanRoot)
                        isImportedScreamingConstLeaf(cleanRoot, boundRawAlias, isSelfImport) -> rustConstIdentToKotlin(cleanRoot)
                        cleanRoot.firstOrNull()?.isUpperCase() == true -> rustTypeIdentToKotlin(cleanRoot)
                        else -> rustValueIdentToKotlin(cleanRoot)
                    }
                val isKnownLocalCrateSymbol =
                    leafKt in headerContext.crateSymbolSubpackages ||
                        cleanRoot in inspector.declaredTypeNames ||
                        rustTypeIdentToKotlin(cleanRoot) in inspector.declaredTypeNames ||
                        cleanRoot in inspector.declaredTopLevelFnRawNames ||
                        cleanRoot in inspector.declaredConstNames
                if (isKnownLocalCrateSymbol) {
                    return resolveKotlinImportPath(listOf("crate") + segs, boundRawAlias, isSelfImport, forceTypeLeaf)
                }
            } else if (segs.size >= 2) {
                val targetModPkg =
                    segs
                        .dropLast(1)
                        .joinToString(".") { rustModuleIdentToKotlin(it.removePrefix("r#")) }
                val cleanLast = segs.last().removePrefix("r#")
                val candidateLeafNames =
                    if (cleanLast == "*") {
                        emptyList()
                    } else {
                        listOf(
                            rustTypeIdentToKotlin(cleanLast),
                            rustFunctionIdentToKotlin(cleanLast),
                            rustConstIdentToKotlin(cleanLast),
                            rustValueIdentToKotlin(cleanLast),
                        ).distinct()
                    }
                val verifiedInCrateSymbols =
                    headerContext.crateSymbolSubpackages.isNotEmpty() &&
                        if (cleanLast == "*") {
                            headerContext.crateSymbolSubpackages.values.any { pkgs ->
                                pkgs.any { it == targetModPkg || it.startsWith("$targetModPkg.") }
                            }
                        } else {
                            candidateLeafNames.any { sym ->
                                val pkgs = headerContext.crateSymbolSubpackages[sym].orEmpty()
                                pkgs.any { it == targetModPkg || it.startsWith("$targetModPkg.") } ||
                                    headerContext.crateStructFieldTypes.keys.any { k ->
                                        k == "$targetModPkg.$sym" || k.startsWith("$targetModPkg.$sym.")
                                    } ||
                                    headerContext.crateFnReturnTypes.keys.any { k ->
                                        k == "$targetModPkg.$sym" || k.startsWith("$targetModPkg.$sym.")
                                    }
                            }
                        }
                val rootKnownCrateModule =
                    verifiedInCrateSymbols ||
                        cleanRoot in headerContext.includedSubdirModuleMap ||
                        cleanRoot in headerContext.crateSubpackageRawLeafNames.values ||
                        rootCrateKey in headerContext.crateSubpackageRawLeafNames.keys
                if (rootKnownCrateModule) {
                    return resolveKotlinImportPath(listOf("crate") + segs, boundRawAlias, isSelfImport, forceTypeLeaf)
                }
                if (cleanRoot in inspector.localInlineModuleNames) {
                    return resolveKotlinImportPath(listOf("self") + segs.drop(1), boundRawAlias, isSelfImport, forceTypeLeaf)
                }
            }
        }
    }
    if (root in IGNORED_USE_ROOTS) return null
    if (root in STDLIB_CRATE_ROOTS && segs.getOrNull(1) in STDLIB_MACRO_OR_PRIMITIVE_MODULES) {
        return null
    }
    val lastRaw = segs.last()
    if (!isSelfImport && !headerContext.rawAbiMode) {
        val cleanSegs = segs.map { it.removePrefix("r#") }
        if ((cleanSegs.size == 3 && cleanSegs[0] in setOf("core", "std") && cleanSegs[1] == "ffi") ||
            (cleanSegs.size == 4 && cleanSegs[0] == "std" && cleanSegs[1] == "os" && cleanSegs[2] == "raw")
        ) {
            val leaf = cleanSegs.last()
            if (leaf == "CString" && resolveBestSubpackageForSymbol("CString") == null) {
                return "${CoreIntrinsicLowering.resolveAllocNamespace(headerContext)}.ffi.CString"
            }
            CoreIntrinsicLowering.STDLIB_CORE_FFI_SYMBOLS[leaf]?.let { ktSym ->
                if (resolveBestSubpackageForSymbol(ktSym) == null) {
                    return "${CoreIntrinsicLowering.resolveCoreNamespace(headerContext)}.ffi.$ktSym"
                }
            }
        }
        if (cleanSegs.size == 3 && cleanSegs[0] == "alloc" && cleanSegs[1] == "ffi" && cleanSegs[2] == "CString") {
            if (resolveBestSubpackageForSymbol("CString") == null) {
                return "${CoreIntrinsicLowering.resolveAllocNamespace(headerContext)}.ffi.CString"
            }
        }
    }
    if (!isSelfImport && headerContext.rawAbiMode && lastRaw.removePrefix("r#") == "c_void") {
        return null
    }
    val mapped =
        segs.mapIndexed { idx, s ->
            if (idx == segs.lastIndex) {
                when {
                    s == "*" -> "*"
                    forceTypeLeaf || isImportedTypeLeaf(s, boundRawAlias, isSelfImport) -> rustTypeIdentToKotlin(s)
                    isKnownFunctionLeafIdent(s) -> rustFunctionIdentToKotlin(s)
                    isImportedScreamingConstLeaf(s, boundRawAlias, isSelfImport) -> rustConstIdentToKotlin(s)
                    s.first().isUpperCase() -> rustTypeIdentToKotlin(s)
                    else -> rustValueIdentToKotlin(s)
                }
            } else {
                val isSelfRoot = root == "crate" || root in headerContext.selfCrateAliases
                val relSegs = if (isSelfRoot) segs.drop(1) else segs
                val relIdx = if (isSelfRoot) idx - 1 else idx
                if (relIdx >= 0 && isIntermediateTypeSegmentInUsePath(s, relIdx, relSegs)) {
                    rustTypeIdentToKotlin(s)
                } else {
                    rustModuleIdentToKotlin(s)
                }
            }
        }
    if (mapped.size < 2) return null
    return if (root == "crate" || root in headerContext.selfCrateAliases) {
        val nsPrefix = currentNamespacePrefix()
        val afterRoot = segs.drop(1)
        if (afterRoot.last() == "*" && headerContext.crateSymbolSubpackages.isNotEmpty()) {
            val isRootOrPreludeGlob =
                afterRoot.size == 1 ||
                    (afterRoot.size == 2 && afterRoot[0] == "prelude")
            if (isRootOrPreludeGlob && nsPrefix != null && headerContext.packageName != nsPrefix) {
                return "$nsPrefix.*"
            }
            if (afterRoot.size >= 2 &&
                isIntermediateTypeSegmentInUsePath(afterRoot[afterRoot.lastIndex - 1], afterRoot.lastIndex - 1, afterRoot)
            ) {
                val enumKt = rustTypeIdentToKotlin(afterRoot[afterRoot.lastIndex - 1])
                val sub = resolveBestSubpackageForSymbol(enumKt)
                if (nsPrefix != null && sub != null) {
                    val ownerPkg = if (sub.isEmpty()) nsPrefix else "$nsPrefix.$sub"
                    return "$ownerPkg.$enumKt.*"
                }
            }
            return null
        }
        if (afterRoot.size == 1 && mapped.last() != "*" && nsPrefix != null && headerContext.crateSymbolSubpackages.isNotEmpty()) {
            val bestSub = resolveBestSubpackageForSymbol(mapped.last())
            if (bestSub != null) {
                return if (bestSub.isEmpty()) "$nsPrefix.${mapped.last()}" else "$nsPrefix.$bestSub.${mapped.last()}"
            }
        }
        // A module path names where the item is declared; the scanned crate metadata records the
        // subpackage that declaration is emitted into (with split module roots, `op.rs` -> `op`).
        if (afterRoot.size >= 2 && mapped.last() != "*" && nsPrefix != null) {
            val explicitModule = mapped.drop(1).dropLast(1).joinToString(".")
            val candidates = headerContext.crateSymbolSubpackages[mapped.last()]
            if (!candidates.isNullOrEmpty()) {
                val sub =
                    when {
                        explicitModule in candidates -> explicitModule
                        else ->
                            candidates
                                .filter { it.startsWith("$explicitModule.") }
                                .minByOrNull { it.count { ch -> ch == '.' } }
                                ?: resolveBestSubpackageForSymbol(mapped.last())
                    }
                if (sub != null) {
                    return if (sub.isEmpty()) "$nsPrefix.${mapped.last()}" else "$nsPrefix.$sub.${mapped.last()}"
                }
            }
            for (depCrate in listOf("core", "alloc")) {
                val depNs =
                    headerContext.crateNamespaces[depCrate]
                        ?.trim()
                        ?.removeSuffix(".")
                        ?.takeIf { it.isNotEmpty() } ?: continue
                if (depNs == nsPrefix) continue
                val extCandidates = headerContext.externalCrateSymbolPackages[depCrate]?.get(mapped.last()) ?: continue
                val exactPkg = if (explicitModule.isEmpty()) depNs else "$depNs.$explicitModule"
                val matchedPkg =
                    when {
                        exactPkg in extCandidates -> exactPkg
                        else ->
                            extCandidates
                                .filter { it.startsWith("$exactPkg.") }
                                .minByOrNull { it.count { ch -> ch == '.' } }
                    }
                if (matchedPkg != null) {
                    return "$matchedPkg.${mapped.last()}"
                }
            }
        }
        val includedPrefix =
            headerContext.includedSubdirModuleMap[afterRoot.first()]
                ?.let(::rustModuleIdentToKotlin)
                ?.takeIf { it.isNotEmpty() }
        val tail =
            when {
                segs.size >= 3 && segs[1] in headerContext.crateFlatFileModules -> mapped.last()
                includedPrefix != null -> "$includedPrefix.${mapped.drop(1).joinToString(".")}"
                else -> mapped.drop(1).joinToString(".")
            }
        if (nsPrefix != null) "$nsPrefix.$tail" else tail
    } else {
        val rawCrateKey = mapped.first()
        val isSelfProcMacro2 =
            headerContext.projectNamespace == "io.github.kotlinmania.procmacro2" ||
                headerContext.packageName == "io.github.kotlinmania.procmacro2" ||
                headerContext.packageName?.startsWith("io.github.kotlinmania.procmacro2.") == true
        val crateKey =
            when {
                root == "proc_macro" &&
                    !isSelfProcMacro2 &&
                    headerContext.crateNamespaces["procmacro"] == null &&
                    headerContext.projectGroup == "io.github.kotlinmania" -> "procmacro2"
                root == "serde_derive" &&
                    headerContext.crateNamespaces["serdederive"] == null &&
                    headerContext.projectGroup == "io.github.kotlinmania" -> "serderive"
                root == "serde_derive_internals" &&
                    headerContext.crateNamespaces["serdederiveinternals"] == null &&
                    headerContext.projectGroup == "io.github.kotlinmania" -> "serderive.internals"
                else -> rawCrateKey
            }
        val customCrateNs =
            headerContext.crateNamespaces[crateKey]
                ?.trim()
                ?.removeSuffix(".")
                ?.takeIf { it.isNotEmpty() }
        val flattenSubmodules =
            headerContext.projectGroup == "io.github.kotlinmania" &&
                crateKey in FLAT_KOTLINMANIA_CRATES &&
                mapped.size > 2
        val effectiveTail = if (flattenSubmodules) mapped.last() else mapped.drop(1).joinToString(".")
        if (customCrateNs != null) {
            val candidateCrates =
                when (crateKey) {
                    "std" -> if (isSelfImport) listOf("alloc", "core") else listOf("std", "alloc", "core")
                    "alloc" -> if (isSelfImport) listOf("core") else listOf("alloc", "core")
                    else -> if (isSelfImport) emptyList() else listOf(crateKey)
                }
            val anyCandidateExtPkgs = candidateCrates.any { headerContext.externalCrateSymbolPackages[it] != null }
            val secondSeg = segs.getOrNull(1)?.removePrefix("r#")
            if (root in STDLIB_CRATE_ROOTS &&
                secondSeg in
                setOf(
                    "f32",
                    "f64",
                    "f16",
                    "f128",
                    "i8",
                    "i16",
                    "i32",
                    "i64",
                    "i128",
                    "isize",
                    "u8",
                    "u16",
                    "u32",
                    "u64",
                    "u128",
                    "usize",
                    "consts",
                    "prelude",
                )
            ) {
                return null
            }
            if (!anyCandidateExtPkgs &&
                root in STDLIB_CRATE_ROOTS &&
                secondSeg in
                setOf(
                    "convert",
                    "marker",
                    "default",
                    "clone",
                    "hash",
                    "borrow",
                    "iter",
                    "option",
                    "hint",
                    "panic",
                    "pin",
                    "task",
                    "error",
                    "fmt",
                )
            ) {
                return null
            }
            val mappedLeaf = mapped.lastOrNull()
            if (anyCandidateExtPkgs && mappedLeaf != null && mappedLeaf != "*") {
                val midPath = mapped.drop(1).dropLast(1).joinToString(".")
                for (searchCrate in candidateCrates) {
                    val searchNs =
                        headerContext.crateNamespaces[searchCrate]
                            ?.trim()
                            ?.removeSuffix(".")
                            ?.takeIf { it.isNotEmpty() } ?: continue
                    val searchExtPkgs = headerContext.externalCrateSymbolPackages[searchCrate] ?: continue
                    val candidates = searchExtPkgs[mappedLeaf]
                    if (!candidates.isNullOrEmpty()) {
                        val exactPkg = if (midPath.isEmpty()) searchNs else "$searchNs.$midPath"
                        val resolvedPkg =
                            when {
                                exactPkg in candidates -> exactPkg
                                else ->
                                    candidates
                                        .filter { it.startsWith("$exactPkg.") }
                                        .minByOrNull { it.count { ch -> ch == '.' } }
                                        ?: candidates.first()
                            }
                        return "$resolvedPkg.$mappedLeaf"
                    }
                }
                if (root in STDLIB_CRATE_ROOTS) return null
            } else if (root in STDLIB_CRATE_ROOTS && mappedLeaf == "*") {
                val allowedWildcardSubmodules = setOf("cell", "cmp", "ffi", "mem", "num", "ops", "ptr", "result", "sync", "atomic")
                if (mapped.size != 3 || mapped[1] !in allowedWildcardSubmodules) {
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
            "$customCrateNs.$effectiveTail"
        } else {
            val joined = "$crateKey.$effectiveTail"
            val groupPrefix =
                headerContext.projectGroup
                    ?.trim()
                    ?.removeSuffix(".")
                    ?.takeIf { it.isNotEmpty() }
            if (groupPrefix != null && root in STDLIB_CRATE_ROOTS) {
                if (mapped.size == 3 &&
                    mapped[1] == "cell" &&
                    mapped[2] == "OnceCell"
                ) {
                    "${CoreIntrinsicLowering.resolveCoreNamespace(headerContext)}.cell.once.OnceCell"
                } else if (mapped.size == 3 &&
                    mapped[1] == "cell" &&
                    mapped[2] in setOf("Cell", "RefCell", "Ref", "RefMut")
                ) {
                    "${CoreIntrinsicLowering.resolveCoreNamespace(headerContext)}.cell.${mapped[2]}"
                } else {
                    null
                }
            } else if ((headerContext.portLintDirective == "tests" || headerContext.testClassName != null) &&
                root !in STDLIB_CRATE_ROOTS &&
                crateKey !in headerContext.crateNamespaces
            ) {
                null
            } else if (groupPrefix != null && !joined.startsWith("$groupPrefix.")) {
                "$groupPrefix.$joined"
            } else {
                joined
            }
        }
    }
}

internal fun KotlinJ2KEmitter.renderItemUse(
    item: Item.Use,
    indent: String,
    bodyTextForPubUseFilter: String? = null,
): String {
    val leaves = mutableListOf<UseLeaf>()
    collectUseLeaves(item.tree, emptyList(), leaves)
    val lines = mutableListOf<String>()
    for (leaf in leaves) {
        val root = leaf.segments.firstOrNull()
        if (leaf.isSelfImport && root in STDLIB_CRATE_ROOTS) {
            continue
        }
        if (root in STDLIB_CRATE_ROOTS && leaf.segments.size == 2 && leaf.segments[1].firstOrNull()?.isLowerCase() == true) {
            continue
        }
        if (root in STDLIB_CRATE_ROOTS &&
            leaf.segments.getOrNull(1)?.removePrefix("r#") in setOf("f32", "f64", "consts")
        ) {
            continue
        }
        if (root == "crate" && leaf.segments.getOrNull(1) == "drops" && leaf.segments.lastOrNull() in setOf("NoDrop", "TrivialDrop")) {
            continue
        }
        val fullImport = resolveKotlinImportPath(leaf.segments, leaf.alias, leaf.isSelfImport) ?: continue
        if (isResolvedCrateSubpackageModule(fullImport, leaf.segments.last())) {
            continue
        }
        val finalSimpleName = fullImport.substringAfterLast('.')
        if (leaf.alias == null &&
            root in STDLIB_CRATE_ROOTS &&
            (finalSimpleName in KOTLIN_STDLIB_SHADOWED_IMPORTS || finalSimpleName in STDLIB_ERASED_OR_BUILTIN_IMPORTS)
        ) {
            continue
        }
        val importPkg = fullImport.substringBeforeLast('.', missingDelimiterValue = "")
        if (leaf.alias == null && headerContext.packageName != null && importPkg == headerContext.packageName) {
            continue
        }
        val lastSeg = leaf.segments.last()
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
        if (localBoundIdent in STDLIB_ERASED_OR_BUILTIN_IMPORTS && root in STDLIB_CRATE_ROOTS) {
            continue
        }
        if (leaf.alias == null &&
            (
                localBoundIdent in inspector.declaredTypeNames ||
                    localBoundIdent in inspector.declaredConstNames.values ||
                    localBoundIdent in inspector.declaredConstNames.keys ||
                    isDeclaredTopLevelFnKotlinName(localBoundIdent)
            )
        ) {
            continue
        }
        if (item.vis is Visibility.Public && localBoundIdent == "*") {
            continue
        }
        val shouldFilterUnused =
            item.vis is Visibility.Public ||
                TranslationSession.sharedModuleFile != null ||
                headerContext.isModuleRootSource ||
                headerContext.isBatchCrateOutput ||
                localBoundIdent in witnessedSuppressedTraitNames
        if (shouldFilterUnused &&
            bodyTextForPubUseFilter != null &&
            localBoundIdent != "*" &&
            !containsWholeIdentifier(bodyTextForPubUseFilter, localBoundIdent)
        ) {
            continue
        }
        if (localBoundIdent != "*" && importPkg.isNotEmpty() && localBoundIdent !in importedSimpleNameToPkg) {
            importedSimpleNameToPkg[localBoundIdent] = importPkg
        }
        val aliasSuffix = aliasIdent?.takeIf { it != finalSimpleName }?.let { " as $it" }.orEmpty()
        lines += "${indent}import $fullImport$aliasSuffix"
    }
    return lines.distinct().joinToString("\n")
}

internal fun KotlinJ2KEmitter.collectUseLeaves(
    tree: UseTree,
    prefix: List<String>,
    out: MutableList<UseLeaf>,
) {
    when (tree) {
        is UseTree.Path -> {
            val seg = tree.ident.toString()
            val child = tree.tree
            if (child != null) {
                collectUseLeaves(child, prefix + seg, out)
            }
        }
        is UseTree.Name -> {
            val seg = tree.ident.toString()
            val alias =
                tree.rename
                    ?.ident
                    ?.toString()
                    ?.removePrefix("r#")
                    ?.takeIf { it != "_" }
            if (seg != "self") {
                out.add(UseLeaf(segments = prefix + seg, alias = alias, isSelfImport = false))
            } else if (prefix.isNotEmpty()) {
                out.add(UseLeaf(segments = prefix, alias = alias, isSelfImport = true))
            }
        }
        is UseTree.Glob -> {
            out.add(UseLeaf(segments = prefix + "*", alias = null, isSelfImport = false))
        }
        is UseTree.Group -> {
            for (sub in tree.items.toList()) {
                collectUseLeaves(sub, prefix, out)
            }
        }
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

private val UNPORTED_QUICKCHECK_IDENTS =
    setOf(
        "quickcheck",
        "QuickCheck",
        "StdGen",
        "TestResult",
        "Arbitrary",
    )

private val UNPORTED_RAND_TEST_IDENTS =
    setOf(
        "rand",
        "Rng",
        "RngCore",
        "SeedableRng",
        "SmallRng",
        "StdRng",
        "ThreadRng",
        "thread_rng",
    )

private fun extractItemAttrs(item: Item): List<Attribute> =
    when (item) {
        is Item.Const -> item.attrs
        is Item.Enum -> item.attrs
        is Item.ExternCrate -> item.attrs
        is Item.Fn -> item.attrs
        is Item.ForeignMod -> item.attrs
        is Item.Impl -> item.attrs
        is Item.Macro -> item.attrs
        is Item.Mod -> item.attrs
        is Item.Static -> item.attrs
        is Item.Struct -> item.attrs
        is Item.Trait -> item.attrs
        is Item.TraitAlias -> item.attrs
        is Item.ItemType -> item.attrs
        is Item.Union -> item.attrs
        is Item.Use -> item.attrs
        is Item.Verbatim -> emptyList()
    }

internal fun KotlinJ2KEmitter.itemDependsOnUnportedQuickcheckOrRand(item: Item): Boolean {
    val unportedQuickcheck = "quickcheck" !in headerContext.crateNamespaces
    val unportedRand = "rand" !in headerContext.crateNamespaces
    if (!unportedQuickcheck && !unportedRand) return false
    if (item is Item.Mod || item is Item.Use) return false
    if (item is Item.Macro) {
        val macroName =
            item.mac.path.segments
                .toList()
                .lastOrNull()
                ?.ident
                ?.toString()
                ?.removePrefix("r#")
        if (unportedQuickcheck && macroName == "quickcheck") return true
        if (unportedRand && macroName == "rand") return true
    }
    val inTestContext =
        headerContext.portLintDirective == "tests" ||
            headerContext.testClassName != null ||
            currentModPathPrefix.any { it == "test" || it == "tests" || it == "stdtests" } ||
            hasTestAttribute(extractItemAttrs(item))
    val importedQuickcheckGen =
        unportedQuickcheck &&
            inspector.allUseItems.any { useItem ->
                val leaves = mutableListOf<UseLeaf>()
                collectUseLeaves(useItem.tree, emptyList(), leaves)
                leaves.any { leaf ->
                    leaf.segments.firstOrNull()?.removePrefix("r#") == "quickcheck" &&
                        (leaf.segments.lastOrNull() == "*" || leaf.segments.lastOrNull()?.removePrefix("r#") == "Gen")
                }
            }

    fun scanTokens(stream: TokenStream): Boolean {
        val list = stream.toList()
        var idx = 0
        while (idx < list.size) {
            when (val tt = list[idx]) {
                is TokenTree.Ident -> {
                    val raw = tt.value.toString().removePrefix("r#")
                    val nextIsPathSep =
                        (idx + 2 < list.size) &&
                            list[idx + 1] is TokenTree.Punct &&
                            (list[idx + 1] as TokenTree.Punct).value.asChar() == ':' &&
                            list[idx + 2] is TokenTree.Punct &&
                            (list[idx + 2] as TokenTree.Punct).value.asChar() == ':'
                    if (unportedQuickcheck) {
                        if ((raw == "quickcheck" && nextIsPathSep) ||
                            (inTestContext && raw in UNPORTED_QUICKCHECK_IDENTS) ||
                            (inTestContext && importedQuickcheckGen && raw == "Gen")
                        ) {
                            return true
                        }
                    }
                    if (unportedRand) {
                        if ((raw == "rand" && nextIsPathSep) ||
                            (inTestContext && raw in UNPORTED_RAND_TEST_IDENTS)
                        ) {
                            return true
                        }
                    }
                }
                is TokenTree.Group -> {
                    if (scanTokens(tt.value.stream())) return true
                }
                else -> {}
            }
            idx++
        }
        return false
    }

    return scanTokens(item.toTokenStream())
}

private const val OMITTED_QUICKCHECK_OR_RAND_COMMENT: String =
    "// Omitted quickcheck/rand property test items pending quickcheck-kotlin / rand-kotlin port (Hermes Kanban t_19971811)."

private fun KotlinJ2KEmitter.isQuickcheckOrRandTestContext(item: Item? = null): Boolean =
    headerContext.portLintDirective == "tests" ||
        headerContext.testClassName != null ||
        currentModPathPrefix.any { it == "test" || it == "tests" || it == "stdtests" } ||
        (item != null && hasTestAttribute(extractItemAttrs(item)))

private fun itemTokenStreamContainsAnyIdent(
    stream: TokenStream,
    idents: Set<String>,
): Boolean {
    if (idents.isEmpty()) return false
    for (tt in stream.clone().toList()) {
        when (tt) {
            is TokenTree.Ident -> {
                if (tt.value.toString().removePrefix("r#") in idents) return true
            }
            is TokenTree.Group -> {
                if (itemTokenStreamContainsAnyIdent(tt.value.stream(), idents)) return true
            }
            else -> {}
        }
    }
    return false
}

private fun KotlinJ2KEmitter.declaredOmittedQuickcheckIdents(item: Item): Set<String> =
    when (item) {
        is Item.Fn ->
            setOf(
                item.sig.ident
                    .toString()
                    .removePrefix("r#"),
            )
        is Item.Struct -> setOf(item.ident.toString().removePrefix("r#"))
        is Item.Enum -> setOf(item.ident.toString().removePrefix("r#"))
        is Item.Union -> setOf(item.ident.toString().removePrefix("r#"))
        is Item.ItemType -> setOf(item.ident.toString().removePrefix("r#"))
        is Item.Const -> setOf(item.ident.toString().removePrefix("r#"))
        is Item.Static -> setOf(item.ident.toString().removePrefix("r#"))
        is Item.Impl -> {
            val unportedQuickcheck = "quickcheck" !in headerContext.crateNamespaces
            val unportedRand = "rand" !in headerContext.crateNamespaces
            val traitLast =
                item.traitPath
                    ?.path
                    ?.segments
                    ?.toList()
                    ?.lastOrNull()
                    ?.ident
                    ?.toString()
                    ?.removePrefix("r#")
            if ((unportedQuickcheck && traitLast == "Arbitrary") ||
                (unportedRand && traitLast in UNPORTED_RAND_TEST_IDENTS)
            ) {
                val selfRaw =
                    (unwrapSynType(item.selfType) as? SynType.Path)
                        ?.takeIf { it.qself == null }
                        ?.path
                        ?.segments
                        ?.toList()
                        ?.lastOrNull()
                        ?.ident
                        ?.toString()
                        ?.removePrefix("r#")
                listOfNotNull(selfRaw, selfRaw?.let(::rustTypeIdentToKotlin)).toSet()
            } else {
                emptySet()
            }
        }
        else -> emptySet()
    }

internal fun KotlinJ2KEmitter.computeTransitivelyOmittedQuickcheckOrRandIdents(
    items: List<Item>,
    parentOmitted: Set<String>,
): Set<String> {
    if ("quickcheck" in headerContext.crateNamespaces && "rand" in headerContext.crateNamespaces) {
        return emptySet()
    }
    val omitted = parentOmitted.toMutableSet()
    var changed = true
    while (changed) {
        changed = false
        for (item in items) {
            if (item is Item.Mod || item is Item.Use || itemHasDocOnlyCfg(item)) continue
            if (!isQuickcheckOrRandTestContext(item)) continue
            val directHit = itemDependsOnUnportedQuickcheckOrRand(item)
            val declared = declaredOmittedQuickcheckIdents(item)
            val transitiveHit =
                !directHit &&
                    (
                        declared.any { it in omitted } ||
                            itemTokenStreamContainsAnyIdent(item.toTokenStream(), omitted)
                    )
            if (directHit || transitiveHit) {
                if (omitted.addAll(declared)) {
                    changed = true
                }
            }
        }
    }
    return omitted
}

internal fun KotlinJ2KEmitter.shouldOmitItemForUnportedQuickcheckOrRand(item: Item): Boolean {
    if (itemDependsOnUnportedQuickcheckOrRand(item)) return true
    if (item is Item.Mod || item is Item.Use) return false
    if (!isQuickcheckOrRandTestContext(item) || currentOmittedTestIdents.isEmpty()) return false
    return declaredOmittedQuickcheckIdents(item).any { it in currentOmittedTestIdents } ||
        itemTokenStreamContainsAnyIdent(item.toTokenStream(), currentOmittedTestIdents)
}

internal fun indentBlockByFourSpaces(text: String): String =
    text.lines().joinToString("\n") { line ->
        if (line.isEmpty()) "" else "    $line"
    }

internal fun KotlinJ2KEmitter.allocateTopLevelTestSuiteClassName(
    candidate: String,
    scopeSignatures: MutableMap<String, Int>,
): String {
    var resolved = candidate
    var suffix = 2
    while (resolved in inspector.declaredTypeNames ||
        "type:$resolved" in scopeSignatures ||
        resolved in scopeSignatures
    ) {
        resolved = "$candidate$suffix"
        suffix++
    }
    scopeSignatures["type:$resolved"] = 1
    scopeSignatures[resolved] = 1
    return resolved
}

internal fun KotlinJ2KEmitter.resolveModTestSuiteCandidateName(): String {
    val fileTestBase = headerContext.testClassName
    val nonGenericSegs =
        currentModPathPrefix.filterNot {
            val lower = it.lowercase()
            lower == "test" || lower == "tests"
        }
    if (fileTestBase != null) {
        if (nonGenericSegs.isEmpty()) return fileTestBase
        val suffixKind = if (fileTestBase.endsWith("InlineTests")) "InlineTests" else "Tests"
        val stem = fileTestBase.removeSuffix("InlineTests").removeSuffix("Tests").ifEmpty { fileTestBase }
        val modSuffix = nonGenericSegs.joinToString("") { rustTypeIdentToKotlin(it) }
        return "$stem$modSuffix$suffixKind"
    }
    if (nonGenericSegs.isEmpty()) return "InlineTests"
    return nonGenericSegs.joinToString("") { rustTypeIdentToKotlin(it) } + "Tests"
}

internal fun KotlinJ2KEmitter.renderItemMod(
    item: Item.Mod,
    indent: String,
    scopeSignatures: MutableMap<String, Int> = topLevelSignatureCounts,
): String {
    val content = item.content as? ModContent.Inline ?: return ""
    if (item.vis !is Visibility.Public &&
        item.ident.toString() in setOf("private", "sealed") &&
        content.items.all { sub ->
            sub is Item.Use ||
                (sub is Item.Trait && sub.ident.toString() == "Sealed" && sub.items.isEmpty()) ||
                (
                    sub is Item.Impl && sub.traitPath
                        ?.path
                        ?.segments
                        ?.last()
                        ?.ident
                        ?.toString() == "Sealed" && sub.items.isEmpty()
                )
        }
    ) {
        return ""
    }
    val modRawName = item.ident.toString().removePrefix("r#")
    val prevModPath = currentModPathPrefix
    currentModPathPrefix = prevModPath + modRawName
    scopeBuilder.enterModuleSubscope()
    return try {
        val prevOmittedTestIdents = currentOmittedTestIdents
        try {
            val visibleItems = content.items.filterNot { itemHasDocOnlyCfg(it) }
            currentOmittedTestIdents =
                computeTransitivelyOmittedQuickcheckOrRandIdents(visibleItems, prevOmittedTestIdents)
            val modAttachableNames =
                visibleItems
                    .filterNot { shouldOmitItemForUnportedQuickcheckOrRand(it) }
                    .mapNotNull { sub ->
                        when (sub) {
                            is Item.Struct -> rustTypeIdentToKotlin(sub.ident.toString())
                            is Item.Enum -> rustTypeIdentToKotlin(sub.ident.toString())
                            is Item.Union -> rustTypeIdentToKotlin(sub.ident.toString())
                            else -> null
                        }
                    }.toSet()
            val modLocalTypeImpls = linkedMapOf<String, MutableList<Item.Impl>>()
            val standaloneModItems = mutableListOf<Item>()
            val nonTestOutputs = mutableListOf<String>()
            val modTestFns = mutableListOf<String>()
            val nestedModOutputs = mutableListOf<String>()
            val modTestSignatures = linkedMapOf<String, Int>()
            var emittedOmissionComment = false
            for (sub in visibleItems) {
                if (sub is Item.Use) {
                    registerUseItemInScope(sub)
                    continue
                }
                if (shouldOmitItemForUnportedQuickcheckOrRand(sub)) {
                    if (!emittedOmissionComment) {
                        nonTestOutputs += "$indent$OMITTED_QUICKCHECK_OR_RAND_COMMENT"
                        emittedOmissionComment = true
                    }
                    continue
                }
                if (sub is Item.Impl) {
                    val ownerType = extractDirectOwnerTypeName(sub.selfType, sub.generics)
                    if (ownerType != null && ownerType in modAttachableNames) {
                        modLocalTypeImpls.getOrPut(ownerType) { mutableListOf() }.add(sub)
                        continue
                    }
                }
                standaloneModItems += sub
            }
            for (sub in standaloneModItems) {
                val isTestFn = sub is Item.Fn && hasTestAttribute(sub.attrs)
                val targetSignatures = if (isTestFn) modTestSignatures else scopeSignatures
                val r = renderItem(sub, indent, modLocalTypeImpls, scopeSignatures = targetSignatures).trimEnd()
                if (r.isBlank()) continue
                when {
                    sub is Item.Mod -> nestedModOutputs += r
                    isTestFn && r.lines().any { it.trimStart() == "@kotlin.test.Test" } -> modTestFns += r
                    else -> nonTestOutputs += r
                }
            }
            if (modTestFns.isNotEmpty()) {
                if (indent.isEmpty()) {
                    val suiteName = allocateTopLevelTestSuiteClassName(resolveModTestSuiteCandidateName(), scopeSignatures)
                    val suiteBody = modTestFns.joinToString("\n\n") { indentBlockByFourSpaces(it) }
                    nonTestOutputs += "public class $suiteName {\n$suiteBody\n}"
                } else {
                    nonTestOutputs += modTestFns
                }
            }
            val combined = mutableListOf<String>()
            for (part in nonTestOutputs + nestedModOutputs) {
                if (part.trim() == OMITTED_QUICKCHECK_OR_RAND_COMMENT) {
                    if (emittedOmissionComment && combined.any { it.trim() == OMITTED_QUICKCHECK_OR_RAND_COMMENT }) {
                        continue
                    }
                    emittedOmissionComment = true
                }
                combined += part
            }
            combined.joinToString("\n\n")
        } finally {
            currentOmittedTestIdents = prevOmittedTestIdents
        }
    } finally {
        currentModPathPrefix = prevModPath
        scopeBuilder.exitModuleSubscope()
    }
}

internal fun KotlinJ2KEmitter.renderItemMacro(
    item: Item.Macro,
    indent: String,
    isLocalItem: Boolean = false,
): String {
    if (item.mac.path.isIdent("macro_rules")) {
        return ""
    }
    val lastMacroIdent =
        item.mac.path.segments
            .last()
            ?.ident
            ?.toString()
    if (lastMacroIdent == "include") {
        return ""
    }
    if (isWindowsLinkMacroPath(item.mac.path)) {
        return renderWindowsLinkMacro(item, indent)
    }
    if (!isLocalItem) {
        ffiCollector.recordTopLevelInlineMacro(item.mac)
        return ""
    }
    val rendered = renderMacroInvocationAsStmtOrExpr(item.mac, indent, selfTypeName = null)
    return if (rendered.isBlank()) "" else "$indent$rendered"
}

internal fun KotlinJ2KEmitter.isWindowsLinkMacroPath(path: Path): Boolean {
    val segs = path.segments.toList().map { it.ident.toString() }
    return (segs.size == 2 && (segs[0] == "windows_link" || segs[0] == "windows_targets") && segs[1] == "link") ||
        segs.lastOrNull() == "link"
}

internal fun KotlinJ2KEmitter.renderWindowsLinkMacro(item: Item.Macro, indent: String): String {
    val macTokens = item.mac.tokens
    val trees = macTokens.clone().toList()
    val fnIdx =
        trees.indexOfFirst { tt ->
            tt is TokenTree.Ident && tt.value.toString() == "fn"
        }
    if (fnIdx < 0) return ""
    val leadingStrings = mutableListOf<String>()
    for (i in 0 until fnIdx) {
        val tt = trees[i]
        if (tt is TokenTree.Literal) {
            val lit = Lit.new(tt.value)
            if (lit is Lit.Str) {
                leadingStrings += lit.value.value()
            }
        }
    }
    val dllName = leadingStrings.getOrNull(0).orEmpty()
    val customSymbol = leadingStrings.getOrNull(2)
    val fnTokens = trees.subList(fnIdx, trees.size)
    val pubTokens = TokenStream.fromString("pub").getOrThrow().toList()
    val hasTrailingSemi = (fnTokens.lastOrNull() as? TokenTree.Punct)?.value?.asChar() == ';'
    val semiTokens = if (hasTrailingSemi) emptyList() else TokenStream.fromString(";").getOrThrow().toList()
    val braceBody = TokenTree.Group(Group(Delimiter.Brace, TokenStream.fromTokenTrees(pubTokens + fnTokens + semiTokens)))
    val externPrefix = TokenStream.fromString("extern \"C\"").getOrThrow().toList()
    val wrappedStream = TokenStream.fromTokenTrees(externPrefix + braceBody)
    val parsedFile =
        io.github.kotlinmania.syn
            .parse2(io.github.kotlinmania.syn.FileParse::parse, wrappedStream)
            .getOrNull() ?: return ""
    val foreignMod = parsedFile.items.firstOrNull() as? Item.ForeignMod ?: return ""
    val foreignFn = foreignMod.items.firstOrNull() as? ForeignItem.Fn ?: return ""

    val foreignIdent = foreignFn.sig.ident.toString()
    val rawFnName = foreignIdent.removePrefix("r#")
    val fnName = rustFunctionIdentToKotlin(rawFnName)
    val exportedSymbol = customSymbol ?: rawFnName
    val trivia = renderLeadingTrivia(item, indent)
    val doc = renderKDocFromAttributes(item.attrs + foreignFn.attrs, indent)

    val interopParams = mutableListOf<InteropParamSpec>()
    scopeBuilder.enterDef()
    val (sig, retRendered) =
        try {
            renderForeignFunctionExpectSignature(
                sig = foreignFn.sig,
                visPrefix = "public ",
                fnName = fnName,
                scopeSignatures = topLevelSignatureCounts,
                interopParamsOut = interopParams,
            )
        } finally {
            scopeBuilder.exitDef()
        } ?: return ""
    val retSynType = extractReturnSynType(foreignFn.sig.output)
    ffiCollector.recordForeignFunction(
        visibilityPrefix = "public ",
        kotlinName = fnName,
        rawRustName = exportedSymbol,
        attrs = item.attrs + foreignFn.attrs,
        params = interopParams,
        kotlinReturnType = retRendered,
        returnSynType = retSynType,
        isVariadic = foreignFn.sig.variadic != null,
        returnDefaultExpr = if (retRendered == "Unit") "" else renderFallbackDefaultForRenderedType(retRendered),
        dllName = dllName,
    )
    return "$trivia$doc$indent$sig"
}

internal fun KotlinJ2KEmitter.renderImplOrTraitMacroMembers(
    mac: Macro,
    indent: String,
    selfTypeName: String?,
    ownerGenerics: Generics?,
    scopeSignatures: MutableMap<String, Int>,
    asTopLevelExtension: Boolean,
    isInterface: Boolean,
    isLocalItem: Boolean = false,
): List<String> {
    val macroName =
        mac.path.segments
            .last()
            ?.ident
            ?.toString()
    if (macroName == "forward_to_deserialize_any") {
        return renderForwardToDeserializeAnyMembers(
            mac = mac,
            indent = indent,
            selfTypeName = selfTypeName,
            ownerGenerics = ownerGenerics,
            scopeSignatures = scopeSignatures,
            asTopLevelExtension = asTopLevelExtension,
            isInterface = isInterface,
            isLocalItem = isLocalItem,
        )
    }
    if (isInterface || asTopLevelExtension) {
        return emptyList()
    }
    val expanded = renderMacroInvocationAsStmtOrExpr(mac, "$indent    ", selfTypeName)
    return if (expanded.isBlank()) {
        emptyList()
    } else {
        listOf("${indent}init {\n$indent    $expanded\n$indent}")
    }
}

internal fun KotlinJ2KEmitter.renderForwardToDeserializeAnyMembers(
    mac: Macro,
    indent: String,
    selfTypeName: String?,
    ownerGenerics: Generics?,
    scopeSignatures: MutableMap<String, Int>,
    asTopLevelExtension: Boolean,
    isInterface: Boolean,
    isLocalItem: Boolean,
): List<String> {
    var angleDepth = 0
    val keys = mutableListOf<String>()
    for (tt in mac.tokens.clone().toList()) {
        when (tt) {
            is TokenTree.Punct -> {
                when (tt.value.asChar()) {
                    '<' -> angleDepth++
                    '>' -> if (angleDepth > 0) angleDepth--
                }
            }
            is TokenTree.Ident -> {
                if (angleDepth == 0) {
                    val raw = tt.value.toString().removePrefix("r#")
                    if (raw in FORWARD_DESERIALIZE_KEYS) {
                        keys += raw
                    }
                }
            }
            else -> {}
        }
    }
    if (keys.isEmpty()) return emptyList()

    val ownerParams = ownerGenerics?.params?.toList().orEmpty()
    val ownerTypeParamNames =
        ownerParams
            .mapNotNull { param ->
                (param as? GenericParam.TypeParam)?.ident?.toString()?.let(::rustTypeIdentToKotlin)
            }.toSet()
    val visitorParam = if ("V" in ownerTypeParamNames) "W" else "V"
    val ownerTypeParamsRendered =
        if (asTopLevelExtension) {
            renderTypeParametersFromParams(ownerParams, selfTypeName)
                .removePrefix("<")
                .removeSuffix(">")
                .takeIf { it.isNotEmpty() }
        } else {
            null
        }
    val typeParamsStr =
        if (ownerTypeParamsRendered != null) {
            "<$ownerTypeParamsRendered, $visitorParam : Visitor>"
        } else {
            "<$visitorParam : Visitor>"
        }
    val whereClause =
        if (asTopLevelExtension) {
            renderCombinedWhereClause(
                ownerWhere = ownerGenerics?.whereClause,
                fnWhere = null,
                params = ownerParams,
                selfTypeName = selfTypeName,
                extraDeclaredNames = setOf(visitorParam),
            )
        } else {
            ""
        }

    val vis = if (isLocalItem) "" else "public "
    val openMod = if (!isInterface && !asTopLevelExtension && !isLocalItem && indent.isNotEmpty()) "open " else ""
    val receiverPrefix = if (asTopLevelExtension && !selfTypeName.isNullOrBlank()) "$selfTypeName." else ""
    val receiverKey = computeReceiverSignatureKey(receiverPrefix)
    val errorType = currentImplAssocTypes["Error"]?.let { renderType(it, selfTypeName) } ?: "Error"

    val out = mutableListOf<String>()
    for (key in keys) {
        val fnName = rustFunctionIdentToKotlin("deserialize_$key")
        val (extraParams, extraErased) = forwardDeserializeExtraParams(key)
        val allParams = extraParams + "visitor: $visitorParam"
        val allErased = extraErased + visitorParam
        val sigKey = "$receiverKey$fnName(${allErased.joinToString(",")})"
        val seenCount = scopeSignatures[sigKey] ?: 0
        scopeSignatures[sigKey] = seenCount + 1
        if (seenCount > 0) continue

        val sigHeader =
            "${vis}${openMod}fun $typeParamsStr ${receiverPrefix}$fnName(${allParams.joinToString(", ")}): Result<Any?, $errorType>$whereClause"
        out += "$indent$sigHeader {\n$indent    return this.deserializeAny(visitor)\n$indent}"
    }
    return out
}

internal fun KotlinJ2KEmitter.forwardDeserializeExtraParams(key: String): Pair<List<String>, List<String>> =
    when (key) {
        "unit_struct", "newtype_struct" -> listOf("name: String") to listOf("String")
        "tuple" -> listOf("len: Int") to listOf("Int")
        "tuple_struct" -> listOf("name: String", "len: Int") to listOf("String", "Int")
        "struct" -> listOf("name: String", "fields: List<String>") to listOf("String", "List")
        "enum" -> listOf("name: String", "variants: List<String>") to listOf("String", "List")
        else -> emptyList<String>() to emptyList()
    }

internal fun KotlinJ2KEmitter.renderItemForeignMod(
    item: Item.ForeignMod,
    indent: String,
    isLocalItem: Boolean = false,
): String {
    val modLinkLibrary =
        if (!isLocalItem) {
            ffiCollector.recordForeignModAttributes(item.attrs)
        } else {
            null
        }
    val modComments =
        (renderLeadingTrivia(item, indent) + renderKDocFromAttributes(item.attrs, indent))
            .trimEnd()
    val out = mutableListOf<String>()
    for (foreign in item.items) {
        if (foreignItemHasDocOnlyCfg(foreign)) continue
        when (foreign) {
            is ForeignItem.Fn -> {
                val vis = if (isLocalItem) "" else renderVisibility(foreign.vis)
                val rawFnName =
                    foreign.sig.ident
                        .toString()
                        .removePrefix("r#")
                val fnName = rustFunctionIdentToKotlin(rawFnName)
                val interopParams = mutableListOf<InteropParamSpec>()
                scopeBuilder.enterDef()
                val (sig, retRendered) =
                    try {
                        renderForeignFunctionExpectSignature(
                            sig = foreign.sig,
                            visPrefix = vis,
                            fnName = fnName,
                            scopeSignatures = topLevelSignatureCounts,
                            interopParamsOut = interopParams,
                        )
                    } finally {
                        scopeBuilder.exitDef()
                    } ?: continue
                val fnTrivia = renderLeadingTrivia(foreign, indent)
                val doc = renderKDocFromAttributes(foreign.attrs, indent)
                val retDefault = if (retRendered == "Unit") "" else renderFallbackDefaultForRenderedType(retRendered)
                val rustAllocShimBody =
                    when (rawFnName) {
                        "__rust_alloc",
                        "__rust_alloc_zeroed",
                        "__rust_dealloc",
                        "__rust_realloc",
                        "__rust_no_alloc_shim_is_unstable",
                        "__rust_no_alloc_shim_is_unstable_v2",
                        -> {
                            val coreNs = CoreIntrinsicLowering.resolveCoreNamespace(headerContext)
                            val pNames = interopParams.map { it.kotlinName }
                            fun ptrArgToLong(idx: Int): String =
                                if (TranslationSession.rawAbiMode) pNames[idx] else "${pNames[idx]}.rawAddress"
                            fun wrapLongToRet(expr: String): String =
                                if (TranslationSession.rawAbiMode) {
                                    expr
                                } else {
                                    CoreIntrinsicLowering.registerCoreImport(this, "$coreNs.ptr.MutPtr")
                                    "MutPtr($expr)"
                                }
                            when (rawFnName) {
                                "__rust_alloc" -> {
                                    CoreIntrinsicLowering.registerCoreImport(this, "$coreNs.intrinsics.kmAlloc")
                                    "= ${wrapLongToRet("kmAlloc(${pNames[0]}.toLong(), ${pNames[1]}.toLong())")}"
                                }
                                "__rust_alloc_zeroed" -> {
                                    CoreIntrinsicLowering.registerCoreImport(this, "$coreNs.intrinsics.kmAllocZeroed")
                                    "= ${wrapLongToRet("kmAllocZeroed(${pNames[0]}.toLong(), ${pNames[1]}.toLong())")}"
                                }
                                "__rust_dealloc" -> {
                                    CoreIntrinsicLowering.registerCoreImport(this, "$coreNs.intrinsics.kmDealloc")
                                    "= kmDealloc(${ptrArgToLong(0)}, ${pNames[1]}.toLong(), ${pNames[2]}.toLong())"
                                }
                                "__rust_realloc" -> {
                                    CoreIntrinsicLowering.registerCoreImport(this, "$coreNs.intrinsics.kmRealloc")
                                    "= ${wrapLongToRet("kmRealloc(${ptrArgToLong(0)}, ${pNames[1]}.toLong(), ${pNames[2]}.toLong(), ${pNames[3]}.toLong())")}"
                                }
                                else -> "= Unit"
                            }
                        }
                        else -> null
                    }
                if (rustAllocShimBody != null) {
                    val directSig = sig.replaceFirst("expect fun ", "fun ")
                    out += "$fnTrivia$doc$indent$directSig $rustAllocShimBody"
                } else if (isLocalItem) {
                    val localSig = sig.replaceFirst("expect fun ", "fun ")
                    val localBody = if (retRendered == "Unit") "{}" else "= $retDefault"
                    out += "$fnTrivia$doc$indent$localSig $localBody"
                } else {
                    val retType = extractReturnSynType(foreign.sig.output)
                    ffiCollector.recordForeignFunction(
                        visibilityPrefix = vis,
                        kotlinName = fnName,
                        rawRustName = rawFnName,
                        attrs = foreign.attrs,
                        params = interopParams,
                        kotlinReturnType = retRendered,
                        returnSynType = retType,
                        isVariadic = foreign.sig.variadic != null,
                        returnDefaultExpr = retDefault,
                        linkLibrary = modLinkLibrary,
                    )
                    out += "$fnTrivia$doc$indent$sig"
                }
            }
            is ForeignItem.Static -> {
                val stTrivia = renderLeadingTrivia(foreign, indent)
                val doc = renderKDocFromAttributes(foreign.attrs, indent)
                val vis = if (isLocalItem) "" else renderVisibility(foreign.vis)
                val rawIdent = foreign.ident.toString().removePrefix("r#")
                val ktConst = inspector.declaredConstNames[rawIdent] ?: rustConstIdentToKotlin(rawIdent)
                if (rawIdent !in inspector.declaredConstNames) {
                    inspector.declaredConstNames[rawIdent] = ktConst
                }
                val name = disambiguateName(ktConst, topLevelSignatureCounts)
                val prevExternAbi = inExternAbiSignature
                inExternAbiSignature = true
                val ty =
                    try {
                        renderType(foreign.ty, null)
                    } finally {
                        inExternAbiSignature = prevExternAbi
                    }
                val isMut = foreign.mutability is StaticMutability.Mut
                val kw = if (isMut) "var" else "val"
                val defaultExpr = renderFallbackDefaultForRenderedType(ty)
                if (rawIdent == "__rust_no_alloc_shim_is_unstable" || rawIdent == "__rust_no_alloc_shim_is_unstable_v2") {
                    out += "$stTrivia$doc$indent$vis$kw $name: $ty = $defaultExpr"
                } else if (isLocalItem) {
                    out += "$stTrivia$doc$indent$vis$kw $name: $ty = $defaultExpr"
                } else {
                    ffiCollector.recordForeignStatic(
                        visibilityPrefix = vis,
                        kotlinName = name,
                        rawRustName = rawIdent,
                        attrs = foreign.attrs,
                        synType = foreign.ty,
                        kotlinType = ty,
                        isMutable = isMut,
                        defaultExpr = defaultExpr,
                    )
                    out += "$stTrivia$doc$indent${vis}expect $kw $name: $ty"
                }
            }
            else -> {}
        }
    }
    if (modComments.isNotBlank() && out.isNotEmpty()) {
        out[0] = "$modComments\n${out[0]}"
    }
    return out.joinToString("\n\n")
}

internal fun KotlinJ2KEmitter.renderForeignFunctionExpectSignature(
    sig: Signature,
    visPrefix: String,
    fnName: String,
    scopeSignatures: MutableMap<String, Int>,
    interopParamsOut: MutableList<InteropParamSpec>,
): Pair<String, String>? {
    val prevExternAbi = inExternAbiSignature
    inExternAbiSignature = true
    return try {
        val combinedParams = sig.generics.params.toList()
        val typeParamsStr = renderTypeParametersFromParams(combinedParams, null)
        val params = mutableListOf<String>()
        val usedParamNames = hashSetOf<String>()
        var paramIdx = 0
        for (arg in sig.inputs.toList()) {
            if (arg is FnArg.Typed) {
                val rawParam = extractRawParamIdent(arg.patType.pat)
                val basePName =
                    if (rawParam != null && rawParam != "_") {
                        scopeBuilder
                            .bindLocal(
                                rawName = rawParam,
                                baseKotlinName = rustValueIdentToKotlin(rawParam),
                                span = arg.patType.pat.span(),
                                assignCount = AssignCount.AtMostOnce,
                            ).kotlinName
                    } else {
                        extractParamName(arg.patType.pat, paramIdx)
                    }
                var pName = basePName
                var suffix = 2
                while (!usedParamNames.add(pName)) {
                    pName = "${basePName}_$suffix"
                    suffix++
                }
                val prevHint = currentSamNameHint
                val prevParam = inExternParamType
                currentSamNameHint = "${fnName.replaceFirstChar { it.uppercase() }}${pName.replaceFirstChar { it.uppercase() }}Callback"
                inExternParamType = true
                val pType =
                    try {
                        renderType(arg.patType.ty, null)
                    } finally {
                        inExternParamType = prevParam
                        currentSamNameHint = prevHint
                    }
                val bridge = ffiCollector.classifyParamAbiBridge(arg.patType.ty, pType)
                val cParamName = rawParam?.takeIf { it != "_" } ?: "arg$paramIdx"
                interopParamsOut +=
                    InteropParamSpec(
                        kotlinName = pName,
                        cName = cParamName,
                        kotlinType = pType,
                        cType = pType,
                        bridgeKind = bridge,
                    )
                params += "$pName: $pType"
                paramIdx++
            }
        }
        if (sig.variadic != null) {
            params += "vararg variadicArgs: Long"
        }
        val sigKey = "extern_fn:$fnName"
        val seenCount = scopeSignatures[sigKey] ?: 0
        scopeSignatures[sigKey] = seenCount + 1
        if (seenCount > 0) {
            return null
        }
        val retType = extractReturnSynType(sig.output)
        val prevHint = currentSamNameHint
        currentSamNameHint = "${fnName.replaceFirstChar { it.uppercase() }}ResultCallback"
        val renderedReturn =
            try {
                retType?.let { renderType(it, null) } ?: "Unit"
            } finally {
                currentSamNameHint = prevHint
            }
        val retTypeStr = if (renderedReturn == "Unit") "" else ": $renderedReturn"
        val whereClause = renderWhereClause(sig.generics.whereClause, null, ownerGenerics = sig.generics)
        val typeParamsPart = if (typeParamsStr.isNotEmpty()) "$typeParamsStr " else ""
        val expectSig = "${visPrefix}expect fun ${typeParamsPart}$fnName(${params.joinToString(", ")})${retTypeStr}$whereClause"
        expectSig to renderedReturn
    } finally {
        inExternAbiSignature = prevExternAbi
    }
}
