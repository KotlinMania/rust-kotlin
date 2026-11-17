package io.github.kotlinmania.rustkotlin

import io.github.kotlinmania.io.files.SystemFileSystem
import io.github.kotlinmania.io.files.Path as KmPath

internal const val CLI_VERSION_TEXT: String =
    "rust-kotlin 0.1.0 (proc-macro2-kotlin 0.1.8, syn-kotlin 0.5.0)"

internal val CLI_USAGE_TEXT: String =
    """
    rust-kotlin: deterministic Rust-to-Kotlin translator (1 .rs file -> 1 .kt file)
    Usage:
      rust-kotlin inspect [options] <input.rs | dir...>
      rust-kotlin translate [options] <input.rs> <output.kt>
      rust-kotlin [translate] [options] <input.rs | dir...>
      rust-kotlin --help | --version

    Options:
      -r, --recursive                  Recursively translate/inspect .rs files in directories (skips lib.rs and mod.rs)
      -o, --output <file.kt|dir>       Target .kt file (single input) or output root directory (batch/single)
          --in-place                   Write sibling <PascalCase>.kt next to each input .rs file
          --stdout                     Print translated Kotlin source to stdout without writing disk files
          --dry-run                    Print planned <input.rs> -> <output.kt> actions without writing files
      -f, --force, --overwrite         Overwrite existing target .kt files (default refuses existing files)
          --skip-existing              Skip target .kt files that already exist
      --rustkotlin-properties <file>   Explicit rustkotlin.properties file for group/namespace/crate inference
          --package-properties <file>  Explicit package.properties file for package/crate overrides
      -p, --package <pkg>              Explicit Kotlin package name (appends relative subpackages in recursive mode)
      -n, --namespace <ns>             Explicit crate root Kotlin namespace (e.g. io.github.kotlinmania.serdejson)
          --group <group>              Explicit project group (e.g. io.github.kotlinmania)
      -c, --crate-namespace <k>=<ns>   Map external crate <k> to Kotlin namespace <ns> (repeatable)
          --source-root <dir>          Base directory for computing relative // port-lint paths
          --test-output <dir>          Destination root for inline #[cfg(test)] modules (required when present)
          --port-lint <mode>           Port-lint header directive: source (default), tests, or none
          --raw-abi                    Preserve raw C/Win32 ABI struct/constant/function identifiers
          --final-structs              Emit final classes (non-open) for structs/unions
          --split-module-roots         Split implementation mod.rs/lib.rs into focused files and place leaf modules in subpackages
          --target-pointer-width <n>   Target pointer width in bits: 16, 32, or 64 (default: 64)
          --target-endian <endian>     Target byte order: little or big (default: little)
          --target-arch <arch>         Target CPU architecture for cfg predicates (e.g. x86_64, aarch64, wasm32)
          --target-os <os>             Target OS for cfg predicates (e.g. macos, linux, windows, ios, wasi)
          --target-env <env>           Target ABI/environment for cfg predicates (e.g. gnu, msvc, musl)
          --target-vendor <vendor>     Target vendor for cfg predicates (e.g. apple, pc, unknown)
          --target-family <family>     Target family for cfg predicates, repeatable (e.g. unix, windows, wasm)
          --feature <name>             Enable Cargo feature <name> for cfg(feature = "...") (repeatable)
          --cfg <spec>                 Enable custom cfg flag or key=value predicate (repeatable)
          --cfg-closed-world           Treat unspecified cfg predicates/features as false instead of open-world
          --scalar-char                Translate Rust `char` to `RustChar` (32-bit Unicode scalar) instead of `Char`
          --timings                    Report lexer/parser/emitter stage timings
      -q, --quiet                      Suppress progress summary lines
      -v, --version                    Show version information
      -h, --help                       Show this help message
    """.trimIndent()

private enum class CliActionKind {
    HELP,
    VERSION,
    INSPECT,
    TRANSLATE,
}

private data class ParsedCliOptions(
    val action: CliActionKind,
    val inputs: List<String> = emptyList(),
    val classicOutputPath: String? = null,
    val outputTarget: String? = null,
    val recursive: Boolean = false,
    val inPlace: Boolean = false,
    val toStdout: Boolean = false,
    val dryRun: Boolean = false,
    val forceOverwrite: Boolean = false,
    val skipExisting: Boolean = false,
    val rustKotlinPropertiesPath: String? = null,
    val packagePropertiesPath: String? = null,
    val packageOverride: String? = null,
    val namespaceOverride: String? = null,
    val groupOverride: String? = null,
    val crateNamespaces: Map<String, String> = emptyMap(),
    val sourceRoot: String? = null,
    val portLintDirective: String = "source",
    val testOutputTarget: String? = null,
    val rawAbiMode: Boolean = false,
    val finalStructs: Boolean = false,
    val splitModuleRoots: Boolean = false,
    val scalarChar: Boolean = false,
    val cfgTargetProfile: CfgTargetProfile = CfgTargetProfile(),
    val timings: Boolean = false,
    val quiet: Boolean = false,
)

private data class DiscoveredRustInput(
    val inputPath: String,
    val relativeDir: String,
    val discoveryRoot: String?,
)

/** IO is injected for focused invariant tests; renderers receive source text, never a writer. */
internal fun runSingleFileCommand(
    args: Array<String>,
    read: (String) -> String = ::readInput,
    write: (String, String) -> Unit = ::writeNew,
    writeForce: (String, String) -> Unit = ::writeOverwrite,
    exists: (String) -> Boolean = { probeTargetKotlinFile(it).exists },
    isDirectory: (String) -> Boolean = { SystemFileSystem.metadataOrNull(KmPath(it))?.isDirectory == true },
    collectRustFiles: ((List<String>) -> List<String>)? = null,
    report: (String) -> Unit = ::println,
    resolveRustKotlinProperties: (String?) -> RustKotlinProperties? = ::findNearestRustKotlinProperties,
    resolvePackageProperties: (String?) -> FolderPackageProperties? = ::findNearestPackageProperties,
    resolveCrateMacroSources: (String?) -> List<String> = ::discoverCrateMacroSourcesFromDisk,
    resolveCrateFlatFileModules: (String?, String?) -> Set<String> = ::discoverCrateFlatFileModulesFromDisk,
    resolveCrateRawAbiMetadata: (String?, String?, Boolean, Boolean) -> CrateRawAbiMetadata = { inp, root, raw, split ->
        discoverCrateRawAbiMetadataFromDisk(inp, root, raw, split, TranslationSession.finalStructs)
    },
) {
    val parsed = parseCliOptions(args)
    when (parsed.action) {
        CliActionKind.HELP -> {
            report(CLI_USAGE_TEXT)
            return
        }
        CliActionKind.VERSION -> {
            report(CLI_VERSION_TEXT)
            return
        }
        CliActionKind.INSPECT,
        CliActionKind.TRANSLATE,
        -> {}
    }

    val effectiveCollectRustFiles: (List<String>) -> List<String> =
        collectRustFiles
            ?: if (parsed.splitModuleRoots) {
                ::collectAllRustFilePathsIncludingModuleRoots
            } else {
                ::collectTranspilableRustFilePaths
            }

    val prevTimings = TranslationSession.timings
    val prevRawAbi = TranslationSession.rawAbiMode
    val prevFinalStructs = TranslationSession.finalStructs
    val prevSplitModRoots = TranslationSession.splitModuleRoots
    val prevCfgProfile = TranslationSession.cfgTargetProfile
    val prevEmitted128 = TranslationSession.emittedInt128Support
    val prevEmittedAny = TranslationSession.emittedRustAnySupport
    if (parsed.timings) {
        TranslationSession.timings = true
    }
    TranslationSession.rawAbiMode = parsed.rawAbiMode
    TranslationSession.finalStructs = parsed.finalStructs
    TranslationSession.splitModuleRoots = parsed.splitModuleRoots
    TranslationSession.cfgTargetProfile = parsed.cfgTargetProfile
    TranslationSession.emittedInt128Support = false
    TranslationSession.emittedRustAnySupport = false
    try {
        if (parsed.action == CliActionKind.INSPECT) {
            executeInspect(
                parsed = parsed,
                read = read,
                isDirectory = isDirectory,
                collectRustFiles = effectiveCollectRustFiles,
                report = report,
            )
        } else {
            executeTranslate(
                parsed = parsed,
                read = read,
                write = write,
                writeForce = writeForce,
                exists = exists,
                isDirectory = isDirectory,
                collectRustFiles = effectiveCollectRustFiles,
                report = report,
                resolveRustKotlinProperties = resolveRustKotlinProperties,
                resolvePackageProperties = resolvePackageProperties,
                resolveCrateMacroSources = resolveCrateMacroSources,
                resolveCrateFlatFileModules = resolveCrateFlatFileModules,
                resolveCrateRawAbiMetadata = resolveCrateRawAbiMetadata,
            )
        }
    } finally {
        TranslationSession.timings = prevTimings
        TranslationSession.rawAbiMode = prevRawAbi
        TranslationSession.finalStructs = prevFinalStructs
        TranslationSession.splitModuleRoots = prevSplitModRoots
        TranslationSession.cfgTargetProfile = prevCfgProfile
        TranslationSession.emittedInt128Support = prevEmitted128
        TranslationSession.emittedRustAnySupport = prevEmittedAny
    }
}

