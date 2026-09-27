package com.securebrowser.app.ui

import android.content.Context
import android.graphics.drawable.GradientDrawable
import android.util.TypedValue
import androidx.appcompat.app.AlertDialog

/**
 * تنسيق موحد لحاويات بوابة الرمز (v1.5.0) — بطاقة عائمة حديثة:
 * خلفية بلون السطح وزوايا 24dp، بلا شريط عنوان نظام — العنوان يعيش داخل
 * اللوحة نفسها ([PinPadView]) فتبدو البوابة شاشة مخصصة لا حوارًا عامًا.
 *
 * يستخدمها: [PinGate] وحوار تغيير الرمز في إعدادات الأمان.
 */
object PinDialogStyle {

    /** يطبّق الخلفية المستديرة والعرض الأدنى المريح على حوار الرمز. */
    fun apply(context: Context, dialog: AlertDialog) {
        val surface = resolveColor(context, com.google.android.material.R.attr.colorSurface)
        val background = GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            cornerRadius = dp(context, 24).toFloat()
            setColor(surface)
        }
        dialog.window?.setBackgroundDrawable(background)
    }

    fun dp(context: Context, value: Int): Int =
        (value * context.resources.displayMetrics.density).toInt()

    fun resolveColor(context: Context, attr: Int): Int {
        val tv = TypedValue()
        context.theme.resolveAttribute(attr, tv, true)
        return tv.data
    }
}
