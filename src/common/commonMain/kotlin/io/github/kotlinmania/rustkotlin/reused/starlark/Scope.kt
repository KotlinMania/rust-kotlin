// Reused from starlark-kotlin; modification: package relocation and Rust AST scope binding payloads.
// port-lint: source src/eval/compiler/scope.rs
package io.github.kotlinmania.rustkotlin.reused.starlark

import io.github.kotlinmania.procmacro2.Span
import io.github.kotlinmania.syn.Visibility

/** Not captured local slot id. */
internal data class LocalSlotId(
    val index: UInt,
) {
    fun toCapturedOrNot(): LocalSlotIdCapturedOrNot = LocalSlotIdCapturedOrNot(index)
}

/** Captured local slot id. */
internal data class LocalCapturedSlotId(
    val index: UInt,
)

/** Local slot id used during AST scope analysis. */
internal data class LocalSlotIdCapturedOrNot(
    val index: UInt,
)

/** Module-level slot id. */
internal data class ModuleSlotId(
    val index: UInt,
)

/** Copy local variable slot to nested function. */
internal data class CopySlotFromParent(
    /** Slot in the outer function. */
    val parent: LocalSlotIdCapturedOrNot,
    /** Slot in the nested function. */
    val child: LocalSlotIdCapturedOrNot,
)

internal class UnscopeBinding(
    /**
     * Variable mappings in local scope are overwritten by shadowed/scoped variables.
     * When we pop the block/comprehension scope, we restore the mapping from this value.
     */
    val undo: Pair<LocalSlotIdCapturedOrNot, BindingId>?,
)

internal class Unscope(
    val bindings: MutableMap<String, UnscopeBinding> = linkedMapOf(),
)

internal class ScopeNames(
    /** `Some` when scope is initialized. For module scope, the value is zero. */
    var paramCount: Int? = null,
    /**
     * Slots this scope uses, including for parameters and `parent`.
     * Indexed by LocalSlotId, values are variable names.
     */
    val used: MutableList<String> = mutableListOf(),
    /** The names that are currently visible in this scope. */
    val mp: MutableMap<String, Pair<LocalSlotIdCapturedOrNot, BindingId>> = linkedMapOf(),
    /** Slots to copy from the parent. */
    val parent: MutableList<CopySlotFromParent> = mutableListOf(),
) {
    fun setParamCount(paramCount: Int) {
        check(this.paramCount == null)
        this.paramCount = paramCount
    }

    fun paramCount(): Int = paramCount ?: error("param_count must be set during analysis")

    fun copyParent(
        parentSlot: LocalSlotIdCapturedOrNot,
        bindingId: BindingId,
        name: String,
    ): LocalSlotIdCapturedOrNot {
        check(getName(name) == null)
        val res = addName(name, bindingId)
        parent.add(CopySlotFromParent(parent = parentSlot, child = res))
        return res
    }

    fun nextSlot(name: String): LocalSlotIdCapturedOrNot {
        val res = LocalSlotIdCapturedOrNot(used.size.toUInt())
        used.add(name)
        return res
    }

    fun addName(
        name: String,
        bindingId: BindingId,
    ): LocalSlotIdCapturedOrNot {
        val slot = nextSlot(name)
        val old = mp.put(name, Pair(slot, bindingId))
        check(old == null)
        return slot
    }

    fun addScoped(
        name: String,
        bindingId: BindingId,
        unscope: Unscope,
    ): LocalSlotIdCapturedOrNot {
        val slot = nextSlot(name)
        val existing = mp[name]
        val undo: Pair<LocalSlotIdCapturedOrNot, BindingId>? =
            if (existing != null) {
                mp[name] = Pair(slot, bindingId)
                existing
            } else {
                mp[name] = Pair(slot, bindingId)
                null
            }
        // If `name` was already shadowed earlier in the same `unscope` block frame,
        // keep the original `UnscopeBinding` from when the block started so `unscope()`
        // restores the pre-block binding.
        if (name !in unscope.bindings) {
            unscope.bindings[name] = UnscopeBinding(undo)
        }
        return slot
    }

    fun unscope(unscope: Unscope) {
        for ((name, unscopeBinding) in unscope.bindings) {
            val undo = unscopeBinding.undo
            if (undo == null) {
                mp.remove(name)
            } else {
                mp[name] = undo
            }
        }
    }

    fun getName(name: String): Pair<LocalSlotIdCapturedOrNot, BindingId>? = mp[name]
}

