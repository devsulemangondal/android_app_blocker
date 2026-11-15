package com.appblocker.android_app_blocker

import android.content.Context
import android.graphics.drawable.GradientDrawable
import android.util.TypedValue
import android.view.LayoutInflater
import android.view.View
import android.widget.Button
import android.widget.ImageView
import android.widget.TextView

/**
 * Builds the plugin's built-in block screen from a [BlockerConfig]. Every
 * string and colour comes from the host app — the plugin ships no copy of its
 * own beyond neutral defaults.
 */
internal object NativeOverlayView {

    fun create(
        context: Context,
        config: BlockerConfig,
        blockedPackage: String,
        appLabel: String?,
        onPrimary: () -> Unit,
        onSecondary: () -> Unit,
    ): View {
        val root = LayoutInflater.from(context)
            .inflate(R.layout.aab_overlay_default, null, false)

        root.setBackgroundColor(config.backgroundColor)
        applySystemBarPadding(context, root)

        val icon = root.findViewById<ImageView>(R.id.aab_overlay_icon)
        val customIcon = config.resolveIcon(context, config.iconResourceName)
        if (customIcon != 0) {
            icon.setImageResource(customIcon)
            icon.imageTintList = null
        } else {
            icon.setImageResource(R.drawable.aab_ic_block)
            icon.imageTintList = android.content.res.ColorStateList.valueOf(config.accentColor)
        }

        root.findViewById<TextView>(R.id.aab_overlay_title).apply {
            text = config.title
            setTextColor(config.titleColor)
        }

        root.findViewById<TextView>(R.id.aab_overlay_message).apply {
            text = config.message
            setTextColor(config.messageColor)
        }

        root.findViewById<TextView>(R.id.aab_overlay_app_label).apply {
            val label = appLabel?.takeIf { it.isNotBlank() } ?: blockedPackage
            if (label.isNotBlank()) {
                text = label
                visibility = View.VISIBLE
            } else {
                visibility = View.GONE
            }
        }

        root.findViewById<Button>(R.id.aab_overlay_primary).apply {
            text = config.primaryButtonText
            setTextColor(config.primaryButtonTextColor)
            background = GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                cornerRadius = dp(context, 16f)
                setColor(config.accentColor)
            }
            setOnClickListener { onPrimary() }
        }

        root.findViewById<TextView>(R.id.aab_overlay_secondary).apply {
            val label = config.secondaryButtonText
            if (label.isNullOrBlank()) {
                visibility = View.GONE
            } else {
                visibility = View.VISIBLE
                text = label
                setTextColor(config.secondaryButtonTextColor)
                setOnClickListener { onSecondary() }
            }
        }

        return root
    }

    /**
     * The overlay window uses FLAG_LAYOUT_NO_LIMITS so it covers the status and
     * navigation bars; re-add that space as padding so nothing is clipped.
     */
    private fun applySystemBarPadding(context: Context, root: View) {
        val res = context.resources
        val statusId = res.getIdentifier("status_bar_height", "dimen", "android")
        val navId = res.getIdentifier("navigation_bar_height", "dimen", "android")
        val top = if (statusId > 0) {
            res.getDimensionPixelSize(statusId)
        } else {
            dp(context, 28f).toInt()
        }
        val bottom = if (navId > 0) res.getDimensionPixelSize(navId) else 0
        root.setPadding(
            root.paddingLeft,
            root.paddingTop + top,
            root.paddingRight,
            root.paddingBottom + bottom,
        )
    }

    private fun dp(context: Context, value: Float): Float = TypedValue.applyDimension(
        TypedValue.COMPLEX_UNIT_DIP,
        value,
        context.resources.displayMetrics,
    )
}