private fun parseCliOptions(args: Array<String>): ParsedCliOptions {
    require(args.isNotEmpty()) {
        "usage: rust-kotlin inspect input.rs | translate [options] input.rs [output.kt]; try --help"
    }

    var showHelp = false
    var showVersion = false
    var recursive = false
    var outputTarget: String? = null
    var inPlace = false
    var toStdout = false
    var dryRun = false
    var forceOverwrite = false
    var skipExisting = false
    var rustKotlinPropertiesPath: String? = null
    var packagePropertiesPath: String? = null
    var packageOverride: String? = null
    var namespaceOverride: String? = null
    var groupOverride: String? = null
    val crateNamespaces = linkedMapOf<String, String>()
    var sourceRoot: String? = null
    var portLintDirective = "source"
    var testOutputTarget: String? = null
    var rawAbiMode = false
    var finalStructs = false
    var splitModuleRoots = false
    var targetPointerWidth = 64
    var targetEndian = "little"
    var targetArch: String? = null
    var targetOs: String? = null
    var targetEnv: String? = null
    var targetVendor: String? = null
    val targetFamilies = linkedSetOf<String>()
    val enabledFeatures = linkedSetOf<String>()
    val enabledCfgFlags = linkedSetOf<String>()
    var cfgClosedWorld = false
    var scalarChar = false
    var explicitTargetSelection = false
    var timings = false
    var quiet = false
    val positionals = mutableListOf<String>()

    var idx = 0
    var onlyPositionals = false
    while (idx < args.size) {
        val raw = args[idx]
        if (onlyPositionals || !raw.startsWith("-") || raw == "-") {
            positionals += raw
            idx++
            continue
        }
        if (raw == "--") {
            onlyPositionals = true
            idx++
            continue
        }

        fun nextValue(flagName: String): String {
            idx++
            require(idx < args.size) { "option $flagName requires a value" }
            return args[idx]
        }

        val eqIdx = raw.indexOf('=')
        val optName = if (eqIdx > 0 && raw.startsWith("--")) raw.substring(0, eqIdx) else raw
        val inlineVal = if (eqIdx > 0 && raw.startsWith("--")) raw.substring(eqIdx + 1) else null

        fun optValue(): String = inlineVal ?: nextValue(optName)

        when (optName) {
            "--test-output" -> {
                testOutputTarget = optValue()
                require(testOutputTarget.isNotBlank()) { "--test-output requires a non-empty directory" }
            }
            "-h", "--help" -> showHelp = true
            "-v", "--version" -> showVersion = true
            "-r", "--recursive" -> {
                require(inlineVal == null) { "$optName does not take a value" }
                recursive = true
            }
            "-o", "--output" -> outputTarget = optValue()
            "--in-place" -> {
                require(inlineVal == null) { "$optName does not take a value" }
                inPlace = true
            }
            "--stdout" -> {
                require(inlineVal == null) { "$optName does not take a value" }
                toStdout = true
            }
            "--dry-run" -> {
                require(inlineVal == null) { "$optName does not take a value" }
                dryRun = true
            }
            "-f", "--force", "--overwrite" -> {
                require(inlineVal == null) { "$optName does not take a value" }
                forceOverwrite = true
            }
            "--skip-existing" -> {
                require(inlineVal == null) { "$optName does not take a value" }
                skipExisting = true
            }
            "--rustkotlin-properties" -> rustKotlinPropertiesPath = optValue()
            "--package-properties" -> packagePropertiesPath = optValue()
            "-p", "--package" -> packageOverride = optValue()
            "-n", "--namespace" -> namespaceOverride = optValue()
            "--group" -> groupOverride = optValue()
            "-c", "--crate-namespace" -> {
                val spec = optValue()
                val sep = spec.indexOf('=')
                require(sep > 0 && sep < spec.lastIndex) {
                    "invalid --crate-namespace '$spec'; expected <crate>=<kotlin.package>"
                }
                val crateKey = rustModuleIdentToKotlin(spec.substring(0, sep).trim())
                val cratePkg = spec.substring(sep + 1).trim()
                require(crateKey.isNotEmpty() && cratePkg.isNotEmpty()) {
                    "invalid --crate-namespace '$spec'; expected <crate>=<kotlin.package>"
                }
                crateNamespaces[crateKey] = cratePkg
            }
            "--source-root" -> sourceRoot = optValue()
            "--port-lint" -> {
                val mode = optValue().trim().lowercase()
                require(mode == "source" || mode == "tests" || mode == "none") {
                    "invalid --port-lint '$mode'; expected source, tests, or none"
                }
                portLintDirective = mode
            }
            "--raw-abi" -> {
                require(inlineVal == null) { "$optName does not take a value" }
                rawAbiMode = true
            }
            "--final-structs" -> {
                require(inlineVal == null) { "$optName does not take a value" }
                finalStructs = true
            }
            "--split-module-roots" -> {
                require(inlineVal == null) { "$optName does not take a value" }
                splitModuleRoots = true
            }
            "--target-pointer-width" -> {
                val width = optValue().trim().toIntOrNull()
                require(width == 16 || width == 32 || width == 64) {
                    "invalid --target-pointer-width; expected 16, 32, or 64"
                }
                targetPointerWidth = width
                explicitTargetSelection = true
            }
            "--target-endian" -> {
                val endian = optValue().trim().lowercase()
                require(endian == "little" || endian == "big") {
                    "invalid --target-endian '$endian'; expected little or big"
                }
                targetEndian = endian
                explicitTargetSelection = true
            }
            "--target-arch" -> {
                targetArch = optValue().trim().takeIf { it.isNotEmpty() }
                explicitTargetSelection = true
            }
            "--target-os" -> {
                targetOs = optValue().trim().takeIf { it.isNotEmpty() }
                explicitTargetSelection = true
            }
            "--target-env" -> {
                targetEnv = optValue().trim().takeIf { it.isNotEmpty() }
                explicitTargetSelection = true
            }
            "--target-vendor" -> {
                targetVendor = optValue().trim().takeIf { it.isNotEmpty() }
                explicitTargetSelection = true
            }
            "--target-family" -> {
                val fam = optValue().trim()
                if (fam.isNotEmpty()) targetFamilies += fam
                explicitTargetSelection = true
            }
            "--feature" -> {
                val feat = optValue().trim()
                if (feat.isNotEmpty()) enabledFeatures += feat
            }
            "--cfg" -> {
                val spec = optValue().trim()
                require(spec.isNotEmpty()) { "--cfg requires a non-empty predicate" }
                val kvSep = spec.indexOf('=')
                if (kvSep > 0) {
                    val cfgKey = normalizeCliCfgKey(spec.substring(0, kvSep))
                    require(cfgKey.isNotEmpty()) { "--cfg requires a non-empty key before '='" }
                    val cfgVal = unquoteCliCfgValue(spec.substring(kvSep + 1))
                    when (cfgKey) {
                        "target_pointer_width" -> {
                            val w = cfgVal.toIntOrNull()
                            require(w == 16 || w == 32 || w == 64) {
                                "invalid --cfg target_pointer_width='$cfgVal'"
                            }
                            targetPointerWidth = w
                            explicitTargetSelection = true
                        }
                        "target_endian" -> {
                            require(cfgVal == "little" || cfgVal == "big") {
                                "invalid --cfg target_endian='$cfgVal'"
                            }
                            targetEndian = cfgVal
                            explicitTargetSelection = true
                        }
                        "target_arch" -> {
                            targetArch = cfgVal
                            explicitTargetSelection = true
                        }
                        "target_os" -> {
                            targetOs = cfgVal
                            explicitTargetSelection = true
                        }
                        "target_env" -> {
                            targetEnv = cfgVal
                            explicitTargetSelection = true
                        }
                        "target_vendor" -> {
                            targetVendor = cfgVal
                            explicitTargetSelection = true
                        }
                        "target_family" -> {
                            if (cfgVal.isNotEmpty()) targetFamilies += cfgVal
                            explicitTargetSelection = true
                        }
                        "feature" -> {
                            if (cfgVal.isNotEmpty()) enabledFeatures += cfgVal
                        }
                        else -> enabledCfgFlags += "$cfgKey=$cfgVal"
                    }
                } else {
                    val normFlag = normalizeCliCfgKey(spec)
                    if (normFlag.isNotEmpty()) enabledCfgFlags += normFlag
                }
            }
            "--cfg-closed-world" -> {
                require(inlineVal == null) { "$optName does not take a value" }
                cfgClosedWorld = true
            }
            "--scalar-char" -> {
                require(inlineVal == null) { "$optName does not take a value" }
                scalarChar = true
            }
            "--timings" -> {
                require(inlineVal == null) { "$optName does not take a value" }
                timings = true
            }
            "-q", "--quiet" -> {
                require(inlineVal == null) { "$optName does not take a value" }
                quiet = true
            }
            else -> throw IllegalArgumentException("unknown option: $raw")
        }
        idx++
    }

    if (showHelp) {
        return ParsedCliOptions(action = CliActionKind.HELP)
    }
    if (showVersion) {
        return ParsedCliOptions(action = CliActionKind.VERSION)
    }

    val outputModesCount = listOf(outputTarget != null, inPlace, toStdout).count { it }
    require(outputModesCount <= 1) {
        "--output, --in-place, and --stdout are mutually exclusive"
    }
    require(!(forceOverwrite && skipExisting)) {
        "--force and --skip-existing are mutually exclusive"
    }
    require(positionals.isNotEmpty()) {
        "usage: rust-kotlin inspect input.rs | translate [options] input.rs [output.kt]; try --help"
    }

    val builtCfgProfile =
        CfgTargetProfile(
            pointerWidth = targetPointerWidth,
            targetEndian = targetEndian,
            targetArch = targetArch,
            targetOs = targetOs,
            targetEnv = targetEnv,
            targetVendor = targetVendor,
            targetFamilies =
                when {
                    targetFamilies.isNotEmpty() -> targetFamilies
                    cfgClosedWorld -> emptySet()
                    else -> null
                },
            enabledFeatures =
                when {
                    enabledFeatures.isNotEmpty() -> enabledFeatures
                    cfgClosedWorld -> emptySet()
                    else -> null
                },
            enabledCfgFlags = enabledCfgFlags,
            unknownPolicy = if (cfgClosedWorld) UnknownCfgPolicy.CLOSED_WORLD_FALSE else UnknownCfgPolicy.OPEN_WORLD_NULL,
            useRelativeDirPlatformHints = recursive && !explicitTargetSelection && !cfgClosedWorld,
        )

    val first = positionals[0]
    if (first == "inspect") {
        val inputs = positionals.drop(1)
        require(inputs.isNotEmpty()) {
            "inspect requires an input .rs file (or directory with -r)"
        }
        require(outputModesCount == 0 && !dryRun && !forceOverwrite && !skipExisting && testOutputTarget == null) {
            "inspect does not accept output write options"
        }
        if (!recursive) {
            require(inputs.size == 1 && inputs[0].endsWith(".rs", ignoreCase = true)) {
                "inspect requires a single .rs file (pass -r for directories)"
            }
        }
        return ParsedCliOptions(
            action = CliActionKind.INSPECT,
            inputs = inputs,
            recursive = recursive,
            rawAbiMode = rawAbiMode,
            finalStructs = finalStructs,
            splitModuleRoots = splitModuleRoots,
            scalarChar = scalarChar,
            cfgTargetProfile = builtCfgProfile,
            timings = timings,
            quiet = quiet,
        )
    }

    val translatePositionals =
        if (first == "translate") {
            positionals.drop(1)
        } else {
            require(outputModesCount > 0 || dryRun) {
                "unknown command '$first'; expected 'inspect' or 'translate' (or pass -o / --in-place / --stdout / --dry-run)"
            }
            positionals
        }

    if (outputModesCount == 0 && !dryRun) {
        require(!recursive && translatePositionals.size == 2) {
            "usage: rust-kotlin inspect input.rs | translate input.rs output.kt; one input, one output (or pass -o / --in-place / --stdout / --dry-run)"
        }
        val inputPath = translatePositionals[0]
        val outputPath = translatePositionals[1]
        validateSingleRustInputPath(inputPath)
        validateExplicitKotlinOutputPath(inputPath, outputPath)
        return ParsedCliOptions(
            action = CliActionKind.TRANSLATE,
            inputs = listOf(inputPath),
            classicOutputPath = outputPath,
            forceOverwrite = forceOverwrite,
            skipExisting = skipExisting,
            rustKotlinPropertiesPath = rustKotlinPropertiesPath,
            packagePropertiesPath = packagePropertiesPath,
            packageOverride = packageOverride,
            namespaceOverride = namespaceOverride,
            groupOverride = groupOverride,
            crateNamespaces = crateNamespaces,
            sourceRoot = sourceRoot,
            portLintDirective = portLintDirective,
            testOutputTarget = testOutputTarget,
            rawAbiMode = rawAbiMode,
            finalStructs = finalStructs,
            splitModuleRoots = splitModuleRoots,
            scalarChar = scalarChar,
            cfgTargetProfile = builtCfgProfile,
            timings = timings,
            quiet = quiet,
        )
    }

    require(translatePositionals.isNotEmpty()) {
        "translate requires at least one input .rs file (or directory with -r)"
    }
    if (!recursive) {
        for (inp in translatePositionals) {
            if (splitModuleRoots && outputTarget?.endsWith(".kt", ignoreCase = true) != true) {
                require(inp.endsWith(".rs", ignoreCase = true)) {
                    "input must be a Rust .rs file: $inp (pass -r for directories)"
                }
            } else {
                validateSingleRustInputPath(inp)
            }
        }
    }
    if (outputTarget != null && outputTarget.endsWith(".kt", ignoreCase = true)) {
        require(!recursive && translatePositionals.size == 1) {
            "-o <file.kt> requires a single .rs input file; pass an output directory for batch/recursive translation"
        }
        validateSingleRustInputPath(translatePositionals[0])
        validateExplicitKotlinOutputPath(translatePositionals[0], outputTarget)
    }

    return ParsedCliOptions(
        action = CliActionKind.TRANSLATE,
        inputs = translatePositionals,
        outputTarget = outputTarget,
        recursive = recursive,
        inPlace = inPlace,
        toStdout = toStdout,
        dryRun = dryRun,
        forceOverwrite = forceOverwrite,
        skipExisting = skipExisting,
        rustKotlinPropertiesPath = rustKotlinPropertiesPath,
        packagePropertiesPath = packagePropertiesPath,
        packageOverride = packageOverride,
        namespaceOverride = namespaceOverride,
        groupOverride = groupOverride,
        crateNamespaces = crateNamespaces,
        sourceRoot = sourceRoot,
        portLintDirective = portLintDirective,
        testOutputTarget = testOutputTarget,
        rawAbiMode = rawAbiMode,
        finalStructs = finalStructs,
        splitModuleRoots = splitModuleRoots,
        scalarChar = scalarChar,
        cfgTargetProfile = builtCfgProfile,
        timings = timings,
        quiet = quiet,
    )
}