internal sealed class Slot {
    /** Top-level module scope. */
    data class Module(
        val id: ModuleSlotId,
    ) : Slot()

    /** Local scope, always mutable. */
    data class Local(
        val id: LocalSlotIdCapturedOrNot,
    ) : Slot()
}

internal enum class ResolveIdentScope {
    /** Resolving normal identifier. */
    Any,

    /** Resolving identifier in type expression. */
    GlobalForTypeExpression,
}

/** While performing analysis. */
internal enum class InLoop {
    /** Current statement has an enclosing loop in the current scope. */
    Yes,

    /** Current statement has no enclosing loop in the current scope. */
    No,
}

internal enum class AssignCount {
    /** Variable is assigned at most once during the execution of the scope. */
    AtMostOnce,

    /** Variable may be assigned more than once during execution of the scope. */
    Any,
}

/** Was a binding captured by nested def or lambda scopes? */
internal enum class Captured {
    Yes,
    No,
}

internal sealed class BindingSource {
    /** Variable is defined in the source of the module. */
    data class Source(
        val span: Span,
    ) : BindingSource()

    /** Variable came from `Module` / `use` import. */
    data object FromModule : BindingSource()
}

/**
 * Binding defines a place for a variable or imported module/type/value symbol.
 *
 * In Rust code `let x = 1; let x = 2;`, there are two bindings for `x` in the scope,
 * with distinct emitted Kotlin names (`x` and `x1`) so type-changing or immutable re-bindings
 * never collide in Kotlin.
 */
internal class Binding(
    val name: String,
    val kotlinName: String,
    val source: BindingSource,
    var vis: Visibility,
    var assignCount: AssignCount,
    val importedPath: String? = null,
    val isModuleImport: Boolean = false,
    internal var slot: Slot? = null,
    var captured: Captured = Captured.No,
) {
    fun span(): Span =
        when (source) {
            is BindingSource.Source -> source.span
            is BindingSource.FromModule -> Span.callSite()
        }

    fun resolvedSlot(): Slot =
        slot ?: error("slot is not resolved for binding `$name`")

    fun initSlot(newSlot: Slot) {
        check(slot == null) { "slot is already assigned for binding `$name`" }
        slot = newSlot
    }
}

/** Id of a binding within current module. */
internal data class BindingId(
    val id: Int,
) : Comparable<BindingId> {
    override fun compareTo(other: BindingId): Int = id.compareTo(other.id)
}

/** Id of a scope within current module. */
internal data class ScopeId(
    val id: Int,
) {
    companion object {
        fun module(): ScopeId = ScopeId(0)
    }
}

internal sealed class ResolvedIdent {
    data class Slot(
        val slot: io.github.kotlinmania.rustkotlin.reused.starlark.Slot,
        val bindingId: BindingId,
    ) : ResolvedIdent()

    data class Global(
        val name: String,
    ) : ResolvedIdent()
}

