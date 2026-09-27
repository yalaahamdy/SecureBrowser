package com.securebrowser.app.data.db.entity

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/** قواعد القائمة البيضاء — الجدول الأهم في التطبيق. */
@Entity(
    tableName = "white_list_rules",
    indices = [Index(value = ["host"], unique = false)]
)
data class WhiteListRuleEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    /** null = أي http/https (المضيف هو البوابة). */
    val scheme: String?,
    /** host مطبّع: lowercase punycode. */
    val host: String,
    /** null/"/" = كامل الموقع. */
    val path: String?,
    /** null = المنافذ القياسية فقط. */
    val port: Int?,
    /** RuleType name: ALLOW_DOMAIN / ALLOW_PATH_PREFIX / ALLOW_EXACT */
    val ruleType: String,
    /** SubdomainPolicy name: INCLUDE_SUBDOMAINS / EXACT_HOST_ONLY */
    val subdomainPolicy: String,
    val enabled: Boolean,
    val createdAt: Long,
    val updatedAt: Long
)
