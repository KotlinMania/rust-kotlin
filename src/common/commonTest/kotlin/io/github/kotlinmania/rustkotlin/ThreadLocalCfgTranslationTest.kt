package io.github.kotlinmania.rustkotlin

import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertFalse

class ThreadLocalCfgTranslationTest {
    @Test
    fun originalNamespacedCfgIfRetainsStableThreadLifecycleCode() {
        // Original macro invocation from thread_local 1.1.9/src/thread_id.rs.
        val source =
            """
            cfg_if::cfg_if! {
                if #[cfg(feature = "nightly")] {
                    // This is split into 2 thread-local variables so that we can check whether the
                    // thread is initialized without having to register a thread-local destructor.
                    //
                    // This makes the fast path smaller.
                    #[thread_local]
                    static mut THREAD: Option<Thread> = None;
                    thread_local! { static THREAD_GUARD: ThreadGuard = const { ThreadGuard { id: Cell::new(0) } }; }
            
                    // Guard to ensure the thread ID is released on thread exit.
                    struct ThreadGuard {
                        // We keep a copy of the thread ID in the ThreadGuard: we can't
                        // reliably access THREAD in our Drop impl due to the unpredictable
                        // order of TLS destructors.
                        id: Cell<usize>,
                    }
            
                    impl Drop for ThreadGuard {
                        fn drop(&mut self) {
                            // Release the thread ID. Any further accesses to the thread ID
                            // will go through get_slow which will either panic or
                            // initialize a new ThreadGuard.
                            unsafe {
                                THREAD = None;
                            }
                            THREAD_ID_MANAGER.lock().unwrap().free(self.id.get());
                        }
                    }
            
                    /// Returns a thread ID for the current thread, allocating one if needed.
                    #[inline]
                    pub(crate) fn get() -> Thread {
                        if let Some(thread) = unsafe { THREAD } {
                            thread
                        } else {
                            get_slow()
                        }
                    }
            
                    /// Out-of-line slow path for allocating a thread ID.
                    #[cold]
                     fn get_slow() -> Thread {
                        let new = Thread::new(THREAD_ID_MANAGER.lock().unwrap().alloc());
                        unsafe {
                            THREAD = Some(new);
                        }
                        THREAD_GUARD.with(|guard| guard.id.set(new.id));
                        new
                    }
                } else {
                    // This is split into 2 thread-local variables so that we can check whether the
                    // thread is initialized without having to register a thread-local destructor.
                    //
                    // This makes the fast path smaller.
                    thread_local! { static THREAD: Cell<Option<Thread>> = const { Cell::new(None) }; }
                    thread_local! { static THREAD_GUARD: ThreadGuard = const { ThreadGuard { id: Cell::new(0) } }; }
            
                    // Guard to ensure the thread ID is released on thread exit.
                    struct ThreadGuard {
                        // We keep a copy of the thread ID in the ThreadGuard: we can't
                        // reliably access THREAD in our Drop impl due to the unpredictable
                        // order of TLS destructors.
                        id: Cell<usize>,
                    }
            
                    impl Drop for ThreadGuard {
                        fn drop(&mut self) {
                            // Release the thread ID. Any further accesses to the thread ID
                            // will go through get_slow which will either panic or
                            // initialize a new ThreadGuard.
                            let _ = THREAD.try_with(|thread| thread.set(None));
                            THREAD_ID_MANAGER.lock().unwrap().free(self.id.get());
                        }
                    }
            
                    /// Returns a thread ID for the current thread, allocating one if needed.
                    #[inline]
                    pub(crate) fn get() -> Thread {
                        THREAD.with(|thread| {
                            if let Some(thread) = thread.get() {
                                thread
                            } else {
                                get_slow(thread)
                            }
                        })
                    }
            
                    /// Out-of-line slow path for allocating a thread ID.
                    #[cold]
                    fn get_slow(thread: &Cell<Option<Thread>>) -> Thread {
                        let new = Thread::new(THREAD_ID_MANAGER.lock().unwrap().alloc());
                        thread.set(Some(new));
                        THREAD_GUARD.with(|guard| guard.id.set(new.id));
                        new
                    }
                }
            }
            """.trimIndent()
        val header =
            FileHeaderContext(
                cfgTargetProfile =
                    CfgTargetProfile(
                        enabledFeatures = emptySet(),
                        unknownPolicy = UnknownCfgPolicy.CLOSED_WORLD_FALSE,
                    ),
            )
        val output = translateRustSourceToKotlin(recognizeRust(source), header)
        assertContains(output, "class ThreadGuard")
        assertContains(output, "fun get()")
        assertContains(output, "fun getSlow(thread:")
        assertFalse(output.contains("fun getSlow():"))
    }
}
