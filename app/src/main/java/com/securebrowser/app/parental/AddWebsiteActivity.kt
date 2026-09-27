package com.securebrowser.app.parental

import android.os.Bundle
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.isVisible
import androidx.lifecycle.lifecycleScope
import com.securebrowser.app.R
import com.securebrowser.app.core.url.NormalizeResult
import com.securebrowser.app.core.url.UrlNormalizer
import com.securebrowser.app.data.repository.AddOption
import com.securebrowser.app.databinding.ActivityAddWebsiteBinding
import com.securebrowser.app.di.ServiceLocator
import kotlinx.coroutines.launch

/**
 * قسم Security → Add Website (§7):
 * إضافة مباشرة بالخيارات الثلاثة، أو مسار بديل عبر الوصول المؤقت:
 * الوالد يفتح نافذة → يزور المواقع → يضيفها من السجل.
 */
class AddWebsiteActivity : AppCompatActivity() {

    private lateinit var binding: ActivityAddWebsiteBinding

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityAddWebsiteBinding.inflate(layoutInflater)
        setContentView(binding.root)

        binding.btnToolbarBack.setOnClickListener { finish() }

        binding.siteInput.setOnTextChanged { text ->
            binding.normalizedPreview.text = normalizePreview(text)
        }

        binding.btnAddRule.setOnClickListener {
            val input = binding.siteInput.text?.toString().orEmpty().trim()
            if (input.isBlank()) {
                Toast.makeText(this, R.string.add_whitelist_error, Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            val option = when {
                binding.optSiteOnly.isChecked -> AddOption.SITE_ONLY
                binding.optAllPaths.isChecked -> AddOption.ALL_PATHS
                else -> AddOption.WITH_SUBDOMAINS
            }
            lifecycleScope.launch {
                // إضافة عبر المضيف المستخرج من أي صيغة مدخلة
                val host = (UrlNormalizer.normalize(
                    if (input.contains("://")) input else "https://$input"
                ) as? NormalizeResult.Success)?.url?.host
                if (host.isNullOrBlank()) {
                    Toast.makeText(this@AddWebsiteActivity, R.string.add_whitelist_error, Toast.LENGTH_LONG).show()
                    return@launch
                }
                ServiceLocator.whiteListRepository.addSiteRule(host, option)
                ServiceLocator.whiteListEngine.refresh()
                Toast.makeText(this@AddWebsiteActivity, R.string.add_whitelist_done, Toast.LENGTH_LONG).show()
                finish()
            }
        }

        binding.btnTempAccessPath.setOnClickListener {
            startActivity(android.content.Intent(this, TempAccessActivity::class.java))
        }
    }

    private fun normalizePreview(text: String): String {
        if (text.isBlank()) return ""
        val result = UrlNormalizer.normalize(if (text.contains("://")) text else "https://$text")
        return (result as? NormalizeResult.Success)?.url?.host?.let { host ->
            getString(R.string.site_info_domain) + ": " + host
        } ?: getString(R.string.add_whitelist_error)
    }
}

private fun com.google.android.material.textfield.TextInputEditText.setOnTextChanged(
    listener: (String) -> Unit
) {
    addTextChangedListener(object : android.text.TextWatcher {
        override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) = Unit
        override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) = Unit
        override fun afterTextChanged(s: android.text.Editable?) = listener(s?.toString().orEmpty())
    })
}