private fun validateSingleRustInputPath(inputPath: String) {
    val fileName = inputPath.replace('\\', '/').substringAfterLast('/')
    require(inputPath.endsWith(".rs", ignoreCase = true)) {
        "input must be a Rust .rs file: $inputPath (pass -r for directories)"
    }
    require(!isRustModuleRootFileName(fileName)) {
        "crate/module root files ($fileName) must never be emitted as Lib.kt or Mod.kt"
    }
}

private fun validateExplicitKotlinOutputPath(inputPath: String, outputPath: String) {
    val outputBaseName = outputPath.replace('\\', '/').substringAfterLast('/')
    require(outputPath.endsWith(".kt") && outputPath != inputPath) {
        "output must be a distinct Kotlin .kt file, not a directory"
    }
    require(!outputBaseName.equals("Lib.kt", ignoreCase = true) && !outputBaseName.equals("Mod.kt", ignoreCase = true)) {
        "crate/module root files must never be emitted as Lib.kt or Mod.kt"
    }
}

private val CARGO_TARGET_ROOT_DIR_NAMES = setOf("src", "tests", "benches", "examples")

private fun discoverInputs(
    rawInputs: List<String>,
    recursive: Boolean,
    splitModuleRoots: Boolean,
    sourceRoot: String?,
    isDirectory: (String) -> Boolean,
    collectRustFiles: (List<String>) -> List<String>,
): List<DiscoveredRustInput> {
    val normSourceRoot = sourceRoot?.replace('\\', '/')?.trimEnd('/')?.takeIf { it.isNotEmpty() }
    if (!recursive) {
        return rawInputs.map { inp ->
            if (!splitModuleRoots) {
                validateSingleRustInputPath(inp)
            }
            val normInp = inp.replace('\\', '/')
            val fileName = normInp.substringAfterLast('/')
            val parent = normInp.substringBeforeLast('/', missingDelimiterValue = "")
            val lastSeg = parent.substringAfterLast('/', missingDelimiterValue = parent)
            val isCrateRootFile = fileName.equals("lib.rs", ignoreCase = true) || fileName.equals("main.rs", ignoreCase = true)
            val relDir =
                if (normSourceRoot != null && normInp.startsWith("$normSourceRoot/")) {
                    normInp.removePrefix("$normSourceRoot/").substringBeforeLast('/', missingDelimiterValue = "")
                } else if (isCrateRootFile) {
                    ""
                } else if (splitModuleRoots &&
                    isRustModuleRootFileName(fileName) &&
                    lastSeg !in CARGO_TARGET_ROOT_DIR_NAMES &&
                    lastSeg.isNotEmpty()
                ) {
                    lastSeg
                } else {
                    ""
                }
            DiscoveredRustInput(inputPath = inp, relativeDir = relDir, discoveryRoot = normSourceRoot)
        }
    }
    val discovered = mutableListOf<DiscoveredRustInput>()
    val seenPaths = hashSetOf<String>()
    for (rawInput in rawInputs) {
        if (rawInput.endsWith(".rs", ignoreCase = true) && !isDirectory(rawInput)) {
            val fileName = rawInput.replace('\\', '/').substringAfterLast('/')
            if ((splitModuleRoots || !isRustModuleRootFileName(fileName)) && seenPaths.add(rawInput.replace('\\', '/'))) {
                discovered += DiscoveredRustInput(inputPath = rawInput, relativeDir = "", discoveryRoot = null)
            }
        } else {
            val normRoot = rawInput.replace('\\', '/').trimEnd('/')
            val collected = collectRustFiles(listOf(rawInput))
            for (filePath in collected) {
                val normFile = filePath.replace('\\', '/')
                if (!seenPaths.add(normFile)) continue
                val relPath =
                    if (normFile.startsWith("$normRoot/")) {
                        normFile.removePrefix("$normRoot/")
                    } else {
                        normFile.substringAfterLast('/')
                    }
                val relDir = relPath.substringBeforeLast('/', missingDelimiterValue = "")
                discovered +=
                    DiscoveredRustInput(
                        inputPath = filePath,
                        relativeDir = relDir,
                        discoveryRoot = normRoot,
                    )
            }
        }
    }
    require(discovered.isNotEmpty()) {
        "no transpilable Rust (.rs) files found in ${rawInputs.joinToString(", ")}"
    }
    return discovered
}