/** Storage of objects referenced by AST. */
internal class ModuleScopeData(
    /** Bindings by id. */
    internal val bindings: MutableList<Binding> = mutableListOf(),
    /** Scopes by id. */
    private val scopes: MutableList<ScopeNames> = mutableListOf(),
) {
    fun getBinding(id: BindingId): Binding = bindings[id.id]

    fun mutBinding(id: BindingId): Binding = bindings[id.id]

    fun newBinding(
        name: String,
        kotlinName: String,
        source: BindingSource,
        vis: Visibility,
        assignedCount: AssignCount,
        importedPath: String? = null,
        isModuleImport: Boolean = false,
    ): Pair<BindingId, Binding> {
        val bindingId = BindingId(bindings.size)
        val binding =
            Binding(
                name = name,
                kotlinName = kotlinName,
                source = source,
                vis = vis,
                assignCount = assignedCount,
                importedPath = importedPath,
                isModuleImport = isModuleImport,
            )
        bindings.add(binding)
        return Pair(bindingId, binding)
    }

    fun getScope(id: ScopeId): ScopeNames = scopes[id.id]

    fun mutScope(id: ScopeId): ScopeNames = scopes[id.id]

    fun newScope(): Pair<ScopeId, ScopeNames> {
        val scopeId = ScopeId(scopes.size)
        val scope = ScopeNames()
        scopes.add(scope)
        return Pair(scopeId, scope)
    }
}

/**
 * Tracks active module, function (`def`), and block/comprehension (`Unscope`) scopes
 * during Rust-to-Kotlin AST translation, modeled on Starlark's `ModuleScopeBuilder`.
 */
