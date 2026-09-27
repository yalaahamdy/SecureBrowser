package com.securebrowser.app.data.db.entity

import androidx.room.Entity
import androidx.room.PrimaryKey

/** إعدادات التطبيق (مفتاح/قيمة) — قابلة للتوسعة: theme, searchEngine, zoom … */
@Entity(tableName = "app_settings")
data class AppSettingEntity(
    @PrimaryKey val key: String,
    val value: String
)
