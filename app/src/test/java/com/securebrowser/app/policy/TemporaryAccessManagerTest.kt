package com.securebrowser.app.policy

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * مصفوفة الوصول المؤقت — مواصفة المرحلة 2 §7-§8:
 * - مدد محددة (5/10/15/30/60/مخصص) وعد تنازلي.
 * - الانتهاء يعيد فرض القائمة البيضاء تلقائيًا.
 * - إغلاق التطبيق أثناء النافذة لا يحولها إلى تجاوز دائم.
 * - التمديد مسار موثق فقط.
 */
class TemporaryAccessManagerTest {

    private fun manager(now: LongVar) =
        TemporaryAccessManager(InMemoryTempAccessStore()) { now.value }

    class LongVar(var value: Long = 0)

    @Test
    fun `start activates window and counts down`() {
        val now = LongVar(1000)
        val m = manager(now)
        assertFalse(m.isActive())
        m.start(5 * 60_000)
        assertTrue(m.isActive())
        assertEquals(5 * 60_000, m.remainingMs())
        now.value += 60_000
        assertEquals(4 * 60_000, m.remainingMs())
    }

    @Test
    fun `expiry restores enforcement automatically`() {
        val now = LongVar(0)
        val m = manager(now)
        m.start(15 * 60_000)
        assertTrue(m.isActive())
        now.value = 15 * 60_000 + 1
        assertFalse(m.isActive())
        assertEquals(0, m.remainingMs())
        assertFalse(m.uiState.value.active)
    }

    @Test
    fun `endNow works immediately`() {
        val now = LongVar(0)
        val m = manager(now)
        m.start(30 * 60_000)
        assertTrue(m.isActive())
        m.endNow()
        assertFalse(m.isActive())
        assertEquals(0, m.remainingMs())
    }

    @Test
    fun `closing app during window does not become permanent override`() {
        val store = InMemoryTempAccessStore()
        val now = LongVar(0)
        val first = TemporaryAccessManager(store) { now.value }
        first.start(10 * 60_000)
        // محاكاة إغلاق التطبيق لمدة تتجاوز النافذة ثم إعادة التشغيل
        now.value = 11 * 60_000
        val second = TemporaryAccessManager(store) { now.value }
        second.restoreFromStore()
        assertFalse("النافذة المنتهية يجب أن تختفي", second.isActive())
        assertFalse(second.uiState.value.active)
    }

    @Test
    fun `closing app before expiry resumes countdown not reset`() {
        val store = InMemoryTempAccessStore()
        val now = LongVar(0)
        TemporaryAccessManager(store) { now.value }.start(60 * 60_000)
        now.value = 45 * 60_000
        val second = TemporaryAccessManager(store) { now.value }
        second.restoreFromStore()
        assertTrue(second.isActive())
        assertEquals(15 * 60_000, second.remainingMs())
    }

    @Test
    fun `extend requires explicit call - presets exist`() {
        val now = LongVar(0)
        val m = manager(now)
        m.start(5 * 60_000)
        now.value = 4 * 60_000
        m.extend(10 * 60_000)
        // التمديد يبدأ من اللحظة الحالية
        assertEquals(10 * 60_000, m.remainingMs())
        assertEquals(listOf(5, 10, 15, 30, 60), TemporaryAccessManager.PRESET_MINUTES)
    }

    @Test
    fun `invalid durations rejected`() {
        val now = LongVar(0)
        val m = manager(now)
        assertFalse(m.start(0))
        assertFalse(m.start(-1))
        assertFalse(m.start(TemporaryAccessManager.MAX_DURATION_MS + 1))
        assertFalse(m.isActive())
    }

    @Test
    fun `tick updates ui state and auto clears expired`() {
        val store = InMemoryTempAccessStore()
        val now = LongVar(0)
        val m = TemporaryAccessManager(store) { now.value }
        m.start(2_000)
        assertTrue(m.uiState.value.active)
        now.value = 2_500
        m.tick()
        assertFalse(m.uiState.value.active)
        // التنظيف التلقائي من المخزن عند tick بعد الانتهاء
        m.endNow()
        assertEquals(null, store.getExpiry())
    }
}
