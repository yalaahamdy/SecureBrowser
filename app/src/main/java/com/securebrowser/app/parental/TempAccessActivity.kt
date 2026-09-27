package com.securebrowser.app.parental

import android.os.Bundle
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.isVisible
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.google.android.material.chip.Chip
import com.securebrowser.app.R
import com.securebrowser.app.databinding.ActivityTempAccessBinding
import com.securebrowser.app.di.ServiceLocator
import com.securebrowser.app.policy.TemporaryAccessManager
import kotlinx.coroutines.launch

/**
 * قسم Security → Temporary Access (§7-§8):
 * - اختيار المدة: 5/10/15/30/60 دقيقة أو مخصص.
 * - نافذة شاملة يزور فيها الوالد المواقع التي يريد إضافتها ثم يضيفها من السجل.
 * - مؤقت تنازلي + إنهاء فوري؛ التمديد من هذه الشاشة فقط (بعد المصادقة بالفعل).
 * - الانتهاء يعيد فرض القائمة البيضاء تلقائيًا بلا أي تدخل.
 */
class TempAccessActivity : AppCompatActivity() {

    private lateinit var binding: ActivityTempAccessBinding

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityTempAccessBinding.inflate(layoutInflater)
        setContentView(binding.root)

        binding.btnToolbarBack.setOnClickListener { finish() }
        buildDurationChips()

        binding.btnStartTemp.setOnClickListener {
            val minutes = selectedMinutes() ?: run {
                Toast.makeText(this, R.string.temp_custom_minutes, Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            if (minutes !in 1..TemporaryAccessManager.MAX_MINUTES) {
                Toast.makeText(this, R.string.temp_custom_minutes, Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            val started = ServiceLocator.temporaryAccess.start(minutes * 60_000L)
            if (started) {
                Toast.makeText(this, R.string.temp_started, Toast.LENGTH_LONG).show()
                // بعد البدء: العودة للمتصفح لزيارة المواقع المراد إضافتها
                finish()
            }
        }

        binding.btnEndNow.setOnClickListener {
            ServiceLocator.temporaryAccess.endNow()
            Toast.makeText(this, R.string.temp_ended, Toast.LENGTH_SHORT).show()
        }

        lifecycleScope.launch {
            lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
                ServiceLocator.temporaryAccess.uiState.collect { state ->
                    binding.activePanel.isVisible = state.active
                    binding.setupPanel.isVisible = !state.active
                    if (state.active) {
                        val totalSec = state.remainingMs / 1000
                        binding.countdownText.text = String.format(
                            java.util.Locale.US, "%02d:%02d", totalSec / 60, totalSec % 60
                        )
                    }
                }
            }
        }
    }

    private var selectedChipMinutes: Int? = null

    private fun buildDurationChips() {
        val presets = TemporaryAccessManager.PRESET_MINUTES
        for (minutes in presets) {
            val chip = Chip(this).apply {
                id = View_GeneratedId()
                isCheckable = true
                text = if (minutes >= 60) getString(R.string.temp_hours_short, minutes / 60)
                else getString(R.string.temp_minutes_short, minutes)
                tag = minutes
            }
            binding.durationChips.addView(chip)
        }
        binding.durationChips.setOnCheckedStateChangeListener { group, checkedIds ->
            selectedChipMinutes = checkedIds.firstOrNull()
                ?.let { group.findViewById<Chip>(it)?.tag as? Int }
            if (selectedChipMinutes != null) binding.customMinutes.setText("")
        }
    }

    private fun selectedMinutes(): Int? {
        binding.customMinutes.text?.toString()?.toIntOrNull()?.let { return it }
        return selectedChipMinutes
    }

    private fun View_GeneratedId(): Int = android.view.View.generateViewId()
}