private fun executeInspect(
    parsed: ParsedCliOptions,
    read: (String) -> String,
    isDirectory: (String) -> Boolean,
    collectRustFiles: (List<String>) -> List<String>,
    report: (String) -> Unit,
) {
    val discovered = discoverInputs(parsed.inputs, parsed.recursive, parsed.splitModuleRoots, parsed.sourceRoot, isDirectory, collectRustFiles)
    val multiFile = discovered.size > 1
    for (entry in discovered) {
        val recognized = recognizeRust(read(entry.inputPath))
        val tokenCount = recognized.tokens.clone().count()
        val itemCount = recognized.file.items.size
        val prefix = if (multiFile) "${entry.inputPath}: " else ""
        report("${prefix}lexer=proc-macro2-kotlin:0.1.8 parser=syn-kotlin:0.5.0 tokens=$tokenCount items=$itemCount")
        if (!parsed.quiet) {
            recognized.file.items.forEachIndexed { index, item ->
                report("$prefix$index ${item::class.simpleName}")
            }
        }
    }
}

private fun computeEffectiveRelativePackageSegments(
    entry: DiscoveredRustInput,
    parsed: ParsedCliOptions,
    sharedInlineModuleRelFiles: Set<String> = emptySet(),
): List<String> {
    val dirSegs =
        if (entry.relativeDir.isEmpty()) {
            emptyList()
        } else {
            entry.relativeDir
                .split('/')
                .filter { it.isNotEmpty() }
                .map { seg -> rustModuleIdentToKotlin(seg.replace('-', '_')) }
        }
    if (!parsed.splitModuleRoots) return dirSegs
    val rustFileName = entry.inputPath.replace('\\', '/').substringAfterLast('/')
    if (isRustModuleRootFileName(rustFileName)) return dirSegs
    val relFile = if (entry.relativeDir.isEmpty()) rustFileName else "${entry.relativeDir}/$rustFileName"
    if (relFile in sharedInlineModuleRelFiles) return dirSegs
    val stem = rustFileName.removeSuffix(".rs").removeSuffix(".RS")
    if (stem.isEmpty()) return dirSegs
    return dirSegs + rustModuleIdentToKotlin(stem.replace('-', '_'))
}

private fun computeModuleRootOutputDirectory(
    entry: DiscoveredRustInput,
    parsed: ParsedCliOptions,
    sharedInlineModuleRelFiles: Set<String> = emptySet(),
): String? {
    if (parsed.toStdout) return null
    val normInput = entry.inputPath.replace('\\', '/')
    if (parsed.inPlace || (parsed.outputTarget == null && parsed.dryRun)) {
        return normInput.substringBeforeLast('/', missingDelimiterValue = "")
    }
    val outTarget = requireNotNull(parsed.outputTarget)
    val normOutRoot = outTarget.replace('\\', '/').trimEnd('/')
    val outRelDir =
        if (entry.relativeDir.isEmpty()) {
            ""
        } else if (normOutRoot.contains("/kotlin/") || parsed.splitModuleRoots) {
            computeEffectiveRelativePackageSegments(entry, parsed, sharedInlineModuleRelFiles).joinToString("/")
        } else {
            entry.relativeDir
        }
    return if (outRelDir.isEmpty()) normOutRoot else "$normOutRoot/$outRelDir"
}

private fun computeBatchOutputPath(
    entry: DiscoveredRustInput,
    parsed: ParsedCliOptions,
    sharedInlineModuleRelFiles: Set<String> = emptySet(),
): String? {
    if (parsed.toStdout) return null
    if (parsed.classicOutputPath != null) return parsed.classicOutputPath

    val normInput = entry.inputPath.replace('\\', '/')
    val rustFileName = normInput.substringAfterLast('/')
    val kotlinFileName = computeTargetKotlinFileName(rustFileName)

    if (parsed.inPlace || (parsed.outputTarget == null && parsed.dryRun)) {
        val parentDir = normInput.substringBeforeLast('/', missingDelimiterValue = "")
        return if (parentDir.isEmpty()) kotlinFileName else "$parentDir/$kotlinFileName"
    }

    val outTarget = requireNotNull(parsed.outputTarget)
    if (outTarget.endsWith(".kt", ignoreCase = true) && !parsed.recursive && parsed.inputs.size == 1) {
        return outTarget
    }

    val normOutRoot = outTarget.replace('\\', '/').trimEnd('/')
    val outRelDir =
        if (normOutRoot.contains("/kotlin/") || parsed.splitModuleRoots) {
            computeEffectiveRelativePackageSegments(entry, parsed, sharedInlineModuleRelFiles).joinToString("/")
        } else {
            entry.relativeDir
        }
    return if (outRelDir.isEmpty()) {
        "$normOutRoot/$kotlinFileName"
    } else {
        "$normOutRoot/$outRelDir/$kotlinFileName"
    }
}

private fun computeModuleRootPascalName(
    entry: DiscoveredRustInput,
    finalNamespace: String?,
    isTestRoot: Boolean = false,
): String {
    val lastDirSeg =
        entry.relativeDir
            .split('/')
            .lastOrNull { it.isNotEmpty() }
    if (lastDirSeg != null) {
        return KotlinNamingPolicy.Default.toTypeIdentifier(lastDirSeg.replace('-', '_'), allowRawAbi = false)
    }
    val nsTail = finalNamespace?.substringAfterLast('.')?.takeIf { it.isNotEmpty() }
    val baseName =
        if (nsTail != null) {
            KotlinNamingPolicy.Default.toTypeIdentifier(nsTail, allowRawAbi = false)
        } else {
            "Crate"
        }
    return if (isTestRoot && !baseName.endsWith("Test")) "${baseName}Test" else baseName
}

