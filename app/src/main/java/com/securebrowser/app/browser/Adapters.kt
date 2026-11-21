package com.securebrowser.app.browser

import android.text.format.DateUtils
import android.view.LayoutInflater
import android.view.ViewGroup
import android.widget.Toast
import androidx.core.view.isVisible
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.color.MaterialColors
import com.securebrowser.app.R
import com.securebrowser.app.data.db.entity.HistoryEntity
import com.securebrowser.app.data.repository.HistoryRepository
import com.securebrowser.app.databinding.ItemHistoryRowBinding
import com.securebrowser.app.databinding.ItemSiteCardBinding
import com.securebrowser.app.databinding.ItemTabCardBinding
import com.securebrowser.app.databinding.ItemWallpaperCardBinding
import com.securebrowser.app.databinding.ItemYoutubeStartCardBinding
import com.securebrowser.app.security.whitelist.WhiteListRule
import com.securebrowser.app.ui.FaviconLoader
import kotlinx.coroutines.launch
import java.util.Calendar
import java.util.concurrent.TimeUnit

/**
 * محول لوحة التبويبات — v1.5.0 إعادة كتابة كاملة:
 *
 * **المزامنة الحية (إصلاح جوهري):** كانت اللوحة في v1.4.0 تحتفظ **بلقطة ثابتة**
 * من قائمة التبويبات لحظة الفتح (tabsManager.tabs تعيد نسخة)، فاستدعاء
 * notifyDataSetChanged بعد إغلاق تبويب كان يعيد رسم نفس اللقطة القديمة —
 * لذلك كان المستخدم مضطرًا لإغلاق اللوحة وإعادة فتحها لرؤية التغيير. الآن
 * المحول يعيد المزامنة من [TabsManager] نفسه عبر [sync]، واللوحة تسجّل
 * مستمعًا حيًّا على onTabsChanged ما دامت مفتوحة — كل إغلاق/إضافة/تبديل
 * يظهر فورًا دون لمس اللوحة.
 *
 * **الإزالة المتحركة:** عند حذف عنصر واحد تُستخدم notifyItemRemoved فتنزلق
 * البطاقة بأنيميشن RecyclerView بدل وميض الشبكة كاملة.
 *
 * **تحديد التبويب النشط:** حد بلون الهوية + ظل + شارة "نشط" على البطاقة،
 * والبقية بحد شفاف خفيف — تمييز بصرية فوري.
 *
 * **إصلاح المعاينة:** كان الحرف البديل (tabInitial) مرسومًا دائمًا فوق
 * المعاينة بخلفية معتمة فلا تظهر لقطة الصفحة إطلاقًا — أصبح يظهر أحدهما
 * فقط حسب توفر المعاينة.
 */
