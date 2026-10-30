package io.github.lujinxin.nextep.systemui

import android.view.View
import android.view.WindowManager
import io.github.lujinxin.nextep.logging.NeXtepLog
import java.util.Collections
import java.util.WeakHashMap

object SystemUiStatusBarGestureInstaller {
    private val installedRoots = Collections.newSetFromMap(WeakHashMap<View, Boolean>())

    // TYPE_NAVIGATION_BAR is @hidden in android.jar and will not resolve, so it is
    // spelled out. The value has been fixed since API 14. TYPE_STATUS_BAR is public.
    private const val TYPE_NAVIGATION_BAR = 2019

    fun install(anchor: View) {
        runCatching {
            val roots = resolveRootViews()
                ?: error("No SystemUI root view source available")
            val screenWidth = anchor.resources.displayMetrics.widthPixels
            val maximumHeight = (64f * anchor.resources.displayMetrics.density).toInt()
            // Height and width alone cannot tell the two system bars apart: on ColorOS 17
            // the gesture NavigationBarFrame is 1272x154 and the status bar is a similar
            // full-width strip, so the old heuristic happily attached the observer to the
            // nav bar. Window type is the only reliable discriminator, and it matters:
            // the detector only starts on touches whose rawY is inside the top strip, so
            // an observer parked on the bottom bar can never confirm anything.
            val candidates = roots.filterIsInstance<View>().filter { candidate ->
                candidate !== anchor.rootView &&
                    candidate.width >= screenWidth - 2 &&
                    candidate.height in 1..maximumHeight &&
                    windowType(candidate) != TYPE_NAVIGATION_BAR
            }
            val root = candidates.firstOrNull { windowType(it) == WindowManager.LayoutParams.TYPE_STATUS_BAR }
                ?: candidates.firstOrNull()
                ?: error("SystemUI status-bar root is unavailable")
            NeXtepLog.info(
                "top_corner_gesture",
                "Bar roots: " + roots.filterIsInstance<View>().joinToString {
                    "${it.javaClass.simpleName}(${it.width}x${it.height}, type=${windowType(it)})"
                },
            )

            synchronized(installedRoots) {
                if (!installedRoots.add(root)) return
            }
            val original = currentTouchListener(root)
            val coordinator = SystemUiGestureCoordinator(root.context)
            root.setOnTouchListener { view, event ->
                if (coordinator.observe(event, screenWidth) == TopCornerGestureDetector.Result.CONFIRMED) {
                    coordinator.dispatchConfirmedToggle()
                    NeXtepLog.info(
                        "top_corner_gesture",
                        "Confirmed by status-bar root observer without consuming the event",
                    )
                }
                original?.onTouch(view, event) == true
            }
            NeXtepLog.info(
                "top_corner_gesture",
                "Installed status-bar observer on ${root.javaClass.name} ${root.width}x${root.height}",
            )
        }.onFailure { error ->
            NeXtepLog.warn("top_corner_gesture", "Unable to install status-bar observer", error)
        }
    }

    /** Android 17 no longer exposes a public no-arg getRootViews(). Fall back to the
     *  bookkeeping lists WindowManagerGlobal has always kept alongside the window. */
    private fun resolveRootViews(): Collection<View>? {
        val globalClass = runCatching { Class.forName("android.view.WindowManagerGlobal") }
            .getOrNull() ?: return null
        val global = runCatching { globalClass.getMethod("getInstance").invoke(null) }
            .getOrNull() ?: return null

        val byMethod = runCatching {
            globalClass.getDeclaredMethod("getRootViews").apply { isAccessible = true }
                .invoke(global) as? Collection<*>
        }.getOrNull()?.filterIsInstance<View>().orEmpty()
        if (byMethod.isNotEmpty()) return byMethod

        val byViews = runCatching {
            globalClass.getDeclaredField("mViews").apply { isAccessible = true }
                .get(global) as? Collection<*>
        }.getOrNull()?.filterIsInstance<View>().orEmpty()
        if (byViews.isNotEmpty()) return byViews

        return runCatching {
            val roots = globalClass.getDeclaredField("mRoots").apply { isAccessible = true }
                .get(global) as? Collection<*> ?: return null
            roots.mapNotNull { root ->
                runCatching {
                    root?.javaClass?.getMethod("getView")?.invoke(root) as? View
                }.getOrNull()
            }
        }.getOrNull()?.takeIf { it.isNotEmpty() }
    }

    /** Root views added through WindowManager carry their window type in layout params. */
    private fun windowType(view: View): Int? = (view.layoutParams as? WindowManager.LayoutParams)?.type

    private fun currentTouchListener(view: View): View.OnTouchListener? = runCatching {
        val listenerInfoField = View::class.java.getDeclaredField("mListenerInfo").apply {
            isAccessible = true
        }
        val listenerInfo = listenerInfoField.get(view) ?: return null
        val touchListenerField = listenerInfo.javaClass.getDeclaredField("mOnTouchListener").apply {
            isAccessible = true
        }
        touchListenerField.get(listenerInfo) as? View.OnTouchListener
    }.getOrNull()
}