private fun executeTranslate(
    parsed: ParsedCliOptions,
    read: (String) -> String,
    write: (String, String) -> Unit,
    writeForce: (String, String) -> Unit,
    exists: (String) -> Boolean,
    isDirectory: (String) -> Boolean,
    collectRustFiles: (List<String>) -> List<String>,
    report: (String) -> Unit,
    resolveRustKotlinProperties: (String?) -> RustKotlinProperties?,
    resolvePackageProperties: (String?) -> FolderPackageProperties?,
    resolveCrateMacroSources: (String?) -> List<String>,
    resolveCrateFlatFileModules: (String?, String?) -> Set<String>,
    resolveCrateRawAbiMetadata: (String?, String?, Boolean, Boolean) -> CrateRawAbiMetadata,
) {
    val explicitRustKotlinProps = parsed.rustKotlinPropertiesPath?.let { parseRustKotlinProperties(read(it)) }
    val explicitPkgProps = parsed.packagePropertiesPath?.let { parsePackageProperties(read(it)) }
    val discovered = discoverInputs(parsed.inputs, parsed.recursive, parsed.splitModuleRoots, parsed.sourceRoot, isDirectory, collectRustFiles)
    val effectiveCfgProfile =
        if (parsed.portLintDirective != "tests") {
            parsed.cfgTargetProfile.copy(
                enabledCfgFlags = parsed.cfgTargetProfile.enabledCfgFlags - "test",
                disabledCfgFlags = parsed.cfgTargetProfile.disabledCfgFlags + "test",
            )
        } else {
            parsed.cfgTargetProfile
        }
    val supportedDiscovered =
        if (parsed.recursive && discovered.size > 1) {
            discovered.filterNot { ent ->
                val discRoot = ent.discoveryRoot
                val normEnt = ent.inputPath.replace('\\', '/')
                val relPath =
                    if (discRoot != null && normEnt.startsWith("$discRoot/")) {
                        normEnt.removePrefix("$discRoot/")
                    } else {
                        ent.relativeDir.takeIf { it.isNotEmpty() }?.let { "$it/${normEnt.substringAfterLast('/')}" }
                            ?: normEnt.substringAfterLast('/')
                    }
                isUnsupportedTargetRustRelPath(relPath, effectiveCfgProfile)
            }
        } else {
            discovered
        }
    val filteredDiscovered =
        if (parsed.recursive && supportedDiscovered.size > 1) {
            val disabledByRoot = mutableMapOf<String, Set<String>>()
            for ((discRoot, entriesInRoot) in supportedDiscovered.groupBy { it.discoveryRoot }) {
                if (discRoot == null) continue
                val relTextMap = linkedMapOf<String, String>()
                for (ent in entriesInRoot) {
                    val normEnt = ent.inputPath.replace('\\', '/')
                    val relPath =
                        if (normEnt.startsWith("$discRoot/")) {
                            normEnt.removePrefix("$discRoot/")
                        } else {
                            normEnt.substringAfterLast('/')
                        }
                    relTextMap[relPath] = read(ent.inputPath)
                }
                disabledByRoot[discRoot] =
                    collectCfgDisabledChildModuleRelPrefixes(
                        sourcesByRelPath = relTextMap,
                        cfgProfile = effectiveCfgProfile,
                        skipCfgTest = parsed.portLintDirective != "tests",
                    )
            }
            supportedDiscovered.filterNot { ent ->
                val discRoot = ent.discoveryRoot ?: return@filterNot false
                val disabledSet = disabledByRoot[discRoot].orEmpty()
                if (disabledSet.isEmpty()) return@filterNot false
                val normEnt = ent.inputPath.replace('\\', '/')
                val relPath =
                    if (normEnt.startsWith("$discRoot/")) {
                        normEnt.removePrefix("$discRoot/")
                    } else {
                        normEnt.substringAfterLast('/')
                    }
                isRelPathUnderDisabledModulePrefix(relPath, disabledSet)
            }
        } else {
            supportedDiscovered
        }
    var resolvedProjectNamespaceForScaffold: String? = parsed.namespaceOverride ?: explicitRustKotlinProps?.projectNamespace
    val isBatchOutput =
        !parsed.toStdout &&
            parsed.classicOutputPath == null &&
            parsed.outputTarget != null &&
            !parsed.outputTarget.endsWith(".kt", ignoreCase = true) &&
            (parsed.recursive || parsed.splitModuleRoots || filteredDiscovered.size > 1)
    val shouldScanCrateMetadata =
        parsed.rawAbiMode || parsed.splitModuleRoots || parsed.finalStructs || parsed.sourceRoot != null
    var cachedMetadataKey: String? = null
    var cachedMetadata = CrateRawAbiMetadata()
    val emittedNapiModules = linkedSetOf<String>()
    val batchFailures = mutableListOf<String>()

    fun translateDiscoveredEntry(entry: DiscoveredRustInput) {
        val metadataRoot = entry.discoveryRoot ?: parsed.sourceRoot
        val metaKey = "${metadataRoot ?: entry.inputPath}|${parsed.rawAbiMode}|${parsed.splitModuleRoots}|$effectiveCfgProfile"
        val crateMetadata =
            if (shouldScanCrateMetadata) {
                if (metaKey == cachedMetadataKey) {
                    cachedMetadata
                } else {
                    val prevCfg = TranslationSession.cfgTargetProfile
                    TranslationSession.cfgTargetProfile = effectiveCfgProfile
                    val resolved =
                        try {
                            resolveCrateRawAbiMetadata(
                                entry.inputPath,
                                metadataRoot,
                                parsed.rawAbiMode,
                                parsed.splitModuleRoots,
                            )
                        } finally {
                            TranslationSession.cfgTargetProfile = prevCfg
                        }
                    if (metadataRoot != null) {
                        cachedMetadataKey = metaKey
                        cachedMetadata = resolved
                    }
                    resolved
                }
            } else {
                CrateRawAbiMetadata()
            }
        val sharedInlineFiles = crateMetadata.sharedInlineModuleRelFiles
        val rustFileName = entry.inputPath.replace('\\', '/').substringAfterLast('/')
        val isModRoot = isRustModuleRootFileName(rustFileName)
        val modRootOutDir =
            if (isModRoot && parsed.splitModuleRoots) {
                computeModuleRootOutputDirectory(entry, parsed, sharedInlineFiles)
            } else {
                null
            }
        val outputPath =
            if (isModRoot && parsed.splitModuleRoots) {
                modRootOutDir?.let { if (it.isEmpty()) "ModuleRoot.kt" else "$it/ModuleRoot.kt" }
            } else {
                computeBatchOutputPath(entry, parsed, sharedInlineFiles)
            }
        if (!isModRoot && outputPath != null && parsed.skipExisting && !parsed.dryRun && exists(outputPath)) {
            if (!parsed.quiet) {
                report("skip existing $outputPath")
            }
            return
        }

        val source = read(entry.inputPath)
        val inferredHeader =
            inferFileHeaderContextWithResolvers(
                inputPath = entry.inputPath,
                outputPath = outputPath,
                rustKotlinProperties = explicitRustKotlinProps,
                packageProperties = explicitPkgProps,
                sourceRoot = parsed.sourceRoot,
                fallbackSourceRoot = entry.discoveryRoot,
                portLintDirective = if (isModRoot) "none" else parsed.portLintDirective,
                resolveRustKotlinProperties = resolveRustKotlinProperties,
                resolvePackageProperties = resolvePackageProperties,
                resolveCrateMacroSources = resolveCrateMacroSources,
                resolveCrateFlatFileModules = resolveCrateFlatFileModules,
            )

        val finalGroup = parsed.groupOverride ?: inferredHeader.projectGroup
        val finalNamespace =
            parsed.namespaceOverride
                ?: parsed.packageOverride
                    ?.trim()
                    ?.removeSuffix(".")
                    ?.takeIf { it.isNotEmpty() }
                ?: inferredHeader.projectNamespace
        if (resolvedProjectNamespaceForScaffold == null && finalNamespace != null) {
            resolvedProjectNamespaceForScaffold = finalNamespace
        }
        val finalCrateNamespaces =
            if (parsed.crateNamespaces.isEmpty()) {
                inferredHeader.crateNamespaces
            } else {
                inferredHeader.crateNamespaces + parsed.crateNamespaces
            }
        val effectiveSegs = computeEffectiveRelativePackageSegments(entry, parsed, sharedInlineFiles)
        val finalPackage =
            when {
                parsed.packageOverride != null -> {
                    val basePkg = parsed.packageOverride.trim().removeSuffix(".")
                    val subPkg = effectiveSegs.joinToString(".")
                    if (subPkg.isEmpty()) basePkg else "$basePkg.$subPkg"
                }
                parsed.namespaceOverride != null ->
                    outputPath?.let { inferKotlinPackageName(it) }
                        ?: run {
                            val basePkg = parsed.namespaceOverride.trim().removeSuffix(".")
                            val subPkg = effectiveSegs.joinToString(".")
                            if (subPkg.isEmpty()) basePkg else "$basePkg.$subPkg"
                        }
                inferredHeader.packageName != null -> {
                    if (parsed.splitModuleRoots &&
                        effectiveSegs.isNotEmpty() &&
                        finalNamespace != null &&
                        inferredHeader.packageName == finalNamespace
                    ) {
                        val basePkg = finalNamespace.trim().removeSuffix(".")
                        "$basePkg.${effectiveSegs.joinToString(".")}"
                    } else {
                        inferredHeader.packageName
                    }
                }
                finalNamespace != null -> {
                    val basePkg = finalNamespace.trim().removeSuffix(".")
                    val subPkg = effectiveSegs.joinToString(".")
                    if (subPkg.isEmpty()) basePkg else "$basePkg.$subPkg"
                }
                else -> null
            }

        val effectiveFfiMeta =
            if (shouldScanCrateMetadata) {
                crateMetadata
            } else if (metadataRoot != null && ("extern " in source || "c!" in source || "cpp!" in source)) {
                if (metaKey == cachedMetadataKey) {
                    cachedMetadata
                } else {
                    val resolved =
                        resolveCrateRawAbiMetadata(
                            entry.inputPath,
                            metadataRoot,
                            parsed.rawAbiMode,
                            parsed.splitModuleRoots,
                        )
                    cachedMetadataKey = metaKey
                    cachedMetadata = resolved
                    resolved
                }
            } else {
                null
            }

        val externalCrateMeta =
            discoverExternalCrateAbiFromDisk(
                anchorPath = metadataRoot ?: entry.inputPath,
                crateNamespaces = finalCrateNamespaces,
            )

        val headerContext =
            FileHeaderContext(
                portLintSourcePath = if (isModRoot) null else inferredHeader.portLintSourcePath,
                packageName = finalPackage,
                projectGroup = finalGroup,
                projectNamespace = finalNamespace,
                crateNamespaces = finalCrateNamespaces,
                projectFfiName = inferredHeader.projectFfiName,
                portLintDirective = if (isModRoot) "none" else parsed.portLintDirective,
                crateMacroSources = inferredHeader.crateMacroSources,
                crateFlatFileModules = inferredHeader.crateFlatFileModules,
                rawAbiMode = parsed.rawAbiMode,
                finalClasses = parsed.finalStructs,
                selfCrateAliases = crateMetadata.selfCrateAliases,
                includedSubdirModuleMap = crateMetadata.includedSubdirModuleMap,
                rootSelfDirModuleAliases = crateMetadata.rootSelfDirModuleAliases,
                rootExternalModuleReexports = crateMetadata.rootExternalModuleReexports,
                crateTypeAliasDefaults = crateMetadata.typeAliasDefaults,
                crateOptionFnTypes = crateMetadata.optionFnTypes,
                crateNumericTypeKinds = effectiveFfiMeta?.numericTypeKinds.orEmpty(),
                cratePointerTypeAliases = effectiveFfiMeta?.pointerTypeAliases.orEmpty(),
                crateStructFieldTypes = crateMetadata.structFieldTypes,
                crateFnReturnTypes = crateMetadata.fnReturnTypes,
                crateSymbolSubpackages = crateMetadata.symbolSubpackages,
                crateSubpackageRawLeafNames = crateMetadata.subpackageRawLeafNames,
                crateValueParamPackTemplates = crateMetadata.valueParamPackTemplates,
                crateValueReturnUnpackTemplates = crateMetadata.valueReturnUnpackTemplates,
                crateFnPtrInvokeTemplates = crateMetadata.fnPtrInvokeTemplates,
                crateFnParamNumericKinds = crateMetadata.fnParamNumericKinds,
                crateTraitDefaultlessAssocTypes = crateMetadata.traitDefaultlessAssocTypes,
                crateTraitResultMethods = crateMetadata.traitResultMethods,
                crateSelfParameterizedTraits = crateMetadata.selfParameterizedTraits,
                crateTraitSupertraits = crateMetadata.traitSupertraits,
                crateTraitAstRegistryKey = crateMetadata.traitAstRegistryKey,
                crateFnConstParamNames = crateMetadata.fnConstParamNames,
                crateEnumVariantTypeArity = crateMetadata.enumVariantTypeArity,
                scalarChar = parsed.scalarChar,
                externalCrateSymbolPackages = externalCrateMeta.symbolPackages,
                externalCrateTwoFieldResultFns = externalCrateMeta.twoFieldResultFns,
                externalCrateNullableReturnFns = externalCrateMeta.nullableReturnFns,
                externalCrateFnConstParams = externalCrateMeta.fnConstParams,
                externalCrateTupleStructArity = externalCrateMeta.tupleStructArity,
                cfgTargetProfile =
                    if (parsed.portLintDirective != "tests") {
                        parsed.cfgTargetProfile.copy(
                            enabledCfgFlags = parsed.cfgTargetProfile.enabledCfgFlags - "test",
                            disabledCfgFlags = parsed.cfgTargetProfile.disabledCfgFlags + "test",
                        )
                    } else {
                        parsed.cfgTargetProfile
                    },
                isBatchCrateOutput = isBatchOutput,
                currentRelativeSourceDir = entry.relativeDir.ifEmpty { null },
                testClassName =
                    if (parsed.portLintDirective == "tests") {
                        if (isModRoot) {
                            computeModuleRootPascalName(entry, finalNamespace, isTestRoot = true)
                        } else {
                            val stem =
                                entry.inputPath
                                    .replace('\\', '/')
                                    .substringAfterLast('/')
                                    .substringBeforeLast('.')
                            KotlinNamingPolicy.Default.toTypeIdentifier(stem, allowRawAbi = false) + "Tests"
                        }
                    } else {
                        null
                    },
            )

        fun emitInlineTestsIfRequested() {
            if (parsed.testOutputTarget == null || parsed.portLintDirective == "tests") return
            val testRoot = parsed.testOutputTarget.replace('\\', '/').trimEnd('/')
            val normalizedInput = entry.inputPath.replace('\\', '/')
            val testStem = normalizedInput.substringAfterLast('/').substringBeforeLast('.')
            val baseSuiteName =
                if (isModRoot) {
                    val modBase = computeModuleRootPascalName(entry, finalNamespace, isTestRoot = false)
                    "${modBase}InlineTests"
                } else {
                    KotlinNamingPolicy.Default.toTypeIdentifier(testStem, allowRawAbi = false) + "InlineTests"
                }
            val inlineHeader =
                if (isModRoot) {
                    headerContext.copy(
                        portLintDirective = "none",
                        portLintSourcePath = null,
                        testClassName = baseSuiteName,
                    )
                } else {
                    headerContext.copy(testClassName = baseSuiteName)
                }
            val inlineTests = renderInlineRustTests(source, inlineHeader) ?: return
            require(!parsed.toStdout) { "inline Rust tests require file output and --test-output <dir>" }
            val testName = "$baseSuiteName.kt"
            val testRelDir =
                if (testRoot.contains("/kotlin/") || parsed.splitModuleRoots) {
                    effectiveSegs.joinToString("/")
                } else {
                    entry.relativeDir
                }
            val testPath = listOf(testRoot, testRelDir, testName).filter { it.isNotEmpty() }.joinToString("/")
            require(testPath != outputPath) { "inline tests and product output must have distinct paths" }
            if (parsed.dryRun) {
                if (!parsed.quiet) report("dry-run: ${entry.inputPath} inline tests -> $testPath")
            } else if (!(parsed.skipExisting && exists(testPath))) {
                if (parsed.forceOverwrite) writeForce(testPath, inlineTests.text) else write(testPath, inlineTests.text)
                for (companion in inlineTests.companionFiles) {
                    if (companion.kind is CompanionInteropKind.NapiCppSource) {
                        emittedNapiModules += companion.ffiModuleName.ifEmpty { "ffi" }
                    }
                    val companionPath = resolveCompanionOutputPath(testPath, companion)
                    if (parsed.skipExisting && exists(companionPath)) continue
                    if (parsed.forceOverwrite) writeForce(companionPath, companion.content) else write(companionPath, companion.content)
                }
                if (!parsed.quiet) report("${entry.inputPath} inline tests -> $testPath; ${inlineTests.summary}")
            }
        }

        if (isModRoot && parsed.splitModuleRoots) {
            val normEntryInput = entry.inputPath.replace('\\', '/')
            val isTestRoot =
                parsed.portLintDirective == "tests" ||
                    "/tests/" in normEntryInput ||
                    normEntryInput.startsWith("tests/")
            val modulePascalName = computeModuleRootPascalName(entry, finalNamespace, isTestRoot = isTestRoot)
            val chunks = translateModuleRootIntoFocusedChunks(source, modulePascalName, headerContext)
            emitInlineTestsIfRequested()
            if (chunks.isEmpty()) {
                return
            }
            for (chunk in chunks) {
                val chunkPath =
                    modRootOutDir?.let { dir ->
                        if (dir.isEmpty()) chunk.fileName else "$dir/${chunk.fileName}"
                    }
                when {
                    parsed.toStdout -> {
                        report("// --- ${chunk.fileName} ---")
                        report(chunk.text.trimEnd('\n'))
                    }
                    parsed.dryRun -> {
                        if (!parsed.quiet) {
                            report("dry-run: ${entry.inputPath} -> $chunkPath; ${chunk.itemCount} items translated")
                        }
                    }
                    else -> {
                        val targetOut = requireNotNull(chunkPath)
                        if (parsed.skipExisting && exists(targetOut)) {
                            if (!parsed.quiet) {
                                report("skip existing $targetOut")
                            }
                            continue
                        }
                        if (parsed.forceOverwrite) {
                            writeForce(targetOut, chunk.text)
                        } else {
                            write(targetOut, chunk.text)
                        }
                        for (companion in chunk.companionFiles) {
                            if (companion.kind is CompanionInteropKind.NapiCppSource) {
                                emittedNapiModules += companion.ffiModuleName.ifEmpty { "ffi" }
                            }
                            val companionOutPath = resolveCompanionOutputPath(targetOut, companion)
                            if (parsed.skipExisting && exists(companionOutPath)) continue
                            if (parsed.forceOverwrite) {
                                writeForce(companionOutPath, companion.content)
                            } else {
                                write(companionOutPath, companion.content)
                            }
                        }
                        if (!parsed.quiet) {
                            report("${entry.inputPath} -> $targetOut; ${chunk.itemCount} items translated")
                        }
                    }
                }
            }
            return
        }

        val result = renderSingleKotlinFile(source, headerContext)
        emitInlineTestsIfRequested()
        if (isBatchOutput &&
            !parsed.toStdout &&
            !hasEmittedKotlinDeclarations(result.text) &&
            result.companionFiles.isEmpty()
        ) {
            return
        }
        when {
            parsed.toStdout -> {
                report(result.text.trimEnd('\n'))
            }
            parsed.dryRun -> {
                if (!parsed.quiet) {
                    report("dry-run: ${entry.inputPath} -> $outputPath; ${result.summary}")
                }
            }
            else -> {
                val targetOut = requireNotNull(outputPath)
                if (parsed.forceOverwrite) {
                    writeForce(targetOut, result.text)
                } else {
                    write(targetOut, result.text)
                }
                for (companion in result.companionFiles) {
                    if (companion.kind is CompanionInteropKind.NapiCppSource) {
                        emittedNapiModules += companion.ffiModuleName.ifEmpty { "ffi" }
                    }
                    val companionOutPath = resolveCompanionOutputPath(targetOut, companion)
                    if (parsed.skipExisting && exists(companionOutPath)) continue
                    if (parsed.forceOverwrite) {
                        writeForce(companionOutPath, companion.content)
                    } else {
                        write(companionOutPath, companion.content)
                    }
                }
                if (!parsed.quiet) {
                    report("${entry.inputPath} -> $targetOut; ${result.summary}")
                }
            }
        }
        result.diagnostics.forEach(report)
    }

    for (entry in filteredDiscovered) {
        if (isBatchOutput && filteredDiscovered.size > 1) {
            try {
                translateDiscoveredEntry(entry)
            } catch (e: Exception) {
                val msg = e.message ?: e::class.simpleName ?: "translation error"
                batchFailures += "${entry.inputPath}: $msg"
                report("FAIL ${entry.inputPath}: $msg")
            }
        } else {
            translateDiscoveredEntry(entry)
        }
    }

    if (isBatchOutput && TranslationSession.emittedInt128Support && !parsed.toStdout) {
        val normOutRoot = requireNotNull(parsed.outputTarget).replace('\\', '/').trimEnd('/')
        val commonMarker = "src/common/commonMain/kotlin/"
        val markerIdx =
            when {
                normOutRoot.startsWith(commonMarker) -> 0
                normOutRoot.contains("/$commonMarker") -> normOutRoot.indexOf("/$commonMarker") + 1
                else -> -1
            }
        val supportPkg =
            resolvedProjectNamespaceForScaffold
                ?: inferKotlinPackageName("$normOutRoot/Int128Support.kt")
                ?: parsed.packageOverride
        val supportOutPath =
            if (markerIdx >= 0 && !supportPkg.isNullOrBlank()) {
                val projRootPrefix = normOutRoot.substring(0, markerIdx)
                "$projRootPrefix$commonMarker${supportPkg.replace('.', '/')}/Int128Support.kt"
            } else {
                "$normOutRoot/Int128Support.kt"
            }
        val supportContent = renderSynthesizedInt128SupportFile(supportPkg)
        if (parsed.dryRun) {
            if (!parsed.quiet) {
                report("dry-run: int128-support -> $supportOutPath")
            }
        } else if (!(parsed.skipExisting && exists(supportOutPath))) {
            if (parsed.forceOverwrite) {
                writeForce(supportOutPath, supportContent)
            } else {
                write(supportOutPath, supportContent)
            }
            if (!parsed.quiet) {
                report("int128-support -> $supportOutPath")
            }
        }
    }

    if (isBatchOutput && !parsed.toStdout) {
        val normOutRoot = requireNotNull(parsed.outputTarget).replace('\\', '/').trimEnd('/')
        val commonMarker = "src/common/commonMain/kotlin/"
        val markerIdx =
            when {
                normOutRoot.startsWith(commonMarker) -> 0
                normOutRoot.contains("/$commonMarker") -> normOutRoot.indexOf("/$commonMarker") + 1
                else -> -1
            }
        val inferredCorePkg = inferKotlinPackageName("$normOutRoot/CoreMarker.kt") ?: parsed.packageOverride
        if (markerIdx >= 0 && !inferredCorePkg.isNullOrBlank() && inferredCorePkg.endsWith(".core")) {
            val projRootPrefix = normOutRoot.substring(0, markerIdx)
            val projectNs = resolvedProjectNamespaceForScaffold ?: inferredCorePkg.substringBeforeLast('.')
            val typeLayoutPath = "$projRootPrefix$commonMarker${inferredCorePkg.replace('.', '/')}/mem/TypeLayout.kt"
            val typeLayoutContent = CoreIntrinsicFfiArtifacts.renderTypeLayoutSupportKotlin(inferredCorePkg)
            val intrinsicsAnchor = "$projRootPrefix$commonMarker${inferredCorePkg.replace('.', '/')}/intrinsics/KmCoreIntrinsics.kt"
            val kmCoreCompanions =
                CoreIntrinsicFfiArtifacts.emitAllKmCoreCompanionFiles(
                    coreNamespace = inferredCorePkg,
                    ffiModuleName = "kmcore",
                    projectNamespace = projectNs,
                )
            val constPtrPath = "$projRootPrefix$commonMarker${inferredCorePkg.replace('.', '/')}/ptr/ConstPtr.kt"
            val constPtrContent = "package $inferredCorePkg.ptr\n\n${CoreIntrinsicFfiArtifacts.renderConstPtrClassHeader()}\n"
            val mutPtrPath = "$projRootPrefix$commonMarker${inferredCorePkg.replace('.', '/')}/ptr/MutPtr.kt"
            val mutPtrContent = "package $inferredCorePkg.ptr\n\n${CoreIntrinsicFfiArtifacts.renderMutPtrClassHeader()}\n"
            val int128SupportPath = "$projRootPrefix$commonMarker${inferredCorePkg.replace('.', '/')}/Int128Support.kt"
            val int128SupportContent =
                renderSynthesizedInt128SupportFile(
                    inferredCorePkg,
                    coreIntrinsicsPackage = "$inferredCorePkg.intrinsics",
                )
            val rustAnySupportPath = "$projRootPrefix$commonMarker${inferredCorePkg.replace('.', '/')}/RustAnySupport.kt"
            val rustAnySupportContent = renderSynthesizedAnySupportFile(inferredCorePkg)
            val floatSupportPath = "$projRootPrefix$commonMarker${inferredCorePkg.replace('.', '/')}/FloatSupport.kt"
            val floatSupportContent = renderSynthesizedFloatSupportFile(inferredCorePkg)
            val supportFiles =
                listOf(
                    int128SupportPath to int128SupportContent,
                    rustAnySupportPath to rustAnySupportContent,
                    floatSupportPath to floatSupportContent,
                )
            if (parsed.dryRun) {
                if (!parsed.quiet) {
                    report("dry-run: core-type-layout -> $typeLayoutPath")
                    if (parsed.splitModuleRoots) {
                        report("dry-run: core-ptr -> $constPtrPath")
                        report("dry-run: core-ptr -> $mutPtrPath")
                    }
                    for ((supPath, _) in supportFiles) {
                        report("dry-run: core-support -> $supPath")
                    }
                    for (comp in kmCoreCompanions) {
                        report("dry-run: core-intrinsic-companion -> ${resolveCompanionOutputPath(intrinsicsAnchor, comp)}")
                    }
                }
            } else {
                if (!(parsed.skipExisting && exists(typeLayoutPath))) {
                    if (parsed.forceOverwrite) {
                        writeForce(typeLayoutPath, typeLayoutContent)
                    } else {
                        write(typeLayoutPath, typeLayoutContent)
                    }
                }
                for ((supPath, supContent) in supportFiles) {
                    if (parsed.skipExisting && exists(supPath)) continue
                    if (parsed.forceOverwrite) {
                        writeForce(supPath, supContent)
                    } else {
                        write(supPath, supContent)
                    }
                }
                if (parsed.splitModuleRoots) {
                    for ((ptrPath, ptrContent) in listOf(constPtrPath to constPtrContent, mutPtrPath to mutPtrContent)) {
                        if (parsed.skipExisting && exists(ptrPath)) continue
                        if (parsed.forceOverwrite) {
                            writeForce(ptrPath, ptrContent)
                        } else {
                            write(ptrPath, ptrContent)
                        }
                    }
                }
                for (comp in kmCoreCompanions) {
                    val compOut = resolveCompanionOutputPath(intrinsicsAnchor, comp)
                    if (parsed.skipExisting && exists(compOut)) continue
                    if (parsed.forceOverwrite) {
                        writeForce(compOut, comp.content)
                    } else {
                        write(compOut, comp.content)
                    }
                }
                if (!parsed.quiet) {
                    val ptrCount = if (parsed.splitModuleRoots) 2 else 0
                    report("core-intrinsic-artifacts -> $projRootPrefix (${kmCoreCompanions.size + 1 + supportFiles.size + ptrCount} files)")
                }
            }
        } else if (markerIdx >= 0 && emittedNapiModules.isNotEmpty() && !parsed.dryRun) {
            val projRootPrefix = normOutRoot.substring(0, markerIdx)
            val inlineHeaderPath = "${projRootPrefix}src/native/interop/cinterop/km_core_intrinsics_inline.h"
            if (!(parsed.skipExisting && exists(inlineHeaderPath))) {
                val inlineHeaderContent = CoreIntrinsicFfiArtifacts.renderKmCoreInlineHeader()
                if (parsed.forceOverwrite) {
                    writeForce(inlineHeaderPath, inlineHeaderContent)
                } else {
                    write(inlineHeaderPath, inlineHeaderContent)
                }
            }
            for (ffiMod in emittedNapiModules) {
                if (ffiMod == "kmcore" && exists("${projRootPrefix}src/js/node-kmcore/src/kmcore_bindings.cpp")) {
                    continue
                }
                val stem = "${ffiMod}_kmcore_intrinsics"
                val napiBridgePath = "${projRootPrefix}src/js/node-$ffiMod/src/${stem}_bindings.cpp"
                if (parsed.skipExisting && exists(napiBridgePath)) continue
                val napiBridgeContent = CoreIntrinsicFfiArtifacts.renderKmCoreNapiCpp(stem)
                if (parsed.forceOverwrite) {
                    writeForce(napiBridgePath, napiBridgeContent)
                } else {
                    write(napiBridgePath, napiBridgeContent)
                }
            }
        }
    }

    if (isBatchOutput && TranslationSession.emittedRustAnySupport && !parsed.toStdout) {
        val normOutRoot = requireNotNull(parsed.outputTarget).replace('\\', '/').trimEnd('/')
        val commonMarker = "src/common/commonMain/kotlin/"
        val markerIdx =
            when {
                normOutRoot.startsWith(commonMarker) -> 0
                normOutRoot.contains("/$commonMarker") -> normOutRoot.indexOf("/$commonMarker") + 1
                else -> -1
            }
        val supportPkg =
            resolvedProjectNamespaceForScaffold
                ?: inferKotlinPackageName("$normOutRoot/RustAnySupport.kt")
                ?: parsed.packageOverride
        val supportOutPath =
            if (markerIdx >= 0 && !supportPkg.isNullOrBlank()) {
                val projRootPrefix = normOutRoot.substring(0, markerIdx)
                "$projRootPrefix$commonMarker${supportPkg.replace('.', '/')}/RustAnySupport.kt"
            } else {
                "$normOutRoot/RustAnySupport.kt"
            }
        val supportContent = renderSynthesizedAnySupportFile(supportPkg)
        if (parsed.dryRun) {
            if (!parsed.quiet) {
                report("dry-run: rust-any-support -> $supportOutPath")
            }
        } else if (!(parsed.skipExisting && exists(supportOutPath))) {
            if (parsed.forceOverwrite) {
                writeForce(supportOutPath, supportContent)
            } else {
                write(supportOutPath, supportContent)
            }
            if (!parsed.quiet) {
                report("rust-any-support -> $supportOutPath")
            }
        }
    }
    if (batchFailures.isNotEmpty()) {
        throw IllegalStateException(
            "Batch translation failed on ${batchFailures.size} file(s):\n${batchFailures.joinToString("\n")}",
        )
    }
}

