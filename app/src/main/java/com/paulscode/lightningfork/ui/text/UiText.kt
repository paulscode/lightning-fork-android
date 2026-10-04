package com.paulscode.lightningfork.ui.text

import android.content.res.Resources
import androidx.annotation.PluralsRes
import androidx.annotation.StringRes
import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalContext

/**
 * Text a view model or a rule hands the screen: one of the app's strings, in
 * the phone's language, or a sentence as it came (the node's own words, which
 * are the dashboard's English). Kept as a reference until shown, so the rules
 * stay plain Kotlin and their tests can check which text without resources.
 */
sealed class UiText {
    data class Res(@StringRes val id: Int, val args: List<Any> = emptyList()) : UiText()
    data class Plural(@PluralsRes val id: Int, val count: Int, val args: List<Any> = emptyList()) : UiText()
    data class Raw(val text: String) : UiText()

    /** Several, joined with a space. */
    data class Joined(val parts: List<UiText>) : UiText()

    fun resolve(res: Resources): String = when (this) {
        is Res -> if (args.isEmpty()) res.getString(id) else res.getString(id, *args.map { a -> if (a is UiText) a.resolve(res) else a }.toTypedArray())
        is Plural -> res.getQuantityString(id, count, *args.map { a -> if (a is UiText) a.resolve(res) else a }.toTypedArray())
        is Raw -> text
        is Joined -> parts.joinToString(" ") { it.resolve(res) }
    }

    companion object {
        fun of(@StringRes id: Int, vararg args: Any): UiText = Res(id, args.toList())
        fun raw(text: String): UiText = Raw(text)
    }
}

/** [this] in the phone's language. */
@Composable
fun UiText.text(): String = resolve(LocalContext.current.resources)

@Composable
fun UiText?.textOrNull(): String? = this?.resolve(LocalContext.current.resources)