class TabsGridAdapter(
    private val tabsProvider: () -> List<TabSession>,
    private val activeIdProvider: () -> Long?,
    private val onSwitch: (Long) -> Unit,
    private val onClose: (Long) -> Unit
) : RecyclerView.Adapter<TabsGridAdapter.Holder>() {

    private val items = mutableListOf<TabSession>()

    init {
        items.addAll(tabsProvider())
    }

    class Holder(val binding: ItemTabCardBinding) : RecyclerView.ViewHolder(binding.root)

    /**
     * إعادة مزامنة العناصر من المدير الحي.
     * - إزالة عنصر واحد → notifyItemRemoved (أنيميشن انزلاق).
     * - نفس المعرفات بترتيب ثابت → تحديث النطاق (حدود النشط/العناوين).
     * - أي تغيير آخر → رسم كامل.
     */
    fun sync() {
        val fresh = tabsProvider()
        if (fresh.size == items.size - 1) {
            var removedAt = items.size - 1
            for (i in fresh.indices) {
                if (items[i].id != fresh[i].id) {
                    removedAt = i
                    break
                }
            }
            items.clear()
            items.addAll(fresh)
            notifyItemRemoved(removedAt)
            if (removedAt < items.size) {
                notifyItemRangeChanged(removedAt, items.size - removedAt)
            }
            return
        }
        val stable = fresh.size == items.size &&
            items.indices.all { items[it].id == fresh[it].id }
        items.clear()
        items.addAll(fresh)
        if (stable) {
            if (items.isNotEmpty()) notifyItemRangeChanged(0, items.size)
        } else {
            notifyDataSetChanged()
        }
    }

    /** موضع تبويب بالمعرف داخل القائمة الحية — -1 إن لم يوجد. */
    fun positionOf(id: Long): Int = items.indexOfFirst { it.id == id }

    override fun getItemCount(): Int = items.size

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): Holder {
        val binding = ItemTabCardBinding.inflate(LayoutInflater.from(parent.context), parent, false)
        return Holder(binding)
    }

    override fun onBindViewHolder(holder: Holder, position: Int) {
        val tab = items[position]
        val state = tab.uiState
        val b = holder.binding
        val ctx = b.root.context

        val title = state.title?.takeIf { it.isNotBlank() }
            ?: state.url?.takeIf { it.isNotBlank() }
            ?: tab.pendingRestoreUrl
            ?: ctx.getString(R.string.start_title)
        b.tabTitle.text = title

        // المعاينة أو البديل — أحدهما فقط (كان البديل يغطي المعاينة دائمًا)
        val preview = tab.preview
        if (preview != null && !preview.isRecycled) {
            b.tabPreview.isVisible = true
            b.tabFallback.isVisible = false
            b.tabPreview.setImageBitmap(preview)
        } else {
            b.tabPreview.isVisible = false
            b.tabFallback.isVisible = true
            b.tabPreview.setImageDrawable(null)
            val host = initialHostOf(state.url ?: tab.pendingRestoreUrl)
            b.tabInitial.text = (host ?: title).trim().firstOrNull()?.uppercaseChar()?.toString() ?: "S"
            // v1.7.0 — أيقونة الموقع الحقيقية كبديل أنيق حتى تُلتقط أول معاينة
            b.tabFavicon.tag = host
            b.tabFavicon.isVisible = false
            b.tabInitial.isVisible = true
            if (!host.isNullOrBlank()) {
                (ctx as? androidx.lifecycle.LifecycleOwner)?.lifecycleScope?.launch {
                    val bmp = com.securebrowser.app.ui.FaviconLoader.load(ctx, host)
                    if (b.tabFavicon.tag != host) return@launch
                    if (bmp != null) {
                        b.tabFavicon.isVisible = true
                        b.tabFavicon.setImageBitmap(bmp)
                        b.tabInitial.isVisible = false
                    }
                }
            }
        }

        // تحديد احترافي للتبويب النشط: حد أزرق + ظل + شارة، والبقية هادئة
        val isActive = tab.id == activeIdProvider()
        if (isActive) {
            b.tabCard.strokeWidth = dp(ctx, 2)
            b.tabCard.strokeColor =
                MaterialColors.getColor(b.tabCard, androidx.appcompat.R.attr.colorPrimary)
            b.tabCard.cardElevation = dp(ctx, 4).toFloat()
            b.activeBadge.isVisible = true
            b.tabCard.alpha = 1f
        } else {
            b.tabCard.strokeWidth = dp(ctx, 1)
            b.tabCard.strokeColor =
                MaterialColors.getColor(b.tabCard, com.google.android.material.R.attr.colorOutline)
            b.tabCard.cardElevation = 0f
            b.activeBadge.isVisible = false
            b.tabCard.alpha = 0.95f
        }

        b.root.setOnClickListener { onSwitch(tab.id) }
        b.btnCloseTab.setOnClickListener { onClose(tab.id) }
    }

    private fun dp(context: android.content.Context, value: Int): Int =
        (value * context.resources.displayMetrics.density).toInt()

    /** استخراج المضيف من رابط خام — للبديل الحرفي ولأيقونة الموقع. */
    private fun initialHostOf(url: String?): String? {
        url ?: return null
        val r = com.securebrowser.app.core.url.UrlNormalizer.normalize(url)
        return (r as? com.securebrowser.app.core.url.NormalizeResult.Success)?.url?.host
    }
}