private fun resolveCompanionOutputPath(
    targetKotlinPath: String,
    companion: CompanionInteropFile,
): String {
    val normalizedTarget = targetKotlinPath.replace('\\', '/')
    val commonMarker = "src/common/commonMain/kotlin/"
    val markerIdx =
        when {
            normalizedTarget.startsWith(commonMarker) -> 0
            normalizedTarget.contains("/$commonMarker") -> normalizedTarget.indexOf("/$commonMarker") + 1
            else -> -1
        }
    if (markerIdx >= 0) {
        val projectRootPrefix = normalizedTarget.substring(0, markerIdx)
        val afterKotlin = normalizedTarget.substring(markerIdx + commonMarker.length)
        val pkgDir = afterKotlin.substringBeforeLast('/', missingDelimiterValue = "")
        val relKtPath = if (pkgDir.isEmpty()) companion.relativePath else "$pkgDir/${companion.relativePath}"
        return when (companion.kind) {
            is CompanionInteropKind.CommonKotlinSupport -> "${projectRootPrefix}src/common/commonMain/kotlin/$relKtPath"
            is CompanionInteropKind.CInteropDef,
            is CompanionInteropKind.CHeader,
            is CompanionInteropKind.CSource,
            is CompanionInteropKind.CppSource,
            is CompanionInteropKind.AsmSource,
            -> "${projectRootPrefix}src/native/interop/cinterop/${companion.relativePath}"
            is CompanionInteropKind.NativeKotlinActuals -> "${projectRootPrefix}src/native/nativeMain/kotlin/$relKtPath"
            is CompanionInteropKind.JvmKotlinActuals -> "${projectRootPrefix}src/jvm/jvmMain/kotlin/$relKtPath"
            is CompanionInteropKind.AndroidKotlinActuals -> "${projectRootPrefix}src/android/jvm/androidMain/kotlin/$relKtPath"
            is CompanionInteropKind.JniCSource -> "${projectRootPrefix}src/jvm/jvmMain/c/${companion.relativePath}"
            is CompanionInteropKind.JsKotlinActuals -> "${projectRootPrefix}src/js/jsMain/kotlin/$relKtPath"
            is CompanionInteropKind.NapiCppSource -> {
                val ffiMod = companion.ffiModuleName.ifEmpty { "ffi" }
                "${projectRootPrefix}src/js/node-$ffiMod/src/${companion.relativePath}"
            }
            is CompanionInteropKind.NapiDistFile -> {
                val ffiMod = companion.ffiModuleName.ifEmpty { "ffi" }
                "${projectRootPrefix}src/js/node-$ffiMod/dist/${companion.relativePath}"
            }
            is CompanionInteropKind.WasmJsKotlinActuals -> "${projectRootPrefix}src/js/wasm/wasmJsMain/kotlin/$relKtPath"
            is CompanionInteropKind.WasmWasiKotlinActuals -> "${projectRootPrefix}src/js/wasm/wasmWasiMain/kotlin/$relKtPath"
        }
    }
    val parentDir = normalizedTarget.substringBeforeLast('/', missingDelimiterValue = "")
    return if (parentDir.isEmpty()) companion.relativePath else "$parentDir/${companion.relativePath}"
}

