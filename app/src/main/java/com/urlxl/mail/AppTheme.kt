package com.urlxl.mail

import android.app.Activity
import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.GradientDrawable
import android.text.SpannableString
import android.text.style.ForegroundColorSpan
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.ImageView
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat

const val THEME_STORAGE_KEY = "llama-lab-theme"

val THEME_OPTIONS = listOf(
    "Dark Matter",
    "Light Matter",
    "Tropics",
    "Tropic Night",
    "Ocean",
    "Coffee",
    "White Cliffs",
    "Cyber Punk",
    "Neon Purple",
    "Space",
    "Sky",
    "Forest",
    "Sun",
)

data class ThemePalette(
    val bg: String,
    val panel: String,
    val ink: String,
    val inkStrong: String,
    val accent: String,
    val line: String,
)

private val themePalettes: Map<String, ThemePalette> = mapOf(
    "Dark Matter" to ThemePalette("#1a1a1e", "#252530", "#d4c5e2", "#e8ddf5", "#c29a72", "#404050"),
    "Light Matter" to ThemePalette("#f5efe5", "#fff8ee", "#4c3d32", "#2d1f15", "#c29a72", "#c5b29d"),
    "Tropics" to ThemePalette("#f4f1eb", "#fffaf0", "#43362d", "#241a14", "#9bc400", "#c4b7a3"),
    "Tropic Night" to ThemePalette("#15131a", "#221f2b", "#cdbde0", "#e8ddf5", "#9bc400", "#3c3650"),
    "Ocean" to ThemePalette("#0f1b24", "#152a36", "#b8d8e8", "#e0f2fb", "#5ea9be", "#2f5567"),
    "Coffee" to ThemePalette("#1d1714", "#2a211d", "#d6c0b3", "#f0ded2", "#b47f5c", "#4a3830"),
    "White Cliffs" to ThemePalette("#f7f9fb", "#ffffff", "#2e4c63", "#163246", "#5ea8d8", "#8fc3df"),
    "Cyber Punk" to ThemePalette("#120918", "#1e1028", "#f5d0ff", "#ffe9ff", "#00f5d4", "#5c2d84"),
    "Neon Purple" to ThemePalette("#130b1d", "#231233", "#e4ccff", "#f2e6ff", "#c86cff", "#63358a"),
    "Space" to ThemePalette("#0b0f1a", "#151c2d", "#c8d5f0", "#e7efff", "#86a8ff", "#34496f"),
    "Sky" to ThemePalette("#dff1ff", "#f4fbff", "#2f4f64", "#183142", "#6db3d6", "#93bdd2"),
    "Forest" to ThemePalette("#142018", "#1f2f24", "#c7dbc7", "#e3f0df", "#8faa74", "#4f694f"),
    "Sun" to ThemePalette("#fff3dc", "#fff9ec", "#5a4024", "#392611", "#e0ab4f", "#d4b27a"),
)

fun getStoredThemeName(context: Context): String {
    val prefs = context.getSharedPreferences("com.urlxl.mail.settings", Context.MODE_PRIVATE)
    val saved = prefs.getString(THEME_STORAGE_KEY, "Dark Matter") ?: "Dark Matter"
    return if (THEME_OPTIONS.contains(saved)) saved else "Dark Matter"
}

fun saveThemeName(context: Context, themeName: String) {
    val prefs = context.getSharedPreferences("com.urlxl.mail.settings", Context.MODE_PRIVATE)
    prefs.edit().putString(THEME_STORAGE_KEY, themeName).apply()
}

fun getStoredThemePalette(context: Context): ThemePalette {
    return themePaletteFor(getStoredThemeName(context))
}

fun themePaletteFor(themeName: String): ThemePalette {
    return themePalettes[themeName] ?: themePalettes.getValue("Dark Matter")
}

