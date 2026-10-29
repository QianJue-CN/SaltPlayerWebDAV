@file:OptIn(com.xuncorp.spw.workshop.api.UnstableSpwWorkshopApi::class, androidx.compose.runtime.InternalComposeApi::class)

package com.saltplayermod.webdav

import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.runtime.Composer
import androidx.compose.runtime.currentComposer
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.dp
import com.xuncorp.spw.workshop.api.WorkshopApi
import com.xuncorp.spw.workshop.api.hook.HookHandle
import java.io.Closeable
import java.lang.reflect.Method

internal class NativeUiBindings(loader: ClassLoader, version: String) {
    val contentMethod: Method
    val callOwner: String = "androidx.compose.ui.ஏ\$Ԫ"
    private val theme: Class<*>
    private val colorsGetter: Method
    private val stylesGetter: Method
    private val shapesGetter: Method
    private val dimensGetter: Method
    private val colorMethods: List<Method>
    private val mainStyle: Method
    private val subStyle: Method
    private val smallShape: Method
    private val mediumShape: Method
    private val padding: Method

    init {
        require(version == "1.19.3") { "内嵌设置目前适配 Windows 版 Salt Player 1.19.3" }
        val screen = loader.loadClass("androidx.compose.ui.ஏ")
        val group = loader.loadClass("androidx.compose.ui.Ь")
        contentMethod = screen.declaredMethods.single { java.lang.reflect.Modifier.isPublic(it.modifiers) &&
            it.parameterTypes.toList() == listOf(group, String::class.java, Composer::class.java, Int::class.javaPrimitiveType)
        }.apply { isAccessible = true }
        loader.loadClass(callOwner)
        require(contentMethod.returnType == Void.TYPE)
        theme = loader.loadClass("androidx.compose.ui.ʰ")
        colorsGetter = theme.getMethod("Ԩ", Composer::class.java)
        stylesGetter = theme.getMethod("ԩ", Composer::class.java)
        dimensGetter = theme.getMethod("Ԫ", Composer::class.java)
        shapesGetter = theme.getMethod("ԫ", Composer::class.java)
        colorMethods = listOf("Ϳ", "Ԩ", "ԩ", "Ԫ", "ԫ", "Ԭ", "ԭ", "Ԯ", "ԯ", "ՠ", "ֈ")
            .map { colorsGetter.returnType.getMethod(it).also { method -> require(method.returnType == Long::class.javaPrimitiveType) } }
        mainStyle = stylesGetter.returnType.getMethod("Ϳ")
        subStyle = stylesGetter.returnType.getMethod("Ԩ")
        smallShape = shapesGetter.returnType.getMethod("Ϳ")
        mediumShape = shapesGetter.returnType.getMethod("Ԩ")
        padding = dimensGetter.returnType.getMethod("ԩ")
        require(mainStyle.returnType == TextStyle::class.java && subStyle.returnType == TextStyle::class.java)
        require(smallShape.returnType == Shape::class.java && mediumShape.returnType == Shape::class.java)
        require(padding.returnType == Float::class.javaPrimitiveType)
    }

    fun palette(composer: Composer): SettingsPalette {
        val colors = colorsGetter.invoke(null, composer)
        val values = colorMethods.map { Color((it.invoke(colors) as Long).toULong()) }
        val styles = stylesGetter.invoke(null, composer)
        val shapes = shapesGetter.invoke(null, composer)
        val dimensions = dimensGetter.invoke(null, composer)
        val main = mainStyle.invoke(styles) as TextStyle
        return SettingsPalette(
            highlight = values[0], text = values[1], subText = values[2], background = values[3],
            card = values[4], stroke = values[6], onHighlight = values[7],
            success = values[8], warning = values[9], error = values[10],
            main = main, sub = main.merge(subStyle.invoke(styles) as TextStyle),
            small = smallShape.invoke(shapes) as Shape, medium = mediumShape.invoke(shapes) as Shape,
            padding = (padding.invoke(dimensions) as Float).dp,
        )
    }

    fun renderOriginal(arguments: List<Any?>, composer: Composer) {
        contentMethod.invoke(null, *arguments.toTypedArray(), composer, 0)
    }
}

internal class NativeSettingsBridge(
    version: String,
    pluginId: String,
    controller: LibraryController,
) : Closeable {
    private val bindings = NativeUiBindings(WorkshopApi::class.java.classLoader, version)
    private val hook: HookHandle = WorkshopApi.hookRegistrar.replaceComposable(
        className = bindings.contentMethod.declaringClass.name,
        methodName = bindings.contentMethod.name,
        inClass = bindings.callOwner,
        parameterTypes = bindings.contentMethod.parameterTypes.take(2).map { it.name },
    ) { call ->
        val composer = currentComposer
        if (call.getArgument(1) == pluginId) {
            LibraryPanel(controller, PaddingValues(), bindings.palette(composer))
        } else bindings.renderOriginal(call.arguments, composer)
    }

    override fun close() = hook.close()
}