private fun normalizeCliCfgKey(rawKey: String): String =
    rawKey
        .split("::")
        .map { it.trim().removePrefix("r#") }
        .filter { it.isNotEmpty() }
        .joinToString("::")

private fun unquoteCliCfgValue(rawVal: String): String {
    val trimmed = rawVal.trim()
    if (trimmed.startsWith('r')) {
        val quoteStart = trimmed.indexOf('"')
        val quoteEnd = trimmed.lastIndexOf('"')
        if (quoteStart > 0 && quoteEnd > quoteStart) {
            val prefixHashes = quoteStart - 1
            val suffixHashes = trimmed.length - 1 - quoteEnd
            val validPrefix = (1 until quoteStart).all { trimmed[it] == '#' }
            val validSuffix = (quoteEnd + 1 until trimmed.length).all { trimmed[it] == '#' }
            if (prefixHashes == suffixHashes && validPrefix && validSuffix) {
                return trimmed.substring(quoteStart + 1, quoteEnd)
            }
        }
    }
    if (trimmed.length >= 2 && trimmed.startsWith('"') && trimmed.endsWith('"')) {
        return trimmed
            .substring(1, trimmed.length - 1)
            .replace("\\\"", "\"")
            .replace("\\\\", "\\")
    }
    if (trimmed.length >= 2 && trimmed.startsWith('\'') && trimmed.endsWith('\'')) {
        return trimmed.substring(1, trimmed.length - 1)
    }
    return trimmed
}
