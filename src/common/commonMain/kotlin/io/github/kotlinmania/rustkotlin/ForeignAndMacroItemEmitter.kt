package io.github.kotlinmania.rustkotlin

import io.github.kotlinmania.procmacro2.Delimiter
import io.github.kotlinmania.procmacro2.Group
import io.github.kotlinmania.procmacro2.TokenStream
import io.github.kotlinmania.procmacro2.TokenTree
import io.github.kotlinmania.rustkotlin.reused.starlark.AssignCount
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
    if (boundRawAlias == null &&
        effectiveBound.firstOrNull()?.isLowerCase() == true &&
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
    if (cleaned.first().isUpperCase()) return false
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
        val libcNs = FfiInteropCollector.resolveLibcNamespace(headerContext)
        val normalizedLeafSegs =
            if (libcNs != null && leaf.segments.firstOrNull()?.removePrefix("r#") == "netc") {
                listOf("libc") + leaf.segments.drop(1)
            } else {
                leaf.segments
            }
        val rewrittenLeafSegs = rewriteCrateRootAliasAndReexportSegments(normalizedLeafSegs)
        val importedPath =
            resolveKotlinImportPath(rewrittenLeafSegs, leaf.alias, leaf.isSelfImport) ?: run {
                val head = rewrittenLeafSegs.firstOrNull()?.removePrefix("r#")
                when {
                    head == "crate" && currentNamespacePrefix() != null -> {
                        val ns = currentNamespacePrefix()!!
                        val tail =
                            rewrittenLeafSegs.drop(1).joinToString(".") {
                                rustModuleIdentToKotlin(it.removePrefix("r#").replace('-', '_'))
                            }
                        if (tail.isEmpty()) ns else "$ns.$tail"
                    }
                    head == "super" && headerContext.packageName?.contains('.') == true -> {
                        val parentPkg = headerContext.packageName.substringBeforeLast('.')
                        val tail =
                            rewrittenLeafSegs.drop(1).joinToString(".") {
                                rustModuleIdentToKotlin(it.removePrefix("r#").replace('-', '_'))
                            }
                        if (tail.isEmpty()) parentPkg else "$parentPkg.$tail"
                    }
                    else -> rewrittenLeafSegs.joinToString(".")
                }
            }
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
                val ns = currentNamespacePrefix()
                val afterNs = if (ns != null) importedPath.removePrefix("$ns.") else importedPath
                val cleanRaw = lastSeg.removePrefix("r#")
                val pascalLeaf = rustTypeIdentToKotlin(cleanRaw)
                val leafMod = rustModuleIdentToKotlin(cleanRaw.replace('-', '_'))
                when {
                    ns != null && headerContext.crateSubpackageRawLeafNames[afterNs] == cleanRaw ->
                        "$ns.$afterNs"
                    ns != null &&
                        afterNs.endsWith(".$pascalLeaf") &&
                        headerContext.crateSubpackageRawLeafNames[afterNs.removeSuffix(".$pascalLeaf")] == cleanRaw ->
                        "$ns.${afterNs.removeSuffix(".$pascalLeaf")}"
                    else -> {
                        val pkgPrefix = importedPath.substringBeforeLast('.', missingDelimiterValue = "")
                        if (pkgPrefix.isEmpty()) leafMod else "$pkgPrefix.$leafMod"
                    }
                }
            } else {
                importedPath
            }
        if (!isModuleImport && !isKnownSubpkgModule && leaf.alias != null) {
            val importPkg = normalizedImportedPath.substringBeforeLast('.', missingDelimiterValue = "")
            val finalSimple = normalizedImportedPath.substringAfterLast('.')
            if (headerContext.packageName != null &&
                importPkg == headerContext.packageName &&
                finalSimple.isNotEmpty() &&
                finalSimple != "*" &&
                kotlinName != finalSimple
            ) {
                samePackageUseRenames[kotlinName] = finalSimple
            }
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

internal fun KotlinJ2KEmitter.resolveExternalDepReexportPkg(
    explicitModule: String,
    symKt: String,
    nsPrefix: String?,
): String? {
    for (depCrate in listOf("core", "alloc", "libm")) {
        val depNs =
            headerContext.crateNamespaces[depCrate]
                ?.trim()
                ?.removeSuffix(".")
                ?.takeIf { it.isNotEmpty() } ?: continue
        if (depNs == nsPrefix) continue
        val extCandidates = headerContext.externalCrateSymbolPackages[depCrate]?.get(symKt) ?: continue
        val candidateModules =
            buildList {
                add(explicitModule)
                if (depCrate == "libm" && (explicitModule == "support" || explicitModule.startsWith("support."))) {
                    add("math.$explicitModule")
                }
            }
        for (modPath in candidateModules) {
            val exactPkg = if (modPath.isEmpty()) depNs else "$depNs.$modPath"
            val matchedPkg =
                when {
                    exactPkg in extCandidates -> exactPkg
                    else ->
                        extCandidates
                            .filter { it.startsWith("$exactPkg.") }
                            .minByOrNull { it.count { ch -> ch == '.' } }
                }
            if (matchedPkg != null) {
                return "$matchedPkg.$symKt"
            }
        }
    }
    return null
}

internal fun KotlinJ2KEmitter.resolveBestSubpackageForSymbol(ktSymbol: String): String? {
    val nsPrefix = currentNamespacePrefix()
    if (nsPrefix?.endsWith(".core") == true && ktSymbol in setOf("U128", "I128", "F16", "F128")) {
        return ""
    }
    if (nsPrefix?.endsWith(".core") == true &&
        ktSymbol in setOf("sizeOf", "sizeOfVal", "alignOf", "alignOfVal", "transmute", "transmuteUnchecked", "needsDrop", "forget", "drop") &&
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

    val unixFamilySegs = setOf("unix", "linux", "android", "apple", "macos", "ios", "tvos", "watchos", "darwin", "posix")
    val foreignPlatformSegs =
        setOf("windows", "wasi", "wasip1", "wasip2", "wasip3", "unsupported", "stubs", "hermit", "solid", "uefi", "xous", "zkvm", "motor", "sgx", "teeos", "vexos")

    val curSignificantSegs =
        curSegs
            .filter { it !in setOf("new", "sys", "pal", "mod", "bsd", "linuxlike", "freebsdlike", "netbsdlike", "solarish") }
            .toSet()

    fun osAffinityBias(candSegs: List<String>): Int {
        var bias = 0
        when {
            "windows" in curSegs -> {
                if ("windows" in candSegs) bias += 5000
                if (candSegs.any { it in unixFamilySegs } || candSegs.any { it in foreignPlatformSegs && it != "windows" }) {
                    bias -= 5000
                }
            }
            "wasi" in curSegs || "wasip1" in curSegs -> {
                if ("wasi" in candSegs) bias += 5000
                if ("wasip1" in candSegs) bias += 4000
                if ("unix" in candSegs) bias += 2000
                if (candSegs.any { it in setOf("windows", "hermit", "solid", "uefi", "xous", "zkvm", "motor", "sgx", "unsupported", "stubs") }) {
                    bias -= 5000
                }
            }
            curSegs.any { it in unixFamilySegs } -> {
                if ("unix" in candSegs) bias += 5000
                if (candSegs.any { it in unixFamilySegs && it in curSegs }) bias += 1500
                if (candSegs.any { it in foreignPlatformSegs }) {
                    bias -= 5000
                }
            }
            else -> {
                if (("unsupported" in candSegs && "unsupported" !in curSegs) || ("stubs" in candSegs && "stubs" !in curSegs)) {
                    bias -= 4000
                }
                if (candSegs.any { it in setOf("wasip1", "wasip2", "wasip3") }) {
                    bias -= 2000
                } else if ("wasi" in candSegs && "wasi" !in curSegs) {
                    bias -= 1000
                }
            }
        }
        return bias
    }

    fun score(cand: String): Int {
        val candSegs = if (cand.isEmpty()) emptyList() else cand.split('.')
        val isAncestor = cand.isEmpty() || cur.startsWith("$cand.")
        if (isAncestor) {
            return 20_000 + candSegs.size * 200
        }
        val isDescendant = cur.isEmpty() || cand.startsWith("$cur.")
        if (isDescendant) {
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

    return candidates.maxByOrNull(::score)
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
    if (afterNs == pascalLeaf || afterNs.endsWith(".$pascalLeaf")) {
        val stripped = if (afterNs == pascalLeaf) "" else afterNs.removeSuffix(".$pascalLeaf")
        if (stripped.isNotEmpty() && headerContext.crateSubpackageRawLeafNames[stripped] == cleanRaw) {
            return true
        }
        val leafMod = rustModuleIdentToKotlin(cleanRaw.replace('-', '_'))
        val candFromStripped = if (stripped.isEmpty()) leafMod else "$stripped.$leafMod"
        if (headerContext.crateSubpackageRawLeafNames[candFromStripped] == cleanRaw) return true
    }
    val parentSubpkg = afterNs.substringBeforeLast('.', missingDelimiterValue = "")
    val leafMod = rustModuleIdentToKotlin(cleanRaw.replace('-', '_'))
    val candidateSubpkg = if (parentSubpkg.isEmpty()) leafMod else "$parentSubpkg.$leafMod"
    if (headerContext.crateSubpackageRawLeafNames[candidateSubpkg] == cleanRaw) return true
    return afterNs.substringAfterLast('.') == leafMod &&
        (parentSubpkg.isEmpty() || parentSubpkg in headerContext.crateSubpackageRawLeafNames) &&
        parentSubpkg !in headerContext.crateSymbolSubpackages[rustValueIdentToKotlin(cleanRaw)].orEmpty() &&
        parentSubpkg !in headerContext.crateSymbolSubpackages[rustFunctionIdentToKotlin(cleanRaw)].orEmpty() &&
        parentSubpkg !in headerContext.crateSymbolSubpackages[rustConstIdentToKotlin(cleanRaw)].orEmpty() &&
        parentSubpkg !in headerContext.crateSymbolSubpackages[rustTypeIdentToKotlin(cleanRaw)].orEmpty()
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
    } else if (importedPkg == null) {
        val bestSub = resolveBestSubpackageForSymbol(clean)
        if (!bestSub.isNullOrEmpty()) {
            addSubpkgAndAncestors(bestSub, clean)
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
    return when {
        simpleName in KOTLIN_BUILTIN_QUALIFY_SIMPLE_TYPE_NAMES ->
            if (resolvedPkg.isNotEmpty() && resolvedPkg != headerContext.packageName) rawTarget else simpleName
        simpleName in inspector.attachableTypeNames ||
            simpleName in inspector.declaredTypeNames ||
            isDeclaredTopLevelFnKotlinName(simpleName) ->
            if (resolvedPkg.isNotEmpty() && resolvedPkg != headerContext.packageName) rawTarget else simpleName
        collidedPkg != null && collidedPkg != resolvedPkg -> rawTarget
        explicitAlias == null &&
            resolvedPkg.isNotEmpty() &&
            resolvedPkg != headerContext.packageName &&
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
        resolvedPkg.isNotEmpty() && resolvedPkg != headerContext.packageName -> {
            importedSimpleNameToPkg[simpleName] = resolvedPkg
            val importLine = if (explicitAlias != null) "import $rawTarget as $explicitAlias" else "import $rawTarget"
            extraTypePathImports += importLine
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
    var cur = segs
    var guard = 0
    while (guard++ < 4) {
        val firstRaw = cur.firstOrNull()?.removePrefix("r#") ?: break
        val isCrateRootPrefix = firstRaw == "crate" || firstRaw in headerContext.selfCrateAliases
        if (isCrateRootPrefix && cur.size >= 3) {
            val secondRaw = cur[1].removePrefix("r#")
            if (secondRaw in headerContext.rootSelfDirModuleAliases) {
                cur = listOf("crate") + cur.drop(2)
                continue
            }
            val reexportTarget = headerContext.rootExternalModuleReexports[secondRaw]
            if (reexportTarget != null &&
                secondRaw !in inspector.localInlineModuleNames &&
                secondRaw !in inspector.localExternalModuleNames
            ) {
                cur = reexportTarget.split("::") + cur.drop(2)
                continue
            }
        }
        if (cur.size >= 2 &&
            firstRaw !in inspector.localInlineModuleNames &&
            firstRaw !in inspector.localExternalModuleNames &&
            !scopeBuilder.isImportedModuleInScope(firstRaw)
        ) {
            if (firstRaw in headerContext.rootSelfDirModuleAliases) {
                cur = listOf("crate") + cur.drop(1)
                continue
            }
            val reexportTarget = headerContext.rootExternalModuleReexports[firstRaw]
            if (reexportTarget != null) {
                cur = reexportTarget.split("::") + cur.drop(1)
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
): Pair<String, List<String>>? {
    val rawSegStrings = rewriteCrateRootAliasAndReexportSegments(rawInputSegStrings)
    if (rawSegStrings.size < 2) return null
    val firstRaw = rawSegStrings.first().removePrefix("r#")
    if (firstRaw in inspector.localInlineModuleNames) return null
    val secondCleanEarly = rawSegStrings.getOrNull(1)?.removePrefix("r#")
    val thirdCleanEarly = rawSegStrings.getOrNull(2)?.removePrefix("r#")
    if (!headerContext.rawAbiMode &&
        headerContext.packageName?.startsWith("io.github.kotlinmania.rustlang.") == true &&
        rawSegStrings.size in 4..5 &&
        firstRaw in setOf("core", "std", "crate") &&
        secondCleanEarly == "sync" &&
        thirdCleanEarly == "atomic"
    ) {
        val coreNs = CoreIntrinsicLowering.resolveCoreNamespace(headerContext)
        if (rawSegStrings.size == 5) {
            val ownerSym = rawSegStrings[3].removePrefix("r#")
            if (ownerSym.firstOrNull()?.isUpperCase() == true) {
                return "$coreNs.sync.atomic.${rustTypeIdentToKotlin(ownerSym)}" to listOf(rawSegStrings[4])
            }
        } else {
            val sym = rawSegStrings[3].removePrefix("r#")
            val ktSym = if (sym.firstOrNull()?.isUpperCase() == true) rustTypeIdentToKotlin(sym) else rustFunctionIdentToKotlin(sym)
            return "$coreNs.sync.atomic.$ktSym" to emptyList()
        }
    }
    val coreMemIntrinsics =
        setOf("transmute", "transmute_unchecked", "size_of", "size_of_val", "align_of", "align_of_val", "needs_drop", "forget")
    if (rawSegStrings.size == 3 &&
        firstRaw in setOf("core", "std", "crate") &&
        secondCleanEarly == "intrinsics" &&
        thirdCleanEarly != null &&
        thirdCleanEarly in coreMemIntrinsics
    ) {
        return "${CoreIntrinsicLowering.resolveCoreNamespace(headerContext)}.mem.${rustFunctionIdentToKotlin(thirdCleanEarly)}" to emptyList()
    }
    if (!headerContext.rawAbiMode &&
        headerContext.packageName?.startsWith("io.github.kotlinmania.rustlang.") == true &&
        rawSegStrings.size in 3..4 &&
        firstRaw in setOf("alloc", "std", "crate") &&
        (
            firstRaw != "crate" ||
                headerContext.packageName.startsWith("io.github.kotlinmania.rustlang.std") ||
                headerContext.packageName.startsWith("io.github.kotlinmania.rustlang.alloc")
        )
    ) {
        val symSeg = rawSegStrings[2].removePrefix("r#")
        val allocNs = CoreIntrinsicLowering.resolveAllocNamespace(headerContext)
        if (secondCleanEarly == "sync" && symSeg in setOf("Arc", "Weak") && symSeg !in inspector.declaredTypeNames) {
            return "$allocNs.rcs.arc.$symSeg" to rawSegStrings.drop(3)
        }
        if (secondCleanEarly == "rc" && symSeg in setOf("Rc", "Weak") && symSeg !in inspector.declaredTypeNames) {
            return "$allocNs.rcs.rc.$symSeg" to rawSegStrings.drop(3)
        }
    }
    if (firstRaw == "std" || firstRaw == "alloc" || firstRaw == "Self" || firstRaw in PRIMITIVE_TYPE_NAMES) return null
    if (rawSegStrings.dropLast(1).any { it.removePrefix("r#") in STDLIB_PRELUDE_REEXPORT_MODULES }) {
        return null
    }
    val secondClean = rawSegStrings.getOrNull(1)?.removePrefix("r#")
    val thirdClean = rawSegStrings.getOrNull(2)?.removePrefix("r#")
    if (secondClean == "string" &&
        thirdClean == "String" &&
        (
            firstRaw == "alloc" ||
                firstRaw == "std" ||
                (
                    firstRaw == "crate" &&
                        (
                            headerContext.packageName == "io.github.kotlinmania.rustlang.alloc" ||
                                headerContext.packageName?.startsWith("io.github.kotlinmania.rustlang.alloc.") == true
                        )
                )
        )
    ) {
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
            val inCoreCrate = currentNamespacePrefix() == CoreIntrinsicLowering.resolveCoreNamespace(headerContext)
            val leafClean = rawSegStrings.last().removePrefix("r#")
            val coreIntrinsicOrPreludeLeaves =
                setOf(
                    "Option",
                    "Some",
                    "None",
                    "Result",
                    "Ok",
                    "Err",
                    "Vec",
                    "Box",
                    "Rc",
                    "Arc",
                    "Cow",
                    "PhantomData",
                    "ManuallyDrop",
                    "MaybeUninit",
                    "Cell",
                    "RefCell",
                    "OnceCell",
                    "Iter",
                    "IterMut",
                    "IntoIter",
                    "Range",
                    "RangeInclusive",
                    "min",
                    "max",
                    "once",
                    "empty",
                    "drop",
                    "transmute",
                    "transmute_unchecked",
                    "size_of",
                    "size_of_val",
                    "align_of",
                    "align_of_val",
                    "needs_drop",
                    "forget",
                )
            if (inCoreCrate &&
                rawSegStrings.size >= 3 &&
                secondRaw !in setOf("intrinsics", "ffi") &&
                secondRaw !in STDLIB_MACRO_OR_PRIMITIVE_MODULES &&
                leafClean !in coreIntrinsicOrPreludeLeaves
            ) {
                return resolveCrossModulePathTarget(listOf("crate") + rawSegStrings.drop(1), inTypePosition)
            }
            return null
        }
    }

    val nsPrefix = currentNamespacePrefix()
    if (scopeBuilder.isImportedModuleInScope(firstRaw)) {
        val modBinding = scopeBuilder.resolveModuleBinding(firstRaw) ?: return null
        val importedPath = modBinding.importedPath ?: return null
        val rest = rawSegStrings.drop(1)
        val coreNs = CoreIntrinsicLowering.resolveCoreNamespace(headerContext)
        if (firstRaw == "intrinsics" &&
            rest.size == 1 &&
            rest[0].removePrefix("r#") in coreMemIntrinsics &&
            (importedPath == "core.intrinsics" || importedPath == "std.intrinsics" || importedPath == "$coreNs.intrinsics")
        ) {
            return "$coreNs.mem.${rustFunctionIdentToKotlin(rest[0].removePrefix("r#"))}" to emptyList()
        }
        if (rest.size == 1 &&
            !headerContext.rawAbiMode &&
            (importedPath.endsWith(".os.raw") || importedPath.endsWith(".ffi"))
        ) {
            val cLeaf = rest[0].removePrefix("r#")
            if (cLeaf == "CString") {
                resolveStdlibFfiSymbolImport("CString", isAllocCString = true)?.let { return it to emptyList() }
            }
            if (cLeaf in CoreIntrinsicLowering.STDLIB_CORE_FFI_SYMBOLS) {
                resolveStdlibFfiSymbolImport(cLeaf)?.let { return it to emptyList() }
            }
        }
        val isSameCrateImport =
            nsPrefix != null && (importedPath == nsPrefix || importedPath.startsWith("$nsPrefix."))
        if (isSameCrateImport && rest.isNotEmpty()) {
            if (importedPath == nsPrefix) {
                for (trailingCount in 0..1) {
                    if (rest.size <= trailingCount) continue
                    val effectiveRest = rest.dropLast(trailingCount)
                    val midSegs = effectiveRest.dropLast(1)
                    if (midSegs.all { it.removePrefix("r#").firstOrNull()?.isLowerCase() == true }) {
                        val resolved =
                            resolveKotlinImportPath(
                                listOf("crate") + effectiveRest,
                                forceTypeLeaf = inTypePosition || trailingCount > 0,
                            )
                        if (resolved != null) {
                            return resolved to rest.takeLast(trailingCount)
                        }
                    }
                }
                return null
            }
            val boundSubpkg = importedPath.removePrefix("$nsPrefix.")
            val boundExistsInCrate =
                boundSubpkg in headerContext.crateSubpackageRawLeafNames ||
                    headerContext.crateSubpackageRawLeafNames.keys.any { it.startsWith("$boundSubpkg.") } ||
                    headerContext.crateSymbolSubpackages.values.any { pkgs ->
                        pkgs.any { it == boundSubpkg || it.startsWith("$boundSubpkg.") }
                    }
            var resolvedBoundSubpkg: String? = if (boundExistsInCrate) boundSubpkg else null
            if (resolvedBoundSubpkg == null) {
                var anc = boundSubpkg
                while ('.' in anc) {
                    anc = anc.substringBeforeLast('.')
                    val ancExists =
                        anc in headerContext.crateSubpackageRawLeafNames ||
                            headerContext.crateSymbolSubpackages.values.any { anc in it }
                    if (ancExists) {
                        resolvedBoundSubpkg = anc
                        break
                    }
                }
                if (resolvedBoundSubpkg == null && headerContext.crateSymbolSubpackages.values.any { "" in it }) {
                    resolvedBoundSubpkg = ""
                }
            }
            if (resolvedBoundSubpkg == null) return null
            var modSubpkg = resolvedBoundSubpkg
            var idx = 0
            while (idx < rest.lastIndex) {
                val segClean = rest[idx].removePrefix("r#")
                val nextSub = "$modSubpkg.${rustModuleIdentToKotlin(segClean.replace('-', '_'))}"
                if (headerContext.crateSubpackageRawLeafNames[nextSub] == segClean ||
                    headerContext.crateSubpackageRawLeafNames.keys.any { it.startsWith("$nextSub.") }
                ) {
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
                val symCandidates = headerContext.crateSymbolSubpackages[candSymKt].orEmpty()
                val scopedCandidates =
                    when {
                        modSubpkg in symCandidates -> listOf(modSubpkg)
                        modSubpkg.isEmpty() -> symCandidates
                        else -> symCandidates.filter { it.startsWith("$modSubpkg.") }
                    }
                val finalSubpkg = resolveBestSubpackageAmong(scopedCandidates)
                if (finalSubpkg != null) {
                    return (if (finalSubpkg.isEmpty()) "$nsPrefix.$candSymKt" else "$nsPrefix.$finalSubpkg.$candSymKt") to trailing
                }
            }
            if (trailing.isEmpty() && !inTypePosition) {
                val childModSubpkg =
                    if (modSubpkg.isEmpty()) {
                        rustModuleIdentToKotlin(symRaw.removePrefix("r#").replace('-', '_'))
                    } else {
                        "$modSubpkg.${rustModuleIdentToKotlin(symRaw.removePrefix("r#").replace('-', '_'))}"
                    }
                if (childModSubpkg in headerContext.crateSubpackageRawLeafNames &&
                    headerContext.crateSymbolSubpackages["call"]?.contains(childModSubpkg) == true
                ) {
                    return "$nsPrefix.$childModSubpkg.call as ${symRaw.removePrefix("r#")}" to emptyList()
                }
            }
            resolveExternalDepReexportPkg(modSubpkg, symKt, nsPrefix)?.let { resolvedExt ->
                return resolvedExt to trailing
            }
            return (if (modSubpkg.isEmpty()) "$nsPrefix.$symKt" else "$nsPrefix.$modSubpkg.$symKt") to trailing
        }
        val matchedExtCrate: Pair<String, List<String>>? =
            if (importedPath in headerContext.crateNamespaces) {
                importedPath to emptyList()
            } else {
                headerContext.crateNamespaces.entries
                    .mapNotNull { (k, v) ->
                        val cleanNs = v.trim().removeSuffix(".")
                        when {
                            cleanNs.isEmpty() || cleanNs == nsPrefix -> null
                            importedPath == cleanNs -> Triple(k, cleanNs.length, emptyList<String>())
                            importedPath.startsWith("$cleanNs.") -> {
                                val subSegs =
                                    importedPath
                                        .removePrefix("$cleanNs.")
                                        .split('.')
                                        .filter { it.isNotEmpty() }
                                Triple(k, cleanNs.length, subSegs)
                            }
                            importedPath.startsWith("$k.") -> {
                                val subSegs =
                                    importedPath
                                        .removePrefix("$k.")
                                        .split('.')
                                        .filter { it.isNotEmpty() }
                                Triple(k, k.length, subSegs)
                            }
                            else -> null
                        }
                    }.maxByOrNull { it.second }
                    ?.let { it.first to it.third }
            }
        if (matchedExtCrate != null && rest.isNotEmpty()) {
            for (trailingCount in 0..1) {
                if (rest.size <= trailingCount) continue
                val effectiveRest = rest.dropLast(trailingCount)
                val midSegs = effectiveRest.dropLast(1)
                if (midSegs.all { it.removePrefix("r#").firstOrNull()?.isLowerCase() == true }) {
                    val resolved =
                        resolveKotlinImportPath(
                            listOf(matchedExtCrate.first) + matchedExtCrate.second + effectiveRest,
                            forceTypeLeaf = inTypePosition || trailingCount > 0,
                        )
                    if (resolved != null) {
                        return resolved to rest.takeLast(trailingCount)
                    }
                }
            }
        }
        return null
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
                    firstRaw in headerContext.crateNamespaces ||
                    firstRaw in STDLIB_CRATE_ROOTS ||
                    (
                        !headerContext.isBatchCrateOutput &&
                            !headerContext.projectGroup.isNullOrBlank() &&
                            firstRaw !in STDLIB_UNIMPORTED_SUBMODULES
                    )
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
                    firstRaw in headerContext.crateNamespaces ||
                    firstRaw in STDLIB_CRATE_ROOTS ||
                    (
                        !headerContext.isBatchCrateOutput &&
                            !headerContext.projectGroup.isNullOrBlank() &&
                            firstRaw !in STDLIB_UNIMPORTED_SUBMODULES
                    )
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
    if (kt in inspector.declaredTypeNames ||
        kt in headerContext.crateSymbolSubpackages ||
        headerContext.externalCrateSymbolPackages.values.any { kt in it }
    ) {
        return true
    }
    if (headerContext.crateSymbolSubpackages.isNotEmpty()) return false
    return idx == segments.lastIndex - 1 && segments.last() == "*"
}

internal fun KotlinJ2KEmitter.resolveKotlinImportPath(
    rawSegsInput: List<String>,
    boundRawAlias: String? = null,
    isSelfImport: Boolean = false,
    forceTypeLeaf: Boolean = false,
): String? {
    val rewrittenSegs = rewriteCrateRootAliasAndReexportSegments(rawSegsInput)
    if (rewrittenSegs.isEmpty()) return null
    val segs =
        if (rewrittenSegs.first().removePrefix("r#") == "netc" &&
            FfiInteropCollector.resolveLibcNamespace(headerContext) != null
        ) {
            listOf("libc") + rewrittenSegs.drop(1)
        } else {
            rewrittenSegs
        }
    val root = segs.first()
    if (root in PRIMITIVE_TYPE_NAMES) return null
    // A same-file type or an imported type's members retain type casing.
    // Resolve an unqualified type glob through an unambiguous lexical glob use;
    // this does not inspect any other Rust file or invent a cross-file symbol table.
    if (root.firstOrNull()?.isUpperCase() == true && segs.size > 1) {
        val rootTypeKt = rustTypeIdentToKotlin(root)
        fun formatUpperTail(tailSegs: List<String>): String =
            tailSegs.joinToString(".") { seg ->
                val cleanSeg = seg.removePrefix("r#")
                when {
                    cleanSeg == "*" -> "*"
                    isImportedScreamingConstLeaf(cleanSeg, boundRawAlias, isSelfImport) -> rustConstIdentToKotlin(cleanSeg)
                    else -> rustTypeIdentToKotlin(cleanSeg)
                }
            }
        if (rootTypeKt in inspector.declaredTypeNames) {
            return resolveKotlinImportPath(listOf("self") + segs, boundRawAlias, isSelfImport, forceTypeLeaf)
        }
        val scopedImportedPath = scopeBuilder.resolveModuleBinding(root)?.importedPath?.takeIf { '.' in it }
        if (scopedImportedPath != null) {
            return "$scopedImportedPath.${formatUpperTail(segs.drop(1))}"
        }
        val importedPkg = importedSimpleNameToPkg[rootTypeKt]
        if (importedPkg != null) {
            return "$importedPkg.${formatUpperTail(segs)}"
        }
        if (headerContext.crateSymbolSubpackages[rootTypeKt]?.isNotEmpty() == true) {
            val subPkg = resolveBestSubpackageForSymbol(rootTypeKt)
            val nsPrefix = currentNamespacePrefix()
            if (nsPrefix != null) {
                val ownerPkg = if (subPkg.isNullOrEmpty()) nsPrefix else "$nsPrefix.$subPkg"
                return "$ownerPkg.${formatUpperTail(segs)}"
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
            return "${packages.single()}.${formatUpperTail(segs)}"
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
        val nsPrefix = currentNamespacePrefix()
        if (nsPrefix != null && afterSelf.size >= 2 && headerContext.crateSymbolSubpackages.isNotEmpty()) {
            val interTypeIdx =
                afterSelf.indices.take(afterSelf.lastIndex).firstOrNull { idx ->
                    isIntermediateTypeSegmentInUsePath(afterSelf[idx], idx, afterSelf, curSubSegs)
                }
            if (interTypeIdx != null) {
                val typeKt = mappedTail[interTypeIdx]
                val targetPrefix = (curSubSegs + mappedTail.take(interTypeIdx)).joinToString(".")
                val candidates = headerContext.crateSymbolSubpackages[typeKt].orEmpty()
                val resolvedSub =
                    if (targetPrefix in candidates) {
                        targetPrefix
                    } else {
                        resolveBestSubpackageAmong(
                            candidates.filter { targetPrefix.isEmpty() || it.startsWith("$targetPrefix.") },
                        )
                    }
                if (resolvedSub != null) {
                    val tail = mappedTail.drop(interTypeIdx).joinToString(".")
                    return if (resolvedSub.isEmpty()) "$nsPrefix.$tail" else "$nsPrefix.$resolvedSub.$tail"
                }
            }
        }
        if (nsPrefix != null && afterSelf.isNotEmpty() && mappedTail.last() != "*" && headerContext.crateSymbolSubpackages.isNotEmpty()) {
            val explicitModule = (curSubSegs + mappedTail.dropLast(1)).joinToString(".")
            val leafSym = mappedTail.last()
            val candidates = headerContext.crateSymbolSubpackages[leafSym]
            if (candidates != null && explicitModule !in candidates) {
                val flattenedSub = resolveFlattenedInlineModuleSubpackage(explicitModule, candidates)
                if (flattenedSub != null) {
                    return if (flattenedSub.isEmpty()) "$nsPrefix.$leafSym" else "$nsPrefix.$flattenedSub.$leafSym"
                }
                val prefixDot = if (explicitModule.isEmpty()) "" else "$explicitModule."
                val childSub =
                    resolveBestSubpackageAmong(
                        candidates.filter { prefixDot.isEmpty() || it.startsWith(prefixDot) },
                    )
                if (childSub != null) {
                    return if (childSub.isEmpty()) "$nsPrefix.$leafSym" else "$nsPrefix.$childSub.$leafSym"
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
        if (rem.size >= 2 && headerContext.crateSymbolSubpackages.isNotEmpty()) {
            val interTypeIdx =
                rem.indices.take(rem.lastIndex).firstOrNull { idx ->
                    isIntermediateTypeSegmentInUsePath(rem[idx], idx, rem, baseSegs)
                }
            if (interTypeIdx != null) {
                val typeKt = mappedRem[interTypeIdx]
                val targetPrefix = (baseSegs + mappedRem.take(interTypeIdx)).joinToString(".")
                val candidates = headerContext.crateSymbolSubpackages[typeKt].orEmpty()
                val resolvedSub =
                    if (targetPrefix in candidates) {
                        targetPrefix
                    } else {
                        resolveBestSubpackageAmong(
                            candidates.filter { targetPrefix.isEmpty() || it.startsWith("$targetPrefix.") },
                        )
                    }
                if (resolvedSub != null) {
                    val tail = mappedRem.drop(interTypeIdx).joinToString(".")
                    return if (resolvedSub.isEmpty()) "$nsPrefix.$tail" else "$nsPrefix.$resolvedSub.$tail"
                }
            }
        }
        if (rem.isNotEmpty() && mappedRem.last() != "*" && headerContext.crateSymbolSubpackages.isNotEmpty()) {
            val explicitModule = (baseSegs + mappedRem.dropLast(1)).joinToString(".")
            val leafSym = mappedRem.last()
            val candidates = headerContext.crateSymbolSubpackages[leafSym]
            if (candidates != null && explicitModule !in candidates) {
                val flattenedSub = resolveFlattenedInlineModuleSubpackage(explicitModule, candidates)
                if (flattenedSub != null) {
                    return if (flattenedSub.isEmpty()) "$nsPrefix.$leafSym" else "$nsPrefix.$flattenedSub.$leafSym"
                }
                val childSub =
                    resolveBestSubpackageAmong(
                        candidates.filter { explicitModule.isEmpty() || it.startsWith("$explicitModule.") },
                    )
                if (childSub != null) {
                    return if (childSub.isEmpty()) "$nsPrefix.$leafSym" else "$nsPrefix.$childSub.$leafSym"
                }
                if (rem.size == 1) {
                    resolveExternalDepReexportPkg(explicitModule, leafSym, nsPrefix)?.let { return it }
                    val best = resolveBestSubpackageForSymbol(leafSym)
                    if (best != null) {
                        return if (best.isEmpty()) "$nsPrefix.$leafSym" else "$nsPrefix.$best.$leafSym"
                    }
                }
            } else if (candidates == null && rem.size == 1) {
                resolveExternalDepReexportPkg(explicitModule, leafSym, nsPrefix)?.let { return it }
            }
        }
        if (!isSelfImport &&
            !headerContext.rawAbiMode &&
            headerContext.packageName?.startsWith("io.github.kotlinmania.rustlang.") == true &&
            rem.isNotEmpty()
        ) {
            val resolvedModSegs = baseSegs + rem.dropLast(1).map { rustModuleIdentToKotlin(it.removePrefix("r#")) }
            val superLeaf = rem.last().removePrefix("r#")
            if (resolvedModSegs == listOf("os", "raw") || resolvedModSegs == listOf("ffi")) {
                if (superLeaf == "CString") {
                    resolveStdlibFfiSymbolImport("CString", isAllocCString = true)?.let { return it }
                }
                if (superLeaf in CoreIntrinsicLowering.STDLIB_CORE_FFI_SYMBOLS) {
                    resolveStdlibFfiSymbolImport(superLeaf)?.let { return it }
                }
            }
        }
        if (baseSegs.isEmpty() && rem.isNotEmpty()) {
            val flatHeadRaw = rem[0].removePrefix("r#")
            if (flatHeadRaw in headerContext.crateFlatFileModules) {
                return if (rem.size == 1 && isSelfImport) {
                    nsPrefix
                } else if (rem.size >= 2) {
                    "$nsPrefix.${mappedRem.drop(1).joinToString(".")}"
                } else {
                    nsPrefix
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
    val cleanSegsEarly = segs.map { it.removePrefix("r#") }
    if (!isSelfImport &&
        !headerContext.rawAbiMode &&
        cleanSegsEarly.size == 2 &&
        cleanSegsEarly[0] == "libc" &&
        cleanSegsEarly[1] in CoreIntrinsicLowering.STDLIB_CORE_FFI_SYMBOLS
    ) {
        resolveStdlibFfiSymbolImport(cleanSegsEarly[1])?.let { return it }
    }
    if (!isSelfImport &&
        cleanSegsEarly.size == 3 &&
        (
            cleanSegsEarly[0] in setOf("core", "std") ||
                (cleanSegsEarly[0] == "crate" && currentNamespacePrefix() == CoreIntrinsicLowering.resolveCoreNamespace(headerContext))
        ) &&
        cleanSegsEarly[1] == "intrinsics" &&
        cleanSegsEarly[2] in
        setOf("transmute", "transmute_unchecked", "size_of", "size_of_val", "align_of", "align_of_val", "needs_drop", "forget")
    ) {
        return "${CoreIntrinsicLowering.resolveCoreNamespace(headerContext)}.mem.${rustFunctionIdentToKotlin(cleanSegsEarly[2])}"
    }
    if (!isSelfImport &&
        !headerContext.rawAbiMode &&
        headerContext.packageName?.startsWith("io.github.kotlinmania.rustlang.") == true &&
        cleanSegsEarly.size in 4..5 &&
        cleanSegsEarly[0] in setOf("core", "std", "crate") &&
        cleanSegsEarly[1] == "sync" &&
        cleanSegsEarly[2] == "atomic"
    ) {
        val coreNs = CoreIntrinsicLowering.resolveCoreNamespace(headerContext)
        if (cleanSegsEarly.size == 5 && cleanSegsEarly[3] == "Ordering") {
            return "$coreNs.sync.atomic.Ordering.${rustTypeIdentToKotlin(cleanSegsEarly[4])}"
        }
        if (cleanSegsEarly.size == 4 && cleanSegsEarly[3] != "*") {
            val s = cleanSegsEarly[3]
            val ktSym =
                when {
                    forceTypeLeaf || s.firstOrNull()?.isUpperCase() == true -> rustTypeIdentToKotlin(s)
                    isKnownFunctionLeafIdent(s) -> rustFunctionIdentToKotlin(s)
                    else -> rustValueIdentToKotlin(s)
                }
            return "$coreNs.sync.atomic.$ktSym"
        }
    }
    if (root == "libc" && FfiInteropCollector.resolveLibcNamespace(headerContext) == null) {
        return null
    }
    val lastRaw = segs.last()
    if (!isSelfImport && !headerContext.rawAbiMode) {
        val cleanSegs = segs.map { it.removePrefix("r#") }
        val isStdOrCrateFfi =
            (cleanSegs.size == 3 && cleanSegs[0] in setOf("core", "std") && cleanSegs[1] == "ffi") ||
                (cleanSegs.size == 4 && cleanSegs[0] == "std" && cleanSegs[1] == "os" && cleanSegs[2] == "raw") ||
                (
                    cleanSegs.size == 3 &&
                        cleanSegs[0] == "crate" &&
                        cleanSegs[1] == "ffi" &&
                        headerContext.packageName?.startsWith("io.github.kotlinmania.rustlang.") == true
                ) ||
                (
                    cleanSegs.size == 4 &&
                        cleanSegs[0] == "crate" &&
                        cleanSegs[1] == "os" &&
                        cleanSegs[2] == "raw" &&
                        headerContext.packageName?.startsWith("io.github.kotlinmania.rustlang.std") == true
                )
        if (isStdOrCrateFfi) {
            val leaf = cleanSegs.last()
            if (leaf == "CString") {
                resolveStdlibFfiSymbolImport("CString", isAllocCString = true)?.let { return it }
            }
            if (leaf in CoreIntrinsicLowering.STDLIB_CORE_FFI_SYMBOLS) {
                resolveStdlibFfiSymbolImport(leaf)?.let { return it }
            }
        }
        if (cleanSegs.size == 3 && cleanSegs[0] == "alloc" && cleanSegs[1] == "ffi" && cleanSegs[2] == "CString") {
            resolveStdlibFfiSymbolImport("CString", isAllocCString = true)?.let { return it }
        }
        if (cleanSegs.size == 3 &&
            headerContext.packageName?.startsWith("io.github.kotlinmania.rustlang.") == true &&
            cleanSegs[0] in setOf("alloc", "std", "crate") &&
            (
                cleanSegs[0] != "crate" ||
                    headerContext.packageName.startsWith("io.github.kotlinmania.rustlang.std") ||
                    headerContext.packageName.startsWith("io.github.kotlinmania.rustlang.alloc")
            )
        ) {
            val cleanLeaf = cleanSegs[2]
            val allocNs = CoreIntrinsicLowering.resolveAllocNamespace(headerContext)
            if (cleanLeaf !in inspector.declaredTypeNames) {
                if (cleanSegs[1] == "sync" && cleanLeaf in setOf("Arc", "Weak")) {
                    return "$allocNs.rcs.arc.$cleanLeaf"
                }
                if (cleanSegs[1] == "rc" && cleanLeaf in setOf("Rc", "Weak")) {
                    return "$allocNs.rcs.rc.$cleanLeaf"
                }
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
        val curPkg = headerContext.packageName.orEmpty()
        if (nsPrefix != null &&
            ".detect.os." in curPkg &&
            mapped.last() == "Feature" &&
            "detect" in segs
        ) {
            val osTailSegs = curPkg.substringAfter(".detect.os.").split('.').filter { it.isNotEmpty() }
            val archSub =
                osTailSegs.firstOrNull { cand ->
                    "detect.arch.$cand" in headerContext.crateSubpackageRawLeafNames
                }
            if (archSub != null) {
                return "$nsPrefix.detect.arch.$archSub.Feature"
            }
        }
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
            val rawLeaf = afterRoot[0].removePrefix("r#")
            if (rawLeaf.firstOrNull()?.isLowerCase() == true && !isKnownFunctionLeafIdent(afterRoot[0])) {
                val leafMod = rustModuleIdentToKotlin(rawLeaf.replace('-', '_'))
                if (leafMod in headerContext.crateSubpackageRawLeafNames) {
                    return "$nsPrefix.$leafMod"
                }
            }
            val directSub =
                if (rawLeaf.firstOrNull()?.isLowerCase() != true || isKnownFunctionLeafIdent(afterRoot[0])) {
                    resolveBestSubpackageForSymbol(mapped.last())
                } else {
                    null
                }
            if (directSub != null) {
                return if (directSub.isEmpty()) "$nsPrefix.${mapped.last()}" else "$nsPrefix.$directSub.${mapped.last()}"
            }
            if (rawLeaf.firstOrNull()?.isLowerCase() == true) {
                val typeKt = rustTypeIdentToKotlin(afterRoot[0])
                val typeSub = resolveBestSubpackageForSymbol(typeKt)
                if (typeSub != null) {
                    return if (typeSub.isEmpty()) "$nsPrefix.$typeKt" else "$nsPrefix.$typeSub.$typeKt"
                }
                val valSub = resolveBestSubpackageForSymbol(mapped.last())
                if (valSub != null) {
                    return if (valSub.isEmpty()) "$nsPrefix.${mapped.last()}" else "$nsPrefix.$valSub.${mapped.last()}"
                }
            }
            if (mapped.last() !in inspector.declaredTypeNames &&
                mapped.last() !in inspector.declaredConstNames.values &&
                !isDeclaredTopLevelFnKotlinName(mapped.last())
            ) {
                for (extCrate in listOf("core", "alloc", "std")) {
                    val extPkgs = headerContext.externalCrateSymbolPackages[extCrate]?.get(mapped.last())
                    if (!extPkgs.isNullOrEmpty()) {
                        return "${extPkgs.first()}.${mapped.last()}"
                    }
                }
            }
        }
        if (afterRoot.size >= 2 && nsPrefix != null && headerContext.crateSymbolSubpackages.isNotEmpty()) {
            val interTypeIdx =
                afterRoot.indices.take(afterRoot.lastIndex).firstOrNull { idx ->
                    isIntermediateTypeSegmentInUsePath(afterRoot[idx], idx, afterRoot)
                }
            if (interTypeIdx != null) {
                val typeKt = mapped[1 + interTypeIdx]
                val targetPrefix = mapped.drop(1).take(interTypeIdx).joinToString(".")
                val candidates = headerContext.crateSymbolSubpackages[typeKt].orEmpty()
                val resolvedSub =
                    if (targetPrefix in candidates) {
                        targetPrefix
                    } else {
                        resolveBestSubpackageAmong(
                            candidates.filter { targetPrefix.isEmpty() || it.startsWith("$targetPrefix.") },
                        )
                    }
                if (resolvedSub != null) {
                    val tail = mapped.drop(1 + interTypeIdx).joinToString(".")
                    return if (resolvedSub.isEmpty()) "$nsPrefix.$tail" else "$nsPrefix.$resolvedSub.$tail"
                }
            }
        }
        // A module path names where the item is declared; the scanned crate metadata records the
        // subpackage that declaration is emitted into (with split module roots, `op.rs` -> `op`).
        if (afterRoot.size >= 2 && mapped.last() != "*" && nsPrefix != null) {
            val explicitModule = mapped.drop(1).dropLast(1).joinToString(".")
            val rawLastClean = afterRoot.last().removePrefix("r#")
            if (rawLastClean.firstOrNull()?.isLowerCase() == true && !isKnownFunctionLeafIdent(rawLastClean)) {
                val expectedLeafKt = rustModuleIdentToKotlin(rawLastClean.replace('-', '_'))
                val exactChild = if (explicitModule.isEmpty()) expectedLeafKt else "$explicitModule.$expectedLeafKt"
                if (exactChild in headerContext.crateSubpackageRawLeafNames) {
                    return "$nsPrefix.$exactChild"
                }
            }
            val normalizedExplicitModule =
                if (explicitModule == "os.wasi.ffi" || explicitModule.startsWith("os.wasi.ffi.")) {
                    explicitModule.replaceFirst("os.wasi.ffi", "os.unix.ffi")
                } else {
                    explicitModule
                }
            val candidates = headerContext.crateSymbolSubpackages[mapped.last()]
            if (!candidates.isNullOrEmpty()) {
                val sub =
                    when {
                        explicitModule in candidates -> explicitModule
                        normalizedExplicitModule in candidates -> normalizedExplicitModule
                        else ->
                            resolveFlattenedInlineModuleSubpackage(explicitModule, candidates)
                                ?: resolveFlattenedInlineModuleSubpackage(normalizedExplicitModule, candidates)
                                ?: resolveBestSubpackageAmong(
                                    candidates.filter { it.startsWith("$explicitModule.") },
                                )
                                ?: resolveBestSubpackageAmong(
                                    candidates.filter { it.startsWith("$normalizedExplicitModule.") },
                                )
                    }
                if (sub != null) {
                    return if (sub.isEmpty()) "$nsPrefix.${mapped.last()}" else "$nsPrefix.$sub.${mapped.last()}"
                }
            }
            resolveExternalDepReexportPkg(explicitModule, mapped.last(), nsPrefix)?.let { return it }
            if (rawLastClean.firstOrNull()?.isLowerCase() == true && !isKnownFunctionLeafIdent(rawLastClean)) {
                val expectedLeafKt = rustModuleIdentToKotlin(rawLastClean.replace('-', '_'))
                val exactChild = if (explicitModule.isEmpty()) expectedLeafKt else "$explicitModule.$expectedLeafKt"
                if (exactChild !in headerContext.crateSubpackageRawLeafNames) {
                    val matchingModSubpkgs =
                        headerContext.crateSubpackageRawLeafNames.entries
                            .filter { (subKey, rawLeaf) ->
                                rawLeaf == rawLastClean &&
                                    (explicitModule.isEmpty() || subKey.startsWith("$explicitModule."))
                            }.map { it.key }
                    val bestModSub = resolveBestSubpackageAmong(matchingModSubpkgs)
                    if (bestModSub != null) {
                        return "$nsPrefix.$bestModSub"
                    }
                }
            }
            if (!candidates.isNullOrEmpty()) {
                val best = resolveBestSubpackageForSymbol(mapped.last())
                if (best != null) {
                    return if (best.isEmpty()) "$nsPrefix.${mapped.last()}" else "$nsPrefix.$best.${mapped.last()}"
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
            fun resolveExternalPenultOwnerType(): String? {
                if (mappedLeaf == null || mapped.size < 3 || mapped[mapped.size - 2].firstOrNull()?.isUpperCase() != true) {
                    return null
                }
                val penultType = mapped[mapped.size - 2]
                val ownerMidPath = mapped.drop(1).dropLast(2).joinToString(".")
                for (searchCrate in candidateCrates) {
                    val searchNs =
                        headerContext.crateNamespaces[searchCrate]
                            ?.trim()
                            ?.removeSuffix(".")
                            ?.takeIf { it.isNotEmpty() } ?: continue
                    val searchExtPkgs = headerContext.externalCrateSymbolPackages[searchCrate] ?: continue
                    val ownerCandidates = searchExtPkgs[penultType] ?: continue
                    val exactOwnerPkg = if (ownerMidPath.isEmpty()) searchNs else "$searchNs.$ownerMidPath"
                    val resolvedOwnerPkg =
                        when {
                            exactOwnerPkg in ownerCandidates -> exactOwnerPkg
                            else ->
                                ownerCandidates
                                    .filter { it.startsWith("$exactOwnerPkg.") }
                                    .minByOrNull { it.count { ch -> ch == '.' } }
                                    ?: ownerCandidates.firstOrNull()
                        }
                    if (resolvedOwnerPkg != null) {
                        return "$resolvedOwnerPkg.$penultType.$mappedLeaf"
                    }
                }
                return null
            }
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
                        val candidateMidPaths =
                            buildList {
                                add(midPath)
                                if (searchCrate == "libm" && (midPath == "support" || midPath.startsWith("support."))) {
                                    add("math.$midPath")
                                }
                            }
                        for (candMidPath in candidateMidPaths) {
                            val exactPkg = if (candMidPath.isEmpty()) searchNs else "$searchNs.$candMidPath"
                            val resolvedPkg =
                                when {
                                    exactPkg in candidates -> exactPkg
                                    else ->
                                        candidates
                                            .filter { it.startsWith("$exactPkg.") }
                                            .minByOrNull { it.count { ch -> ch == '.' } }
                                }
                            if (resolvedPkg != null) {
                                return "$resolvedPkg.$mappedLeaf"
                            }
                        }
                    }
                }
                val cleanLastRaw = lastRaw.removePrefix("r#")
                if (cleanLastRaw.firstOrNull()?.isLowerCase() == true && !isKnownFunctionLeafIdent(cleanLastRaw)) {
                    val subTail = mapped.drop(1).joinToString(".")
                    for (searchCrate in candidateCrates) {
                        val searchNs =
                            headerContext.crateNamespaces[searchCrate]
                                ?.trim()
                                ?.removeSuffix(".")
                                ?.takeIf { it.isNotEmpty() } ?: continue
                        val searchExtPkgs = headerContext.externalCrateSymbolPackages[searchCrate] ?: continue
                        val candidateSubPkg = if (subTail.isEmpty()) searchNs else "$searchNs.$subTail"
                        if (searchExtPkgs.values.any { pkgs ->
                                pkgs.any { it == candidateSubPkg || it.startsWith("$candidateSubPkg.") }
                            }
                        ) {
                            return candidateSubPkg
                        }
                    }
                }
                for (searchCrate in candidateCrates) {
                    val searchExtPkgs = headerContext.externalCrateSymbolPackages[searchCrate] ?: continue
                    val candidates = searchExtPkgs[mappedLeaf]
                    if (!candidates.isNullOrEmpty()) {
                        return "${candidates.first()}.$mappedLeaf"
                    }
                }
                resolveExternalPenultOwnerType()?.let { return it }
                if (root in STDLIB_CRATE_ROOTS) return null
            } else if (root in STDLIB_CRATE_ROOTS && mappedLeaf == "*") {
                val allowedWildcardSubmodules = setOf("alloc", "cell", "cmp", "ffi", "mem", "num", "ops", "ptr", "result", "sync", "atomic")
                if (anyCandidateExtPkgs && mapped.size >= 4) {
                    resolveExternalPenultOwnerType()?.let { return it }
                }
                val hasExternalWildcardPkg =
                    anyCandidateExtPkgs &&
                        mapped.size >= 3 &&
                        candidateCrates.any { searchCrate ->
                            val searchNs =
                                headerContext.crateNamespaces[searchCrate]
                                    ?.trim()
                                    ?.removeSuffix(".")
                                    ?.takeIf { it.isNotEmpty() } ?: return@any false
                            val searchExtPkgs = headerContext.externalCrateSymbolPackages[searchCrate] ?: return@any false
                            val subTail = mapped.drop(1).dropLast(1).joinToString(".")
                            val candidateSubPkg = if (subTail.isEmpty()) searchNs else "$searchNs.$subTail"
                            searchExtPkgs.values.any { pkgs -> candidateSubPkg in pkgs }
                        }
                if (!hasExternalWildcardPkg && (mapped.size != 3 || mapped[1] !in allowedWildcardSubmodules)) {
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
            } else if ((
                    headerContext.isBatchCrateOutput ||
                        headerContext.portLintDirective == "tests" ||
                        headerContext.testClassName != null
                ) &&
                root !in STDLIB_CRATE_ROOTS &&
                root !in headerContext.crateNamespaces &&
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
    val effectiveLeaves = mutableListOf<UseLeaf>()
    for (leaf in leaves) {
        val segs = leaf.segments
        val root = segs.firstOrNull()
        val isStdOrSelfStdCrate =
            root in STDLIB_CRATE_ROOTS ||
                (root == "crate" && headerContext.packageName?.startsWith("io.github.kotlinmania.rustlang.") == true)
        if (isStdOrSelfStdCrate && segs.size == 5 && segs[1] == "os" && segs[3] == "prelude" && segs[4] == "*") {
            val stdRoot = if (root == "crate") "crate" else "std"
            when (segs[2]) {
                "windows" -> {
                    for (sym in listOf("OsStrExt", "OsStringExt", "encode_wide")) {
                        effectiveLeaves += UseLeaf(segments = listOf(stdRoot, "os", "windows", "ffi", sym), alias = null, isSelfImport = false)
                    }
                    continue
                }
                "unix", "wasi" -> {
                    for (sym in listOf("OsStrExt", "OsStringExt", "as_bytes", "into_vec", "from_bytes", "from_vec")) {
                        effectiveLeaves += UseLeaf(segments = listOf(stdRoot, "os", "unix", "ffi", sym), alias = null, isSelfImport = false)
                    }
                    continue
                }
            }
        }
        effectiveLeaves += leaf
    }
    val lines = mutableListOf<String>()
    for (leaf in effectiveLeaves) {
        val root = leaf.segments.firstOrNull()
        val isStdOrSelfStdCrate =
            root in STDLIB_CRATE_ROOTS ||
                (root == "crate" && headerContext.packageName?.startsWith("io.github.kotlinmania.rustlang.") == true)
        val isMappedOrSelfStdCrate =
            (
                root in STDLIB_CRATE_ROOTS &&
                    (
                        root in headerContext.crateNamespaces ||
                            headerContext.packageName?.startsWith("io.github.kotlinmania.rustlang.") == true
                    )
            ) ||
                (root == "crate" && headerContext.packageName?.startsWith("io.github.kotlinmania.rustlang.") == true)
        if (leaf.isSelfImport && isStdOrSelfStdCrate) {
            continue
        }
        if (isMappedOrSelfStdCrate &&
            leaf.segments.size == 2 &&
            leaf.segments[1].firstOrNull()?.isLowerCase() == true &&
            !isKnownFunctionLeafIdent(leaf.segments[1]) &&
            resolveBestSubpackageForSymbol(rustTypeIdentToKotlin(leaf.segments[1])) == null
        ) {
            continue
        }
        if (isStdOrSelfStdCrate &&
            leaf.segments.getOrNull(1)?.removePrefix("r#") in setOf("f16", "f32", "f64", "f128", "consts")
        ) {
            continue
        }
        if (root == "crate" && leaf.segments.getOrNull(1) == "drops" && leaf.segments.lastOrNull() in setOf("NoDrop", "TrivialDrop")) {
            continue
        }
        val fullImport = resolveKotlinImportPath(leaf.segments, leaf.alias, leaf.isSelfImport) ?: continue
        if (fullImport.endsWith(".panic.constPanic") ||
            fullImport.endsWith(".panic.constAssert") ||
            fullImport.endsWith(".io.constError") ||
            fullImport.endsWith(".error.constError")
        ) {
            continue
        }
        if (isResolvedCrateSubpackageModule(fullImport, leaf.segments.last())) {
            continue
        }
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
            if (leaf.alias != null) {
                val rawAliasIdent =
                    if (isImportedTypeLeaf(leaf.segments.last(), leaf.alias, leaf.isSelfImport)) {
                        rustTypeIdentToKotlin(leaf.alias)
                    } else if (leaf.alias.first().isUpperCase()) {
                        rustTypeIdentToKotlin(leaf.alias)
                    } else {
                        null
                    }
                if (rawAliasIdent != null && rawAliasIdent != finalSimpleName) {
                    samePackageUseRenames[rawAliasIdent] = finalSimpleName
                }
            }
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
            val symbolsInPkg =
                headerContext.externalCrateSymbolPackages.values
                    .flatMap { symMap ->
                        symMap.entries.mapNotNull { (sym, pkgs) ->
                            if (importPkg in pkgs) sym else null
                        }
                    }
            val keepStdlibWildcard =
                root in STDLIB_CRATE_ROOTS &&
                    !bodyTextForPubUseFilter.isNullOrBlank() &&
                    (symbolsInPkg.isEmpty() || symbolsInPkg.any { containsWholeIdentifier(bodyTextForPubUseFilter, it) })
            if (!keepStdlibWildcard) {
                continue
            }
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
    val inTestContext = isQuickcheckOrRandTestContext(item)
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
    return UnportedTestDepTokenScanner(
        trees = item.toTokenStream().toList(),
        unportedQuickcheck = unportedQuickcheck,
        unportedRand = unportedRand,
        inTestContext = inTestContext,
        importedQuickcheckGen = importedQuickcheckGen,
    ).matches()
}

private class UnportedTestDepTokenScanner(
    trees: List<TokenTree>,
    private val unportedQuickcheck: Boolean,
    private val unportedRand: Boolean,
    private val inTestContext: Boolean,
    private val importedQuickcheckGen: Boolean,
) : RustTokenCursor(trees) {
    fun matches(): Boolean {
        for (idx in 0 until size) {
            when (val tt = trees[idx]) {
                is TokenTree.Ident -> {
                    val raw = cleanIdentAt(idx).orEmpty()
                    val nextIsPathSep = punctCharAt(idx + 1) == ':' && punctCharAt(idx + 2) == ':'
                    if (unportedQuickcheck &&
                        (
                            (raw == "quickcheck" && nextIsPathSep) ||
                                (inTestContext && raw in UNPORTED_QUICKCHECK_IDENTS) ||
                                (inTestContext && importedQuickcheckGen && raw == "Gen")
                        )
                    ) {
                        return true
                    }
                    if (unportedRand &&
                        ((raw == "rand" && nextIsPathSep) || (inTestContext && raw in UNPORTED_RAND_TEST_IDENTS))
                    ) {
                        return true
                    }
                }
                is TokenTree.Group ->
                    if (UnportedTestDepTokenScanner(
                            trees = tt.value.stream().toList(),
                            unportedQuickcheck = unportedQuickcheck,
                            unportedRand = unportedRand,
                            inTestContext = inTestContext,
                            importedQuickcheckGen = importedQuickcheckGen,
                        ).matches()
                    ) {
                        return true
                    }
                else -> {}
            }
        }
        return false
    }
}

private const val OMITTED_QUICKCHECK_OR_RAND_COMMENT: String =
    "// Omitted quickcheck/rand property test items pending quickcheck-kotlin / rand-kotlin port (Hermes Kanban card 19971811)."

private fun KotlinJ2KEmitter.isQuickcheckOrRandTestContext(item: Item? = null): Boolean =
    headerContext.portLintDirective == "tests" ||
        headerContext.testClassName != null ||
        currentModPathPrefix.any { it == "test" || it == "tests" || it == "stdtests" } ||
        (item != null && hasTestAttribute(item.attributes))

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
                            RustTokenCursor.containsAnyIdentRecursive(item.toTokenStream(), omitted)
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
        RustTokenCursor.containsAnyIdentRecursive(item.toTokenStream(), currentOmittedTestIdents)
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
    isLocalItem: Boolean = false,
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
                val r = renderItem(sub, indent, modLocalTypeImpls, isLocalItem = isLocalItem, scopeSignatures = targetSignatures).trimEnd()
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
    val keys =
        RustTokenCursor(mac.tokens.clone().toList())
            .collectTopLevelIdentsOutsideAngles()
            .filter { it in FORWARD_DESERIALIZE_KEYS }
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
    val localForeignSignatures = linkedMapOf<String, Int>()
    for (foreign in item.items) {
        if (foreignItemHasDocOnlyCfg(foreign)) continue
        when (foreign) {
            is ForeignItem.Fn -> {
                val vis = if (isLocalItem) "" else renderVisibility(foreign.vis)
                val rawFnName =
                    foreign.sig.ident
                        .toString()
                        .removePrefix("r#")
                val fnName =
                    if (isLocalItem) {
                        activeLocalForeignFnRenames[rawFnName]
                            ?: rustFunctionIdentToKotlin(rawFnName).let { kt ->
                                if (kt == currentEnclosingFunctionName) {
                                    "extern${kt.replaceFirstChar { c -> c.uppercase() }}"
                                } else {
                                    kt
                                }
                            }
                    } else {
                        val collidedRename =
                            currentModPathPrefix
                                .lastOrNull()
                                ?.let { inspector.collidingInlineForeignFnRenames[it to rawFnName] }
                                ?: if (currentModPathPrefix.isEmpty()) inspector.collidingTopLevelForeignFnRenames[rawFnName] else null
                        collidedRename ?: rustFunctionIdentToKotlin(rawFnName)
                    }
                val interopParams = mutableListOf<InteropParamSpec>()
                scopeBuilder.enterDef()
                val (sig, retRendered) =
                    try {
                        renderForeignFunctionExpectSignature(
                            sig = foreign.sig,
                            visPrefix = vis,
                            fnName = fnName,
                            scopeSignatures = if (isLocalItem) localForeignSignatures else topLevelSignatureCounts,
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
                            fun alignArgToLong(idx: Int): String =
                                if (interopParams.getOrNull(idx)?.kotlinType?.substringAfterLast('.') == "Alignment") {
                                    "${pNames[idx]}.asUsize().toLong()"
                                } else {
                                    "${pNames[idx]}.toLong()"
                                }
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
                                    "= ${wrapLongToRet("kmAlloc(${pNames[0]}.toLong(), ${alignArgToLong(1)})")}"
                                }
                                "__rust_alloc_zeroed" -> {
                                    CoreIntrinsicLowering.registerCoreImport(this, "$coreNs.intrinsics.kmAllocZeroed")
                                    "= ${wrapLongToRet("kmAllocZeroed(${pNames[0]}.toLong(), ${alignArgToLong(1)})")}"
                                }
                                "__rust_dealloc" -> {
                                    CoreIntrinsicLowering.registerCoreImport(this, "$coreNs.intrinsics.kmDealloc")
                                    "= kmDealloc(${ptrArgToLong(0)}, ${pNames[1]}.toLong(), ${alignArgToLong(2)})"
                                }
                                "__rust_realloc" -> {
                                    CoreIntrinsicLowering.registerCoreImport(this, "$coreNs.intrinsics.kmRealloc")
                                    "= ${wrapLongToRet("kmRealloc(${ptrArgToLong(0)}, ${pNames[1]}.toLong(), ${alignArgToLong(2)}, ${pNames[3]}.toLong())")}"
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
                    val retSynTy = extractReturnSynType(foreign.sig.output)
                    val retBaseSimple = retRendered.substringBefore('<').substringAfterLast('.').trim()
                    val isReturnStructWithFields =
                        retDefault.endsWith("()") &&
                            (
                                retBaseSimple in inspector.tupleStructArityByType ||
                                    inspector.structFieldSynTypesByType[retBaseSimple]?.isNotEmpty() == true ||
                                    retBaseSimple in headerContext.externalCrateTupleStructArity ||
                                    scopedAbiKeysForRenderedType(retBaseSimple).any { k ->
                                        "$k.value" in headerContext.crateStructFieldTypes ||
                                            "$k.field0" in headerContext.crateStructFieldTypes
                                    }
                            )
                    val localBody =
                        when {
                            retRendered == "Unit" -> "{}"
                            (retDefault.startsWith("null as ") || retDefault.startsWith("error(") || isReturnStructWithFields) && retSynTy != null -> {
                                val coreNs = CoreIntrinsicLowering.resolveCoreNamespace(headerContext)
                                val layoutExpr = CoreIntrinsicLowering.resolveTypeLayoutExpression(retSynTy, null, this)
                                "= ${CoreIntrinsicLowering.renderZeroedLayoutReadExpression(layoutExpr, "$coreNs.intrinsics", this)}"
                            }
                            else -> "= $retDefault"
                        }
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