/**
 * محول صفوف السجل — بادئة ترويسة مجموعة (اليوم/أمس/هذا الأسبوع/أقدم) تُدار من الطرف المستدعي.
 */
class HistoryRowAdapter(
    private val entries: List<HistoryEntity>,
    private val showDelete: Boolean,
    private val onOpen: (HistoryEntity) -> Unit,
    private val onMore: (HistoryEntity) -> Unit,
    private val onDelete: ((HistoryEntity) -> Unit)? = null
) : RecyclerView.Adapter<HistoryRowAdapter.Holder>() {

    class Holder(val binding: ItemHistoryRowBinding) : RecyclerView.ViewHolder(binding.root)

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): Holder {
        val binding = ItemHistoryRowBinding.inflate(LayoutInflater.from(parent.context), parent, false)
        return Holder(binding)
    }

    override fun getItemCount(): Int = entries.size

    override fun onBindViewHolder(holder: Holder, position: Int) {
        val entry = entries[position]
        val ctx = holder.binding.root.context
        val title = entry.title?.takeIf { it.isNotBlank() } ?: entry.host ?: entry.url
        holder.binding.historyTitle.text = title
        val letter = (entry.host ?: title).trim().firstOrNull()?.uppercaseChar()?.toString() ?: "?"
        holder.binding.historyTile.text = letter
        // v1.7.0 — أيقونة الموقع الحقيقية بدل حرف البديل عند توفرها
        FaviconLoader.bind(
            holder.binding.root, holder.binding.historyIcon, holder.binding.historyTile,
            entry.host, letter
        )

        val relative = DateUtils.getRelativeTimeSpanString(
            entry.timestamp, System.currentTimeMillis(), DateUtils.MINUTE_IN_MILLIS
        )
        holder.binding.historySubtitle.text = buildString {
            append(entry.host ?: entry.url.take(60))
            append(" — ")
            append(relative)
            if (entry.visitResult == HistoryRepository.RESULT_TEMPORARY) {
                append(" • ")
                append(ctx.getString(R.string.history_temp_badge))
            }
        }
        holder.binding.historySubtitle.isVisible = true

        holder.binding.root.setOnClickListener { onOpen(entry) }
        holder.binding.btnMore.isVisible = true
        holder.binding.btnMore.setOnClickListener { onMore(entry) }
        holder.binding.root.setOnLongClickListener {
            if (showDelete && onDelete != null) {
                onDelete.invoke(entry)
                true
            } else false
        }
    }
}

/**
 * تجميع السجل زمنيًا (§9): اليوم / أمس / هذا الأسبوع / أقدم.
 */
object HistoryGrouper {

    enum class Group { TODAY, YESTERDAY, THIS_WEEK, OLDER }

    fun groupOf(timestamp: Long, now: Long = System.currentTimeMillis()): Group {
        val cal = Calendar.getInstance()
        cal.timeInMillis = now
        cal.set(Calendar.HOUR_OF_DAY, 0)
        cal.set(Calendar.MINUTE, 0)
        cal.set(Calendar.SECOND, 0)
        cal.set(Calendar.MILLISECOND, 0)
        val todayStart = cal.timeInMillis
        return when {
            timestamp >= todayStart -> Group.TODAY
            timestamp >= todayStart - TimeUnit.DAYS.toMillis(1) -> Group.YESTERDAY
            timestamp >= todayStart - TimeUnit.DAYS.toMillis(7) -> Group.THIS_WEEK
            else -> Group.OLDER
        }
    }