fun applyThemeToActivity(activity: Activity) {
    val palette = getStoredThemePalette(activity)
    val bgColor = Color.parseColor(palette.bg)
    val panelColor = Color.parseColor(palette.panel)
    val accentColor = Color.parseColor(palette.accent)

    @Suppress("DEPRECATION")
    run {
        activity.window.statusBarColor = accentColor
        activity.window.navigationBarColor = panelColor
    }
    // Edge-to-edge (API 35+) ignores statusBarColor, leaving the raw window background (light by
    // default) visible behind the status bar. Paint the window itself so it matches the toolbar.
    activity.window.decorView.setBackgroundColor(accentColor)
    WindowInsetsControllerCompat(activity.window, activity.window.decorView).isAppearanceLightStatusBars =
        readableOn(accentColor) == Color.BLACK

    if (activity is AppCompatActivity) {
        activity.supportActionBar?.setBackgroundDrawable(ColorDrawable(accentColor))
        activity.supportActionBar?.title = styledTitle(activity.title?.toString().orEmpty(), readableOn(accentColor))
    }

    // The overflow ("more options") icon isn't part of the content view tree, so it never gets
    // painted by applyThemeToViewTree below. It defaults to a fixed light tint from the base
    // theme, which disappears against light accent colors (Sun, Sky, White Cliffs, ...). The menu
    // only exists once onCreateOptionsMenu has run, so defer the lookup by a frame.
    activity.window.decorView.post {
        tintOverflowIcon(activity, readableOn(accentColor))
    }

    val root: View = activity.findViewById(android.R.id.content)
    root.setBackgroundColor(bgColor)
    applyThemeToViewTree(root, palette)
}

/** Sets the action-bar title and repaints just its text color for the active palette, without
 *  re-theming the whole view tree (which would clobber custom backgrounds like the tab bar). */
fun applyThemedTitle(activity: Activity, title: CharSequence) {
    activity.title = title
    if (activity is AppCompatActivity) {
        val accent = Color.parseColor(getStoredThemePalette(activity).accent)
        activity.supportActionBar?.title = styledTitle(title.toString(), readableOn(accent))
    }
}

/** Pads the view's bottom by the system navigation-bar inset so edge-to-edge content (e.g. the
 *  bottom navigation bar) clears the gesture/nav area. */
fun applyBottomInset(view: View) {
    val basePaddingBottom = view.paddingBottom
    ViewCompat.setOnApplyWindowInsetsListener(view) { v, insets ->
        val bottomInset = insets.getInsets(WindowInsetsCompat.Type.systemBars()).bottom
        v.setPadding(v.paddingLeft, v.paddingTop, v.paddingRight, basePaddingBottom + bottomInset)
        insets
    }
    ViewCompat.requestApplyInsets(view)
}

fun applyTopInsetWithHeader(activity: Activity, root: View) {
    val basePaddingLeft = root.paddingLeft
    val basePaddingTop = root.paddingTop
    val basePaddingRight = root.paddingRight
    val basePaddingBottom = root.paddingBottom

    ViewCompat.setOnApplyWindowInsetsListener(root) { view, insets ->
        val topInset = insets.getInsets(WindowInsetsCompat.Type.systemBars()).top
        // Under the enforced edge-to-edge of targetSdk 36, windowSoftInputMode="adjustResize" no
        // longer shrinks the window for the keyboard, so pad by the IME inset ourselves. It reads 0
        // whenever the keyboard is hidden, so screens without text fields are unaffected.
        val imeInset = insets.getInsets(WindowInsetsCompat.Type.ime()).bottom
        view.setPadding(
            basePaddingLeft,
            basePaddingTop + topInset + actionBarSize(activity),
            basePaddingRight,
            basePaddingBottom + imeInset,
        )
        insets
    }
    ViewCompat.requestApplyInsets(root)
}

fun applyPrimaryButtonTheme(context: Context, button: Button) {
    val palette = getStoredThemePalette(context)
    button.backgroundTintList = null
    button.background = buttonBackground(palette)
    button.setTextColor(readableOn(Color.parseColor(palette.accent)))
}

fun applyIconButtonTheme(context: Context, button: android.widget.ImageButton) {
    val palette = getStoredThemePalette(context)
    button.imageTintList = ColorStateList.valueOf(Color.parseColor(palette.inkStrong))
}

private fun tintOverflowIcon(activity: Activity, color: Int) {
    val description = activity.getString(androidx.appcompat.R.string.abc_action_menu_overflow_description)
    val overflowButton = findViewByContentDescription(activity.window.decorView, description) as? ImageView
    overflowButton?.imageTintList = ColorStateList.valueOf(color)
}

private fun findViewByContentDescription(view: View, description: CharSequence): View? {
    if (view.contentDescription == description) return view
    if (view is ViewGroup) {
        for (index in 0 until view.childCount) {
            findViewByContentDescription(view.getChildAt(index), description)?.let { return it }
        }
    }
    return null
}