internal class ModuleScopeBuilder(
    val scopeData: ModuleScopeData = ModuleScopeData(),
    val moduleBindings: MutableMap<String, BindingId> = linkedMapOf(),
    val locals: MutableList<ScopeId> = mutableListOf(),
    val unscopes: MutableList<Unscope> = mutableListOf(),
) {
    private val moduleUnscopes: MutableList<Unscope> = mutableListOf()

    init {
        val (moduleScopeId, _) = scopeData.newScope()
        check(moduleScopeId == ScopeId.module())
        scopeData.mutScope(moduleScopeId).setParamCount(0)
        locals.add(moduleScopeId)
    }

    fun topScopeId(): ScopeId = locals.last()

    fun scopeAtLevel(level: Int): ScopeNames {
        val scopeId = locals[level]
        return scopeData.getScope(scopeId)
    }

    fun scopeAtLevelMut(level: Int): ScopeNames {
        val scopeId = locals[level]
        return scopeData.mutScope(scopeId)
    }

    fun enterDef(): ScopeId {
        val (scopeId, _) = scopeData.newScope()
        check(scopeId != ScopeId.module())
        locals.add(scopeId)
        return scopeId
    }

    fun exitDef(): ScopeNames {
        val scopeId = locals.removeAt(locals.lastIndex)
        return scopeData.mutScope(scopeId)
    }

    fun enterCompr() {
        unscopes.add(Unscope())
    }

    fun exitCompr() {
        scopeData
            .mutScope(topScopeId())
            .unscope(unscopes.removeAt(unscopes.lastIndex))
    }

    fun enterModuleSubscope() {
        moduleUnscopes.add(Unscope())
    }

    fun exitModuleSubscope() {
        scopeData
            .mutScope(ScopeId.module())
            .unscope(moduleUnscopes.removeAt(moduleUnscopes.lastIndex))
    }

    fun bindModuleImport(
        rawName: String,
        kotlinName: String,
        importedPath: String,
        isModuleImport: Boolean,
        vis: Visibility = Visibility.Inherited,
    ): Binding {
        val moduleScope = scopeData.mutScope(ScopeId.module())
        val (bindingId, binding) =
            scopeData.newBinding(
                name = rawName,
                kotlinName = kotlinName,
                source = BindingSource.FromModule,
                vis = vis,
                assignedCount = AssignCount.AtMostOnce,
                importedPath = importedPath,
                isModuleImport = isModuleImport,
            )
        val activeModuleUnscope = moduleUnscopes.lastOrNull()
        val slot =
            if (activeModuleUnscope != null) {
                moduleScope.addScoped(rawName, bindingId, activeModuleUnscope)
            } else {
                val existing = moduleScope.getName(rawName)
                if (existing != null) {
                    val next = moduleScope.nextSlot(rawName)
                    moduleScope.mp[rawName] = Pair(next, bindingId)
                    next
                } else {
                    moduleScope.addName(rawName, bindingId)
                }
            }
        binding.initSlot(Slot.Module(ModuleSlotId(slot.index)))
        moduleBindings[rawName] = bindingId
        return binding
    }

    fun bindLocal(
        rawName: String,
        baseKotlinName: String,
        span: Span = Span.callSite(),
        vis: Visibility = Visibility.Inherited,
        assignCount: AssignCount = AssignCount.AtMostOnce,
    ): Binding {
        val currentScope = scopeData.mutScope(topScopeId())
        val occurrenceCount = (1 until locals.size).sumOf { level -> scopeAtLevel(level).used.count { it == rawName } }
        val emittedKotlinName =
            if (occurrenceCount == 0) {
                baseKotlinName
            } else {
                "${baseKotlinName}$occurrenceCount"
            }
        val (bindingId, binding) =
            scopeData.newBinding(
                name = rawName,
                kotlinName = emittedKotlinName,
                source = BindingSource.Source(span),
                vis = vis,
                assignedCount = assignCount,
            )
        val activeUnscope = unscopes.lastOrNull()
        val slot =
            if (activeUnscope != null) {
                currentScope.addScoped(rawName, bindingId, activeUnscope)
            } else {
                val existing = currentScope.getName(rawName)
                if (existing != null) {
                    val next = currentScope.nextSlot(rawName)
                    currentScope.mp[rawName] = Pair(next, bindingId)
                    next
                } else {
                    currentScope.addName(rawName, bindingId)
                }
            }
        binding.initSlot(Slot.Local(slot))
        return binding
    }

    fun getName(name: String): Pair<Slot, BindingId>? {
        for (i in (1 until locals.size).reversed()) {
            val found = scopeAtLevel(i).getName(name)
            if (found != null) {
                var (v, bindingId) = found
                if (i + 1 != locals.size) {
                    scopeData.mutBinding(bindingId).captured = Captured.Yes
                }
                for (j in (i + 1) until locals.size) {
                    v = scopeAtLevelMut(j).copyParent(v, bindingId, name)
                }
                return Pair(Slot.Local(v), bindingId)
            }
        }
        val moduleFound = scopeData.getScope(ScopeId.module()).getName(name)
        if (moduleFound != null) {
            val (slot, bindingId) = moduleFound
            val binding = scopeData.mutBinding(bindingId)
            if (locals.size > 1) {
                binding.captured = Captured.Yes
            }
            return Pair(Slot.Module(ModuleSlotId(slot.index)), bindingId)
        }
        val bindingId = moduleBindings[name]
        return if (bindingId != null) {
            val binding = scopeData.mutBinding(bindingId)
            if (locals.size > 1) {
                binding.captured = Captured.Yes
            }
            val slot = binding.resolvedSlot()
            Pair(slot, bindingId)
        } else {
            null
        }
    }

    fun resolveLocalKotlinName(rawName: String): String? {
        for (i in (1 until locals.size).reversed()) {
            val found = scopeAtLevel(i).getName(rawName)
            if (found != null) {
                val (_, bindingId) = found
                return scopeData.getBinding(bindingId).kotlinName
            }
        }
        return null
    }

    fun isSmartCastableLocalKotlinName(kotlinName: String): Boolean {
        for (i in (1 until locals.size).reversed()) {
            for ((_, pair) in scopeAtLevel(i).mp) {
                val binding = scopeData.getBinding(pair.second)
                if (binding.kotlinName == kotlinName) {
                    return binding.assignCount == AssignCount.AtMostOnce ||
                        (i == locals.lastIndex && binding.captured == Captured.No)
                }
            }
        }
        return false
    }

    fun resolveModuleBinding(rawName: String): Binding? {
        val found = scopeData.getScope(ScopeId.module()).getName(rawName) ?: return null
        return scopeData.getBinding(found.second)
    }

    fun isImportedModuleInScope(rawName: String): Boolean = resolveModuleBinding(rawName)?.isModuleImport == true
}