    /** يبني قائمة (ترويسة | عنصر) بترتيب الأحدث أولًا.
     *
     * v1.7.0 — منع التكرارات في العرض: الزيارات المتتالية لنفس الرابط
     * (إعادة تحميل، تنقلات SPA، عودة من صفحة فرعية) تُدمج في صف واحد
     * حتى لا يظهر السجل قائمة مكرّرة مملّة — الديمومة معالجة أيضًا عند
     * التسجيل في [HistoryRepository] بنافذة زمنية.
     */
    fun buildRows(
        entries: List<HistoryEntity>,
        headerFor: (Group) -> String
    ): List<Row> {
        val deduped = mutableListOf<HistoryEntity>()
        for (entry in entries) {
            if (deduped.lastOrNull()?.url == entry.url) continue
            deduped.add(entry)
        }
        val groups = deduped.groupBy { groupOf(it.timestamp) }
        val order = listOf(Group.TODAY, Group.YESTERDAY, Group.THIS_WEEK, Group.OLDER)
        val rows = mutableListOf<Row>()
        for (g in order) {
            val list = groups[g] ?: continue
            if (list.isEmpty()) continue
            rows.add(Row.Header(headerFor(g)))
            list.forEach { rows.add(Row.Item(it)) }
        }
        return rows
    }

    sealed class Row {
        class Header(val title: String) : Row()
        class Item(val entry: HistoryEntity) : Row()
    }
}

/**
 * شبكة مواقع صفحة البداية (v1.7.0) — بطاقة لكل موقع مسموح بأيقونة حقيقية
 * (Favicon) بدل الأزرار النصية. ضغطة = فتح الموقع عبر بوابة الأمان،
 * ضغطة طويلة = إزالة من القائمة (القرار والتنفيذ في BrowserActivity).
 */
class StartSitesAdapter(
    private val rules: List<WhiteListRule>,
    private val onOpen: (String) -> Unit,
    private val onRemove: (WhiteListRule) -> Unit
) : RecyclerView.Adapter<StartSitesAdapter.Holder>() {

    class Holder(val binding: ItemSiteCardBinding) : RecyclerView.ViewHolder(binding.root)

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): Holder =
        Holder(ItemSiteCardBinding.inflate(LayoutInflater.from(parent.context), parent, false))

    override fun getItemCount(): Int = rules.size

    override fun onBindViewHolder(holder: Holder, position: Int) {
        val rule = rules[position]
        val b = holder.binding
        b.siteHost.text = rule.host.removePrefix("www.")
        val letter = FaviconLoader.initialFor(rule.host)
        b.siteLetter.text = letter
        FaviconLoader.bind(b.root, b.siteIcon, b.siteLetter, rule.host, letter)
        b.root.setOnClickListener { onOpen(rule.host) }
        b.root.setOnLongClickListener { onRemove(rule); true }
    }
}

/**
 * شبكة قنوات يوتيوب المسموحة في صفحة البداية (v1.16.0) —
 * بطاقات احترافية بعمودين تعرض صورة القناة الدائرية الحقيقية، اسم القناة، و@handle.
 */
class StartYoutubeChannelsAdapter(
    private val channels: List<com.securebrowser.app.data.db.entity.YoutubeChannelEntity>,
    private val onOpen: (com.securebrowser.app.data.db.entity.YoutubeChannelEntity) -> Unit
) : RecyclerView.Adapter<StartYoutubeChannelsAdapter.Holder>() {

    class Holder(val binding: ItemYoutubeStartCardBinding) : RecyclerView.ViewHolder(binding.root)

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): Holder =
        Holder(ItemYoutubeStartCardBinding.inflate(LayoutInflater.from(parent.context), parent, false))

    override fun getItemCount(): Int = channels.size

    override fun onBindViewHolder(holder: Holder, position: Int) {
        val ch = channels[position]
        val b = holder.binding
        b.ytChannelTitle.text = ch.title
        b.ytChannelHandle.text = ch.handle ?: ch.channelId
        val letter = ch.title.trim().firstOrNull()?.uppercaseChar()?.toString() ?: "Y"
        FaviconLoader.bindCircleUrl(
            hostView = b.root,
            icon = b.ytAvatarImage,
            letter = b.ytAvatarLetter,
            imageUrl = ch.avatarUrl,
            fallbackLetter = letter
        )
        b.root.setOnClickListener { onOpen(ch) }
    }
}

