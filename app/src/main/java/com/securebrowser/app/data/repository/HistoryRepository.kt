package com.securebrowser.app.data.repository

import com.securebrowser.app.data.db.dao.HistoryDao
import com.securebrowser.app.data.db.entity.HistoryEntity
import kotlinx.coroutines.flow.Flow

/**
 * مستودع سجل التصفح — مرحلة 2.
 * - كل زيارة مسموحة تُسجَّل (نتيجة ALLOWED أو TEMPORARY أثناء نافذة الوصول المؤقت).
 * - المستخدم العادي لا يستطيع المسح/الحذف/التعطيل — لا توجد واجهة لذلك؛
 *   الحذف والمسح داخل لوحة الوالدين فقط ([delete], [clearAll]).
 * - تعطيل السجل مستحيل بنيويًا: لا يوجد أي مفتاح إعداد يمس التسجيل.
 */
class HistoryRepository(private val dao: HistoryDao) {

    suspend fun record(
        url: String,
        title: String?,
        host: String?,
        visitResult: String = "ALLOWED",
        timestamp: Long = System.currentTimeMillis()
    ) {
        // v1.7.0 — منع التكرار عند المصدر: الزيارات المتقاربة لنفس الرابط
        // (إعادة تحميل يدوية، تنقلات SPA الداخلية، استعادة الجلسة) تُدمج
        // في زيارة واحدة خلال النافذة الزمنية — يبقى السجل بسيطًا وواضحًا
        // دون فقدان أي معلومة حقيقية للوالد (الزيارات البعيدة تُسجّل كاملة).
        val canonical = url.take(2048)
        dao.lastVisitAt(canonical)?.let { last ->
            if (timestamp - last in 0 until DEDUPE_WINDOW_MS) return
        }
        dao.insert(
            HistoryEntity(
                url = canonical,
                title = title?.take(300),
                host = host?.take(253),
                timestamp = timestamp,
                visitResult = visitResult
            )
        )
    }

    suspend fun recent(limit: Int = 100): List<HistoryEntity> = dao.recent(limit)

    fun observeRecent(limit: Int = 500): Flow<List<HistoryEntity>> = dao.observeRecent(limit)

    suspend fun search(query: String, limit: Int = 100): List<HistoryEntity> =
        dao.search(query, limit)

    /** حذف عنصر — مسار الوالدين فقط. */
    suspend fun delete(id: Long) = dao.deleteById(id)

    /** مسح كامل — مسار الوالدين فقط. */
    suspend fun clearAll() = dao.clearAll()

    companion object {
        const val RESULT_ALLOWED = "ALLOWED"
        const val RESULT_TEMPORARY = "TEMPORARY"

        /** v1.7.0: نافذة دمج الزيارات المتقاربة لنفس الرابط — 5 دقائق. */
        private const val DEDUPE_WINDOW_MS = 5L * 60_000L
    }
}
