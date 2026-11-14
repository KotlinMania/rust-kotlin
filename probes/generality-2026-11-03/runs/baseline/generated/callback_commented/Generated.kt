package probe.callback_commented

public fun interface FnCallback {
    public operator fun invoke(arg0: Int): Int
}

public fun interface CompletionFn {
    public operator fun invoke(arg0: Int): Int
}

public typealias Completion = CompletionFn?

public data class Slot(public var callback: Completion = null)

public fun increment(value: Int): Int {
    return value + 1
}

public fun apply(callback: Completion, value: Int): Int {
    return when (callback) {
        null -> value
        else -> {
            val function = callback
            function(value)
        }
    }
}

public fun probe(): Boolean {
    val slot = Slot(callback = null)
    return slot.callback == null && apply(null, 9) == 9 && apply(::increment, 9) == 10
}