private fun applyThemeToViewTree(view: View, palette: ThemePalette) {
    val panelColor = Color.parseColor(palette.panel)
    val inkStrong = Color.parseColor(palette.inkStrong)
    val ink = Color.parseColor(palette.ink)
    val accent = Color.parseColor(palette.accent)

    when (view) {
        is EditText -> {
            view.setTextColor(inkStrong)
            view.setHintTextColor(ink)
            view.background = fieldBackground(palette)
            // A bare GradientDrawable carries no internal padding, so without this the text and
            // hint sit flush against the rounded border. Preserve any larger top padding a
            // multi-line field (e.g. the compose body) already declares.
            val padH = (14 * density).toInt()
            val padV = (12 * density).toInt()
            view.setPadding(padH, maxOf(padV, view.paddingTop), padH, maxOf(padV, view.paddingBottom))
        }
        is Button -> {
            view.setTextColor(readableOn(accent))
            view.background = buttonBackground(palette)
        }
        is CheckBox -> {
            view.setTextColor(inkStrong)
            view.buttonTintList = ColorStateList.valueOf(accent)
        }
        is TextView -> {
            // Hardcoded XML text colors in this app are always grayscale template leftovers
            // (black/white/mid-gray placeholders), never intentional brand colors, so any
            // grayscale color is safe to remap onto the active palette's ink tones.
            val current = view.currentTextColor
            if (isGrayscale(current)) {
                view.setTextColor(if (isNearWhite(current) || isNearBlack(current)) inkStrong else ink)
            }
        }
    }

    if (view is ViewGroup) {
        // The inbox list themes its own item views (rounded CardViews) in the adapter. Recursing
        // into it here would overwrite each card's rounded background with a flat ColorDrawable,
        // and since only already-bound rows get hit the rounding ends up inconsistent. Skip its
        // whole subtree.
        if (view is androidx.recyclerview.widget.RecyclerView) {
            return
        }
        // Keep panel containers in sync with the active palette. Always repaint (not just when
        // background == null) so switching themes without recreating the activity refreshes
        // containers that were already painted on a previous pass, not just newly-touched ones.
        view.setBackgroundColor(panelColor)
        for (index in 0 until view.childCount) {
            applyThemeToViewTree(view.getChildAt(index), palette)
        }
    }
}

private val density: Float get() = android.content.res.Resources.getSystem().displayMetrics.density

private fun fieldBackground(palette: ThemePalette): GradientDrawable {
    return GradientDrawable().apply {
        shape = GradientDrawable.RECTANGLE
        cornerRadius = 14f * density
        setColor(Color.parseColor(palette.panel))
        setStroke((2 * density).toInt(), Color.parseColor(palette.line))
    }
}

private fun buttonBackground(palette: ThemePalette): GradientDrawable {
    return GradientDrawable().apply {
        shape = GradientDrawable.RECTANGLE
        cornerRadius = 18f * density
        setColor(Color.parseColor(palette.accent))
    }
}

private fun styledTitle(title: String, color: Int): SpannableString {
    return SpannableString(title).apply {
        setSpan(ForegroundColorSpan(color), 0, length, 0)
    }
}

/** Returns black or white — whichever reads more legibly on the given background color. */
internal fun readableOn(backgroundColor: Int): Int {
    val darkness = 1 - (0.299 * Color.red(backgroundColor) + 0.587 * Color.green(backgroundColor) + 0.114 * Color.blue(backgroundColor)) / 255
    return if (darkness >= 0.45) Color.WHITE else Color.BLACK
}

private fun isNearWhite(color: Int): Boolean {
    return Color.red(color) > 235 && Color.green(color) > 235 && Color.blue(color) > 235
}

private fun isNearBlack(color: Int): Boolean {
    return Color.red(color) < 20 && Color.green(color) < 20 && Color.blue(color) < 20
}

private fun isGrayscale(color: Int): Boolean {
    val r = Color.red(color)
    val g = Color.green(color)
    val b = Color.blue(color)
    return (maxOf(r, g, b) - minOf(r, g, b)) <= 10
}

private fun actionBarSize(activity: Activity): Int {
    val typedArray = activity.theme.obtainStyledAttributes(intArrayOf(android.R.attr.actionBarSize))
    return try {
        typedArray.getDimensionPixelSize(0, 0)
    } finally {
        typedArray.recycle()
    }
}
