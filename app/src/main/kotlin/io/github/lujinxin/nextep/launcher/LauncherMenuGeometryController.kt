package io.github.lujinxin.nextep.launcher

import android.provider.Settings
import android.view.View
import io.github.libxposed.api.XposedInterface
import io.github.libxposed.api.XposedModule
import io.github.lujinxin.nextep.logging.NeXtepLog
import java.lang.reflect.Method

/** COUI's detached menu uses the display origin, not HOME's translated decor origin. */
internal object LauncherMenuGeometryController {
    private val menuRoot = ThreadLocal<View?>()
    private const val ANCHOR_CLASS = "com.coui.appcompat.poplist.i"
    // Verified on the launcher process of ColorOS 17 (sdk 37). The symbol never changed:
    // the earlier failures came from other packages, because this controller runs for
    // every loaded package and non-launcher ones ship a different COUI or none at all.
    private const val ANCHOR_METHOD = "j"

    fun install(module: XposedModule, loader: ClassLoader) {
        val locate = resolveAnchor(loader)
            ?: throw NoSuchMethodException("$ANCHOR_CLASS anchor unavailable: ${dumpCandidates(loader)}")
        locate.isAccessible = true
        NeXtepLog.info(
            "launcher_menu_geometry",
            "Anchor ${locate.name}(${locate.parameterTypes.joinToString { it.simpleName }})",
        )
        module.hook(locate).intercept(object : XposedInterface.Hooker {
            override fun intercept(chain: XposedInterface.Chain): Any? {
                val anchor = chain.getArg(0) as? View ?: return chain.proceed()
                if (!LauncherTransformController.isActive() ||
                    !Settings.canDrawOverlays(anchor.context) ||
                    !LauncherPackageResolver.isCurrentHome(anchor.context)) return chain.proceed()
                val previous = menuRoot.get()
                menuRoot.set(anchor.rootView)
                return try {
                    chain.proceed()
                } finally {
                    if (previous == null) menuRoot.remove() else menuRoot.set(previous)
                }
            }
        })
        module.hook(View::class.java.getDeclaredMethod("getLocationOnScreen", IntArray::class.java))
            .intercept(object : XposedInterface.Hooker {
                override fun intercept(chain: XposedInterface.Chain): Any? {
                    val result = chain.proceed()
                    if (menuRoot.get() === chain.thisObject) {
                        val location = chain.getArg(0) as IntArray
                        // COUI subtracts this origin from the physical visible display frame.
                        // Subtracting HOME's workspace translation invents a bottom inset,
                        // squeezing folder shortcut rows into a fraction of their height.
                        location[0] = 0
                        location[1] = 0
                    }
                    return result
                }
            })
        NeXtepLog.info("launcher_menu_geometry", "Installed detached shortcut menu display origin")
    }

    /** Exact symbol first, so a rename to a different method with the same shape cannot
     *  be picked up by accident. Fall back to shape matching only if the symbol moves. */
    private fun resolveAnchor(loader: ClassLoader): Method? {
        val target = runCatching { loader.loadClass(ANCHOR_CLASS) }.getOrNull() ?: return null
        val exact = target.declaredMethods.filter { candidate ->
            candidate.name == ANCHOR_METHOD &&
                candidate.parameterTypes.size == 3 &&
                candidate.parameterTypes[0] == View::class.java &&
                candidate.parameterTypes[1] == Int::class.javaPrimitiveType &&
                candidate.parameterTypes[2] == Int::class.javaPrimitiveType
        }
        return exact.singleOrNull() ?: target.declaredMethods.firstOrNull { candidate ->
            candidate.parameterTypes.size == 3 &&
                candidate.parameterTypes[0] == View::class.java &&
                candidate.parameterTypes[1].isNumeric() &&
                candidate.parameterTypes[2].isNumeric()
        }
    }

    private fun dumpCandidates(loader: ClassLoader): String {
        val target = runCatching { loader.loadClass(ANCHOR_CLASS) }.getOrNull()
            ?: return "class missing"
        return target.declaredMethods.joinToString { method ->
            "${method.name}(${method.parameterTypes.joinToString(",") { it.simpleName }})"
        }
    }

    private fun Class<*>.isNumeric(): Boolean =
        this == Int::class.javaPrimitiveType || this == Float::class.javaPrimitiveType ||
            this == Long::class.javaPrimitiveType || this == Double::class.javaPrimitiveType
}
