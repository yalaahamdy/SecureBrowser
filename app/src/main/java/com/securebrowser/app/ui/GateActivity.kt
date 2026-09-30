package com.securebrowser.app.ui

import android.content.Intent
import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.securebrowser.app.R
import com.securebrowser.app.browser.BrowserActivity
import com.securebrowser.app.di.ServiceLocator
import kotlinx.coroutines.launch

/**
 * بوابة الدخول: تشغيل أول → إنشاء رمز الوالدين، وإلا → المتصفح مباشرة.
 */
class GateActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_gate)

        lifecycleScope.launch {
            // تسخين محرك الأمان قبل أول تنقل
            ServiceLocator.whiteListEngine.refresh()
            val target = if (ServiceLocator.parentalAuthManager.hasPin()) {
                BrowserActivity::class.java
            } else {
                SetupPinActivity::class.java
            }
            val forwardIntent = Intent(this@GateActivity, target).apply {
                action = intent?.action
                data = intent?.data
                intent?.extras?.let { putExtras(it) }
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
            }
            startActivity(forwardIntent)
            finish()
        }
    }
}
