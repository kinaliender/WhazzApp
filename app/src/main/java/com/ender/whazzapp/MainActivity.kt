package com.ender.whazzapp

import android.Manifest
import android.annotation.SuppressLint
import android.content.ActivityNotFoundException
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.provider.Settings
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.webkit.PermissionRequest
import android.webkit.ValueCallback
import android.webkit.WebChromeClient
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.PopupMenu
import android.widget.TextView
import android.widget.Toast
import androidx.activity.addCallback
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.updatePadding

class MainActivity : AppCompatActivity(), SessionHost {

    private lateinit var container: FrameLayout
    private lateinit var strip: View
    private lateinit var tabs: LinearLayout

    private var filePathCallback: ValueCallback<Array<Uri>>? = null
    private var pendingPermissionRequest: PermissionRequest? = null

    private val accountsListener: () -> Unit = { runOnUiThread { renderTabs() } }

    private val fileChooserLauncher =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
            val callback = filePathCallback ?: return@registerForActivityResult
            filePathCallback = null
            val data = result.data
            val uris: Array<Uri>? = when {
                result.resultCode != RESULT_OK -> null
                data?.clipData != null -> {
                    val clip = data.clipData!!
                    Array(clip.itemCount) { clip.getItemAt(it).uri }
                }
                data?.data != null -> arrayOf(data.data!!)
                else -> null
            }
            callback.onReceiveValue(uris)
        }

    private val mediaPermissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
            val request = pendingPermissionRequest ?: return@registerForActivityResult
            pendingPermissionRequest = null
            grantAllowedResources(request)
        }

    private val notificationPermissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { askBatteryOnce() }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        container = findViewById(R.id.container)
        strip = findViewById(R.id.strip)
        tabs = findViewById(R.id.tabs)
        applyInsets()

        AccountStore.backgroundEnabled = true
        KeepAliveService.start(this)

        findViewById<View>(R.id.addAccount).setOnClickListener { addAccount() }
        findViewById<View>(R.id.menu).setOnClickListener { showMenu(it) }
        AccountStore.addListener(accountsListener)

        showAccount(AccountStore.selectedId)
        handleNotificationIntent(intent)
        askNotificationPermission()

        // Geri tuşu: önce açık çekmece/sohbeti kapat, liste ekranındaysak uygulamayı arka plana al.
        onBackPressedDispatcher.addCallback(this) {
            val wv = SessionManager.webView(SessionManager.attachedId)
            if (wv == null) {
                moveTaskToBack(true)
                return@addCallback
            }
            wv.evaluateJavascript("window.__whazzBack ? window.__whazzBack() : false") { result ->
                if (result != "true") moveTaskToBack(true)
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleNotificationIntent(intent)
    }

    override fun onDestroy() {
        AccountStore.removeListener(accountsListener)
        SessionManager.detach(this)
        super.onDestroy()
    }

    // ---- Kenardan kenara düzen (targetSdk 35) -----------------------------------------------

    /** Durum çubuğu üstte, gezinme çubuğu/klavye altta yer kaplasın; WebView ikisinin arasında kalsın. */
    private fun applyInsets() {
        WindowCompat.getInsetsController(window, window.decorView).apply {
            isAppearanceLightStatusBars = false
            isAppearanceLightNavigationBars = false
        }
        val root = findViewById<View>(R.id.root)
        ViewCompat.setOnApplyWindowInsetsListener(root) { v, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout())
            val ime = insets.getInsets(WindowInsetsCompat.Type.ime())
            v.updatePadding(left = bars.left, top = bars.top, right = bars.right, bottom = maxOf(bars.bottom, ime.bottom))
            WindowInsetsCompat.CONSUMED
        }
    }

    // ---- Hesaplar ---------------------------------------------------------------------------

    private fun showAccount(accountId: String) {
        AccountStore.selectedId = accountId
        SessionManager.attach(this, this, container, accountId)
        renderTabs()
        updateStripVisibility()
    }

    private fun renderTabs() {
        tabs.removeAllViews()
        val selected = AccountStore.selectedId
        for (account in AccountStore.all()) {
            val isSelected = account.id == selected
            val tab = TextView(this).apply {
                text = account.name
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
                setTextColor(getColor(if (isSelected) R.color.wa_bg else R.color.wa_text))
                gravity = Gravity.CENTER
                setPadding(dp(14), 0, dp(14), 0)
                background = GradientDrawable().apply {
                    cornerRadius = dp(16).toFloat()
                    setColor(getColor(if (isSelected) R.color.wa_green else R.color.wa_panel))
                }
                setOnClickListener { if (account.id != AccountStore.selectedId) showAccount(account.id) }
                setOnLongClickListener { accountOptions(account); true }
            }
            tabs.addView(tab, LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, dp(32)).apply {
                marginEnd = dp(6)
            })
        }
        findViewById<View>(R.id.addAccount).visibility =
            if (SessionManager.multiProfileSupported) View.VISIBLE else View.GONE
    }

    private fun updateStripVisibility() {
        val open = SessionManager.isChatOpen(AccountStore.selectedId)
        strip.visibility = if (open) View.GONE else View.VISIBLE
    }

    override fun onChatModeChanged(accountId: String, open: Boolean) {
        if (accountId == SessionManager.attachedId) updateStripVisibility()
    }

    private fun addAccount() {
        if (!SessionManager.multiProfileSupported) {
            Toast.makeText(this, R.string.account_multi_unsupported, Toast.LENGTH_LONG).show()
            return
        }
        val input = nameInput(AccountStore.nextDefaultName())
        AlertDialog.Builder(this)
            .setTitle(R.string.account_add)
            .setMessage(R.string.account_add_info)
            .setView(padded(input))
            .setPositiveButton(R.string.ok) { _, _ ->
                val name = input.text.toString().trim().ifEmpty { AccountStore.nextDefaultName() }
                val account = AccountStore.add(name)
                Notifications.ensureChannel(account)
                showAccount(account.id)
                KeepAliveService.refresh(this)
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    private fun accountOptions(account: Account) {
        // Varsayılan profil silinemez (WebView kısıtı); o hesap yalnızca yeniden adlandırılabilir.
        val canRemove = account.id != AccountStore.DEFAULT_ID && AccountStore.all().size > 1
        val items = if (canRemove) arrayOf(getString(R.string.account_rename), getString(R.string.account_remove))
        else arrayOf(getString(R.string.account_rename))
        AlertDialog.Builder(this)
            .setTitle(account.name)
            .setItems(items) { _, which ->
                when (which) {
                    0 -> renameAccount(account)
                    1 -> removeAccount(account)
                }
            }
            .show()
    }

    private fun renameAccount(account: Account) {
        val input = nameInput(account.name)
        AlertDialog.Builder(this)
            .setTitle(R.string.account_rename)
            .setView(padded(input))
            .setPositiveButton(R.string.ok) { _, _ ->
                val name = input.text.toString().trim()
                if (name.isNotEmpty()) {
                    AccountStore.rename(account.id, name)
                    AccountStore.get(account.id)?.let { Notifications.ensureChannel(it) }
                }
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    private fun removeAccount(account: Account) {
        AlertDialog.Builder(this)
            .setTitle(R.string.account_remove)
            .setMessage(getString(R.string.account_remove_confirm, account.name))
            .setPositiveButton(R.string.remove) { _, _ ->
                SessionManager.destroy(account.id, deleteProfile = true)
                Notifications.deleteChannel(account.id)
                AccountStore.remove(account.id)
                showAccount(AccountStore.selectedId)
                KeepAliveService.refresh(this)
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    private fun nameInput(initial: String) = EditText(this).apply {
        setText(initial)
        setSelectAllOnFocus(true)
        hint = getString(R.string.account_add_hint)
        isSingleLine = true
    }

    private fun padded(view: View) = FrameLayout(this).apply {
        setPadding(dp(20), dp(8), dp(20), 0)
        addView(view)
    }

    private fun showMenu(anchor: View) {
        val popup = PopupMenu(this, anchor)
        if (!isIgnoringBatteryOptimizations()) popup.menu.add(0, 1, 0, R.string.menu_battery)
        popup.menu.add(0, 2, 1, R.string.menu_stop)
        popup.setOnMenuItemClickListener {
            when (it.itemId) {
                1 -> requestIgnoreBatteryOptimizations()
                2 -> startService(Intent(this, KeepAliveService::class.java).setAction(KeepAliveService.ACTION_STOP))
            }
            true
        }
        popup.show()
    }

    override fun onSessionsStopped() {
        finishAndRemoveTask()
    }

    // ---- Bildirimden açılış -----------------------------------------------------------------

    private fun handleNotificationIntent(intent: Intent?) {
        val accountId = intent?.getStringExtra(Notifications.EXTRA_ACCOUNT) ?: return
        val notifId = intent.getIntExtra(Notifications.EXTRA_NOTIF_ID, -1)
        intent.removeExtra(Notifications.EXTRA_ACCOUNT)
        if (AccountStore.get(accountId) == null) return
        if (accountId != SessionManager.attachedId) showAccount(accountId)
        if (notifId >= 0) {
            // WhatsApp'ın kendi notification.onclick'i ilgili sohbeti açar.
            SessionManager.webView(accountId)
                ?.evaluateJavascript("window.__whazzNotifClick && window.__whazzNotifClick($notifId)", null)
        }
    }

    // ---- SessionHost: dosya seçici ve izinler -----------------------------------------------

    override fun showFileChooser(
        callback: ValueCallback<Array<Uri>>,
        params: WebChromeClient.FileChooserParams
    ): Boolean {
        filePathCallback?.onReceiveValue(null)
        filePathCallback = callback
        val intent = params.createIntent().apply {
            if (params.mode == WebChromeClient.FileChooserParams.MODE_OPEN_MULTIPLE) {
                putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true)
            }
        }
        return try {
            fileChooserLauncher.launch(intent)
            true
        } catch (e: ActivityNotFoundException) {
            filePathCallback = null
            false
        }
    }

    override fun requestMediaPermission(request: PermissionRequest) {
        val needed = mutableListOf<String>()
        if (PermissionRequest.RESOURCE_AUDIO_CAPTURE in request.resources &&
            !hasPermission(Manifest.permission.RECORD_AUDIO)
        ) needed += Manifest.permission.RECORD_AUDIO
        if (PermissionRequest.RESOURCE_VIDEO_CAPTURE in request.resources &&
            !hasPermission(Manifest.permission.CAMERA)
        ) needed += Manifest.permission.CAMERA

        if (needed.isEmpty()) {
            grantAllowedResources(request)
        } else {
            pendingPermissionRequest = request
            mediaPermissionLauncher.launch(needed.toTypedArray())
        }
    }

    private fun hasPermission(p: String) =
        ContextCompat.checkSelfPermission(this, p) == PackageManager.PERMISSION_GRANTED

    private fun grantAllowedResources(request: PermissionRequest) {
        val allowed = request.resources.filter {
            when (it) {
                PermissionRequest.RESOURCE_AUDIO_CAPTURE -> hasPermission(Manifest.permission.RECORD_AUDIO)
                PermissionRequest.RESOURCE_VIDEO_CAPTURE -> hasPermission(Manifest.permission.CAMERA)
                else -> false
            }
        }
        if (allowed.isEmpty()) request.deny() else request.grant(allowed.toTypedArray())
    }

    // ---- İzinler: bildirim, pil -------------------------------------------------------------

    private fun askNotificationPermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            !hasPermission(Manifest.permission.POST_NOTIFICATIONS)
        ) notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
        else askBatteryOnce()
    }

    private fun askBatteryOnce() {
        if (AccountStore.askedBattery || isIgnoringBatteryOptimizations()) return
        AccountStore.askedBattery = true
        requestIgnoreBatteryOptimizations()
    }

    private fun isIgnoringBatteryOptimizations() =
        getSystemService(PowerManager::class.java).isIgnoringBatteryOptimizations(packageName)

    @SuppressLint("BatteryLife") // Mağaza dışı kişisel uygulama; sürekli bağlantı asıl amaç.
    private fun requestIgnoreBatteryOptimizations() {
        try {
            startActivity(
                Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:$packageName"))
            )
        } catch (_: ActivityNotFoundException) {
            startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS))
        }
    }

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()
}
