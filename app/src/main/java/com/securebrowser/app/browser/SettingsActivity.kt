package com.securebrowser.app.browser

import android.os.Bundle
import android.text.InputType
import android.widget.EditText
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.isVisible
import androidx.lifecycle.lifecycleScope
import com.securebrowser.app.App
import com.securebrowser.app.R
import com.securebrowser.app.data.repository.SettingsRepository
import com.securebrowser.app.databinding.ActivitySettingsBinding
import com.securebrowser.app.databinding.RowActionCardBinding
import com.securebrowser.app.di.ServiceLocator
import kotlinx.coroutines.launch

/**
 * إعدادات المستخدم العادي (§25): السمة، اللغة، محرك البحث، الرئيسية، التكبير.
 *
 * الضمانة البنيوية: لا توجد هنا أي أداة لإيقاف القائمة البيضاء أو السجل
 * أو تجاوز سياسة التنزيلات أو تعطيل قفل الوالدين — كلها من لوحة الوالدين فقط.
 */
class SettingsActivity : AppCompatActivity() {

    private lateinit var binding: ActivitySettingsBinding

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivitySettingsBinding.inflate(layoutInflater)
        setContentView(binding.root)

        binding.btnToolbarBack.setOnClickListener { finish() }

        // عناوين وأيقونات البطاقات — كانت تُترك فارغة في v1.1.0 (نفس عيب شاشة الأمان)
        bindCard(binding.cardTheme, R.drawable.ic_gear, getString(R.string.card_theme)) { showThemeDialog() }
        bindCard(binding.cardLanguage, R.drawable.ic_globe, getString(R.string.card_language)) { showLanguageDialog() }
        bindCard(binding.cardSearchEngine, R.drawable.ic_search, getString(R.string.card_search_engine)) { showSearchEngineDialog() }
        bindCard(binding.cardHomepage, R.drawable.ic_home, getString(R.string.card_homepage)) { showHomepageDialog() }
        bindCard(binding.cardZoom, R.drawable.ic_zoom_in, getString(R.string.card_default_zoom)) { showZoomDialog() }

        refreshValues()
    }

    override fun onResume() {
        super.onResume()
        refreshValues()
    }

    private val settings get() = ServiceLocator.settingsRepository

    private fun refreshValues() {
        binding.cardTheme.cardValue.isVisible = true
        binding.cardTheme.cardValue.text = themeLabel(settings.theme)
        binding.cardLanguage.cardValue.isVisible = true
        binding.cardLanguage.cardValue.text = languageLabel(settings.language)
        binding.cardSearchEngine.cardValue.isVisible = true
        binding.cardSearchEngine.cardValue.text = engineLabel(settings.searchEngine)
        binding.cardHomepage.cardValue.isVisible = true
        binding.cardHomepage.cardValue.text =
            if (settings.homepage == "start") getString(R.string.homepage_start)
            else settings.homepage
        binding.cardZoom.cardValue.isVisible = true
        binding.cardZoom.cardValue.text = getString(R.string.zoom_percent, settings.zoom)
    }

    private fun bindCard(card: RowActionCardBinding, iconRes: Int, title: String, onClick: () -> Unit) {
        card.cardIcon.setImageResource(iconRes)
        card.cardTitle.text = title
        card.cardValue.isVisible = false
        card.root.setOnClickListener { onClick() }
    }

    private fun themeLabel(value: String) = when (value) {
        "light" -> getString(R.string.theme_light)
        "dark" -> getString(R.string.theme_dark)
        else -> getString(R.string.theme_system)
    }

    private fun languageLabel(value: String) = when (value) {
        "ar" -> getString(R.string.language_ar)
        "en" -> getString(R.string.language_en)
        else -> getString(R.string.language_system)
    }

    private fun engineLabel(value: String) = when (value) {
        "google" -> getString(R.string.engine_google)
        "bing" -> getString(R.string.engine_bing)
        else -> getString(R.string.engine_duckduckgo)
    }

    private fun showThemeDialog() {
        val values = arrayOf("system", "light", "dark")
        val labels = arrayOf(
            getString(R.string.theme_system),
            getString(R.string.theme_light),
            getString(R.string.theme_dark)
        )
        AlertDialog.Builder(this)
            .setTitle(R.string.card_theme)
            .setItems(labels) { _, which ->
                lifecycleScope.launch {
                    settings.put("theme", values[which])
                    App.applyTheme(values[which])
                    refreshValues()
                }
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    private fun showLanguageDialog() {
        val values = arrayOf("system", "ar", "en")
        val labels = arrayOf(
            getString(R.string.language_system),
            getString(R.string.language_ar),
            getString(R.string.language_en)
        )
        AlertDialog.Builder(this)
            .setTitle(R.string.card_language)
            .setItems(labels) { _, which ->
                lifecycleScope.launch { settings.put("language", values[which]) }
                val locales = when (values[which]) {
                    "ar" -> androidx.core.os.LocaleListCompat.forLanguageTags("ar")
                    "en" -> androidx.core.os.LocaleListCompat.forLanguageTags("en")
                    else -> androidx.core.os.LocaleListCompat.getEmptyLocaleList()
                }
                androidx.appcompat.app.AppCompatDelegate.setApplicationLocales(locales)
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    private fun showSearchEngineDialog() {
        val values = arrayOf("duckduckgo", "google", "bing")
        val labels = values.map { engineLabel(it) }.toTypedArray()
        AlertDialog.Builder(this)
            .setTitle(R.string.card_search_engine)
            .setItems(labels) { _, which ->
                lifecycleScope.launch {
                    settings.put("search_engine", values[which])
                    refreshValues()
                }
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    private fun showHomepageDialog() {
        val options = arrayOf(
            getString(R.string.homepage_start),
            getString(R.string.address_hint)
        )
        AlertDialog.Builder(this)
            .setTitle(R.string.card_homepage)
            .setItems(options) { _, which ->
                if (which == 0) {
                    lifecycleScope.launch {
                        settings.put("homepage", "start")
                        refreshValues()
                    }
                } else {
                    val input = EditText(this).apply {
                        inputType = InputType.TYPE_TEXT_VARIATION_URI
                        hint = getString(R.string.add_website_hint)
                    }
                    AlertDialog.Builder(this)
                        .setTitle(R.string.card_homepage)
                        .setView(input)
                        .setPositiveButton(R.string.ok) { _, _ ->
                            val url = input.text?.toString()?.trim().orEmpty()
                            if (url.isNotBlank()) {
                                lifecycleScope.launch {
                                    settings.put("homepage", url)
                                    refreshValues()
                                }
                            }
                        }
                        .setNegativeButton(R.string.cancel, null)
                        .show()
                }
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    private fun showZoomDialog() {
        val options = intArrayOf(100, 125, 150, 175, 200)
        val labels = options.map { getString(R.string.zoom_percent, it) }.toTypedArray()
        val current = settings.zoom
        val checked = options.indexOfFirst { it >= current }.coerceAtLeast(0)
        AlertDialog.Builder(this)
            .setTitle(R.string.card_default_zoom)
            .setSingleChoiceItems(labels, checked) { dialog, which ->
                lifecycleScope.launch {
                    settings.putInt("zoom", options[which])
                    refreshValues()
                }
                dialog.dismiss()
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }
}