/** كتالوج خلفيات الصفحة الرئيسية الاحترافية (v1.16.0). */
object StartWallpapers {
    data class Spec(val key: String, val titleRes: Int, val drawableRes: Int)

    val ALL = listOf(
        Spec("ocean", R.string.wallpaper_ocean, R.drawable.bg_start_ocean),
        Spec("aurora", R.string.wallpaper_aurora, R.drawable.bg_start_aurora),
        Spec("sunset", R.string.wallpaper_sunset, R.drawable.bg_start_sunset),
        Spec("emerald", R.string.wallpaper_emerald, R.drawable.bg_start_emerald),
        Spec("midnight", R.string.wallpaper_midnight, R.drawable.bg_start_midnight),
        Spec("minimal", R.string.wallpaper_minimal, R.drawable.bg_start_minimal)
    )

    fun specFor(key: String?): Spec = ALL.firstOrNull { it.key == key } ?: ALL.first()

    fun drawableFor(key: String?): Int = specFor(key).drawableRes

    fun labelResFor(key: String?): Int = specFor(key).titleRes

    fun showPicker(
        activity: androidx.appcompat.app.AppCompatActivity,
        currentKey: String,
        onPick: (Spec) -> Unit
    ) {
        val pickerBinding = com.securebrowser.app.databinding.DialogWallpaperPickerBinding.inflate(activity.layoutInflater)
        pickerBinding.wallpaperRecycler.layoutManager =
            androidx.recyclerview.widget.GridLayoutManager(activity, 2)
        val dialog = com.google.android.material.dialog.MaterialAlertDialogBuilder(activity)
            .setTitle(R.string.wallpaper_picker_title)
            .setView(pickerBinding.root)
            .setNegativeButton(R.string.close, null)
            .create()
        pickerBinding.wallpaperRecycler.adapter = WallpaperPickerAdapter(currentKey) { spec ->
            onPick(spec)
            dialog.dismiss()
        }
        dialog.show()
    }
}

class WallpaperPickerAdapter(
    private val selectedKey: String,
    private val onPick: (StartWallpapers.Spec) -> Unit
) : RecyclerView.Adapter<WallpaperPickerAdapter.Holder>() {

    class Holder(val binding: ItemWallpaperCardBinding) : RecyclerView.ViewHolder(binding.root)

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): Holder =
        Holder(ItemWallpaperCardBinding.inflate(LayoutInflater.from(parent.context), parent, false))

    override fun getItemCount(): Int = StartWallpapers.ALL.size

    override fun onBindViewHolder(holder: Holder, position: Int) {
        val spec = StartWallpapers.ALL[position]
        val b = holder.binding
        val selected = spec.key == selectedKey
        b.wallpaperPreview.setBackgroundResource(spec.drawableRes)
        b.wallpaperName.setText(spec.titleRes)
        b.wallpaperCheck.isVisible = selected
        b.wallpaperCard.strokeColor = MaterialColors.getColor(
            b.wallpaperCard,
            if (selected) androidx.appcompat.R.attr.colorPrimary else com.google.android.material.R.attr.colorOutlineVariant
        )
        val density = b.root.resources.displayMetrics.density
        b.wallpaperCard.strokeWidth = ((if (selected) 2.5f else 1f) * density).toInt()
        b.root.setOnClickListener { onPick(spec) }
    }
}
