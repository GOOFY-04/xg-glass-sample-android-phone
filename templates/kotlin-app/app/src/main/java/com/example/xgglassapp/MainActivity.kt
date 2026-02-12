package com.example.xgglassapp

import android.Manifest
import android.content.Context
import android.graphics.BitmapFactory
import android.os.Bundle
import android.text.InputType
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.EditText
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.Spinner
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.universalglasses.appcontract.HostEnvironment
import com.universalglasses.appcontract.HostKind
import com.universalglasses.appcontract.UniversalAppContext
import com.universalglasses.appcontract.UniversalAppEntry
import com.universalglasses.appcontract.UserSettingField
import com.universalglasses.appcontract.UserSettingInputType
import com.universalglasses.appcontract.commandsWithDefaults
import com.universalglasses.core.ConnectionState
import com.universalglasses.core.GlassesEvent
import com.universalglasses.core.GlassesModel
import com.universalglasses.device.frame.embedded.EmbeddedFrameGlassesClient
import com.universalglasses.device.rayneo.installer.RayNeoApkSource
import com.universalglasses.device.rayneo.installer.RayNeoInstallerConfig
import com.universalglasses.device.rayneo.installer.RayNeoInstallerGlassesClient
import com.universalglasses.device.rokid.RokidGlassesClient
import com.universalglasses.device.sim.EmulatorGlassesClient
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.withContext

class MainActivity : AppCompatActivity() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    private lateinit var tvStatus: TextView
    private lateinit var tvLog: TextView
    private lateinit var spDevice: Spinner
    private lateinit var btnConnect: Button
    private lateinit var ivPreview: ImageView
    private lateinit var tvDisplay: TextView
    private lateinit var etRayNeoIp: EditText
    private lateinit var llCommands: LinearLayout
    private lateinit var tvSettingsTitle: TextView
    private lateinit var llSettings: LinearLayout
    private lateinit var btnApplySettings: Button

    private val entry: UniversalAppEntry? by lazy { loadEntryOrNull() }

    /** Map of setting key → EditText widget, populated by [renderSettings]. */
    private val settingEdits = mutableMapOf<String, EditText>()

    /** Current applied settings (key → value). */
    private var appliedSettings: Map<String, String> = emptyMap()

    private var client: com.universalglasses.core.GlassesClient? = null
    private var connectJob: Job? = null
    private var stateJob: Job? = null
    private var eventsJob: Job? = null
    private var pendingConnectModel: GlassesModel? = null

    private val requestPermissionsLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { result ->
        val allGranted = result.values.all { it }
        if (!allGranted) {
            appendLog("Permissions denied; cannot connect.")
            tvStatus.text = "Status: permissions denied"
            pendingConnectModel = null
            return@registerForActivityResult
        }
        val model = pendingConnectModel
        pendingConnectModel = null
        if (model != null) connect(model)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        tvStatus = findViewById(R.id.tvStatus)
        tvLog = findViewById(R.id.tvLog)
        spDevice = findViewById(R.id.spDevice)
        btnConnect = findViewById(R.id.btnConnect)
        ivPreview = findViewById(R.id.ivPreview)
        tvDisplay = findViewById(R.id.tvDisplay)
        etRayNeoIp = findViewById(R.id.etRayNeoIp)
        llCommands = findViewById(R.id.llCommands)
        tvSettingsTitle = findViewById(R.id.tvSettingsTitle)
        llSettings = findViewById(R.id.llSettings)
        btnApplySettings = findViewById(R.id.btnApplySettings)

        val deviceItems = if (BuildConfig.XG_SIMULATOR) {
            listOf("SIMULATOR")
        } else {
            listOf("SIMULATOR", "ROKID", "FRAME", "RAYNEO")
        }
        spDevice.adapter = ArrayAdapter(
            this,
            android.R.layout.simple_spinner_dropdown_item,
            deviceItems,
        )

        spDevice.onItemSelectedListener = object : android.widget.AdapterView.OnItemSelectedListener {
            override fun onItemSelected(
                parent: android.widget.AdapterView<*>?,
                view: android.view.View?,
                position: Int,
                id: Long
            ) {
                val selected = spDevice.selectedItem?.toString() ?: "ROKID"
                etRayNeoIp.visibility =
                    if (selected == "RAYNEO") android.view.View.VISIBLE else android.view.View.GONE
            }

            override fun onNothingSelected(parent: android.widget.AdapterView<*>?) {}
        }

        btnConnect.setOnClickListener {
            val selected = spDevice.selectedItem?.toString() ?: "ROKID"
            val model = when (selected) {
                "SIMULATOR" -> GlassesModel.SIMULATOR
                "FRAME" -> GlassesModel.FRAME
                "RAYNEO" -> GlassesModel.RAYNEO
                else -> GlassesModel.ROKID
            }
            ensurePermissionsThenConnect(model)
        }

        renderSettings()

        btnApplySettings.setOnClickListener { applySettings() }

        renderCommandsForCurrentSelection(connected = false)

        if (BuildConfig.XG_SIMULATOR) {
            // Simulator builds are meant to run on an emulator; auto-connect.
            ensurePermissionsThenConnect(GlassesModel.SIMULATOR)
        }
    }

    private fun connect(model: GlassesModel) {
        connectJob?.cancel()
        connectJob = scope.launch {
            btnConnect.isEnabled = false
            tvStatus.text = "Status: switching to ${model.name}..."

            // Disconnect previous client FIRST (sequentially) so we don't accidentally destroy the new one.
            val old = client
            client = null
            try {
                old?.disconnect()
            } catch (e: Exception) {
                appendLog("WARN: disconnect previous client failed: ${e.message}")
            }

            val newClient = when (model) {
                GlassesModel.SIMULATOR -> EmulatorGlassesClient(this@MainActivity) { text ->
                    tvDisplay.text = text
                }
                GlassesModel.ROKID -> createRokidClient()
                GlassesModel.FRAME -> {
                // SDK-owned Flutter engine + bridge
                EmbeddedFrameGlassesClient(this@MainActivity)
                }
                GlassesModel.RAYNEO -> {
                    val host = etRayNeoIp.text?.toString()?.trim().orEmpty()
                    if (host.isBlank()) {
                        appendLog("RayNeo: please input glasses IP address first.")
                        tvStatus.text = "Status: RayNeo IP missing"
                        btnConnect.isEnabled = true
                        return@launch
                    }
                    RayNeoInstallerGlassesClient(
                        context = this@MainActivity,
                        config = RayNeoInstallerConfig(
                            host = host,
                            apk = RayNeoApkSource.Asset("rayneo_glass_app.apk"),
                        )
                    )
                }
            }
            client = newClient

            // Restart collectors for the new client.
            stateJob?.cancel()
            eventsJob?.cancel()

            stateJob = launch {
                newClient.state.collectLatest { st ->
                    tvStatus.text = "Status: $st"
                    val connected = st is ConnectionState.Connected
                    renderCommandsForCurrentSelection(connected = connected)
                }
            }

            eventsJob = launch {
                newClient.events.collectLatest { ev ->
                    when (ev) {
                        is GlassesEvent.Log -> appendLog(ev.message)
                        is GlassesEvent.Warning -> appendLog("WARN: ${ev.message}")
                        is GlassesEvent.Tap -> appendLog("TAP: ${ev.count}")
                    }
                }
            }

            val r = newClient.connect()
            appendLog("connect(${model.name}) => ${r.isSuccess} ${r.exceptionOrNull()?.message ?: ""}")

            // After successful RayNeo install, push the current user settings to the glasses.
            if (r.isSuccess && newClient is RayNeoInstallerGlassesClient && appliedSettings.isNotEmpty()) {
                val pushR = newClient.pushUserSettings(appliedSettings)
                if (pushR.isSuccess) appendLog("Settings synced to RayNeo glasses.")
                else appendLog("Settings sync failed: ${pushR.exceptionOrNull()?.message}")
            }

            btnConnect.isEnabled = true
        }
    }

    private fun createRokidClient(): RokidGlassesClient {
        val secret = BuildConfig.ROKID_CLIENT_SECRET.trim()
        val rawName = BuildConfig.ROKID_SN_RAW_NAME.trim()

        val auth = loadRokidAuthorizationOrNull(
            rawName = rawName,
            clientSecret = secret,
        )
        if (auth == null) {
            appendLog(
                "Rokid: SN auth missing. Put your .lc under app/src/main/res/raw/ and set in local.properties:\n" +
                    "  rokid.clientSecret=<your-client-secret>\n" +
                    "  rokid.snRawName=<raw_resource_name_without_extension>\n" +
                    "Or use env vars ROKID_CLIENT_SECRET / ROKID_SN_RAW_NAME."
            )
        }

        return RokidGlassesClient(
            this,
            RokidGlassesClient.RokidOptions(authorization = auth),
        )
    }

    private fun loadRokidAuthorizationOrNull(
        rawName: String,
        clientSecret: String,
    ): RokidGlassesClient.RokidAuthorization? {
        if (rawName.isBlank() || clientSecret.isBlank()) return null

        // rawName is the resource entry name without extension, e.g. "sn_0a98..." for res/raw/sn_0a98....lc
        val resId = resources.getIdentifier(rawName, "raw", packageName)
        if (resId == 0) return null

        val bytes = try {
            resources.openRawResource(resId).use { it.readBytes() }
        } catch (_: Exception) {
            ByteArray(0)
        }
        if (bytes.isEmpty()) return null

        return RokidGlassesClient.RokidAuthorization(
            snLc = bytes,
            clientSecret = clientSecret,
        )
    }

    private fun ensurePermissionsThenConnect(model: GlassesModel) {
        val required = requiredPermissionsFor(model)
        val missing = required.filter { perm ->
            ContextCompat.checkSelfPermission(this, perm) != android.content.pm.PackageManager.PERMISSION_GRANTED
        }
        if (missing.isEmpty()) {
            connect(model)
            return
        }
        pendingConnectModel = model
        appendLog("Requesting permissions: ${missing.joinToString()}")
        requestPermissionsLauncher.launch(missing.toTypedArray())
    }

    private fun requiredPermissionsFor(model: GlassesModel): List<String> {
        val perms = mutableListOf<String>()

        if (model == GlassesModel.SIMULATOR) {
            perms += Manifest.permission.CAMERA
            perms += Manifest.permission.RECORD_AUDIO
        }

        // BLE permissions (Frame + Rokid only)
        if (model == GlassesModel.ROKID || model == GlassesModel.FRAME) {
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.S) {
                perms += Manifest.permission.BLUETOOTH_SCAN
                perms += Manifest.permission.BLUETOOTH_CONNECT
            } else {
                perms += Manifest.permission.ACCESS_FINE_LOCATION
                perms += Manifest.permission.BLUETOOTH
                perms += Manifest.permission.BLUETOOTH_ADMIN
            }
        }

        // Rokid needs Wi‑Fi P2P on Android 13+
        if (model == GlassesModel.ROKID && android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU) {
            perms += Manifest.permission.NEARBY_WIFI_DEVICES
        }

        return perms.distinct()
    }

    private fun appendLog(msg: String) {
        tvLog.text = tvLog.text.toString() + "\n" + msg
    }

    private fun renderCommandsForCurrentSelection(connected: Boolean) {
        val model = when (spDevice.selectedItem?.toString()) {
            "SIMULATOR" -> GlassesModel.SIMULATOR
            "FRAME" -> GlassesModel.FRAME
            "RAYNEO" -> GlassesModel.RAYNEO
            else -> GlassesModel.ROKID
        }

        llCommands.removeAllViews()
        val e = entry
        if (e == null) {
            llCommands.addView(TextView(this).apply { text = "No UniversalAppEntry (meta-data com.universalglasses.app_entry_class)" })
            return
        }

        if (!connected || client == null) {
            llCommands.addView(TextView(this).apply { text = "Connect first to enable commands." })
            return
        }

        val env = HostEnvironment(hostKind = HostKind.PHONE, model = model)
        val cmds = e.commandsWithDefaults(env)
        if (cmds.isEmpty()) {
            llCommands.addView(TextView(this).apply { text = "No commands for PHONE/${model.name}" })
            return
        }

        cmds.forEach { cmd ->
            llCommands.addView(Button(this).apply {
                text = cmd.title
                setOnClickListener {
                    scope.launch {
                        val ctx = UniversalAppContext(
                            environment = env,
                            client = client!!,
                            scope = scope,
                            log = { appendLog(it) },
                            onCapturedImage = { img ->
                                val bytes = img.jpegBytes
                                if (bytes.isNotEmpty()) {
                                    scope.launch {
                                        val bmp = withContext(Dispatchers.Default) {
                                            BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
                                        }
                                        if (bmp != null) ivPreview.setImageBitmap(bmp)
                                    }
                                }
                            },
                            settings = appliedSettings,
                        )
                        val r = cmd.run(ctx)
                        if (r.isFailure) appendLog("Command failed: ${r.exceptionOrNull()?.message ?: "unknown"}")
                    }
                }
            })
        }
    }

    // ===================================================================
    // User settings UI
    // ===================================================================

    private val settingsPrefs by lazy {
        getSharedPreferences("ug_user_settings", Context.MODE_PRIVATE)
    }

    /**
     * Render input fields for the entry's [UniversalAppEntry.userSettings].
     * Values are pre-filled from SharedPreferences (falling back to defaults).
     */
    private fun renderSettings() {
        val e = entry ?: return
        val fields = e.userSettings()
        if (fields.isEmpty()) return

        tvSettingsTitle.visibility = android.view.View.VISIBLE
        llSettings.visibility = android.view.View.VISIBLE
        btnApplySettings.visibility = android.view.View.VISIBLE
        llSettings.removeAllViews()
        settingEdits.clear()

        for (field in fields) {
            val label = TextView(this).apply {
                text = field.label
                setPadding(0, 12, 0, 2)
            }
            llSettings.addView(label)

            val editText = EditText(this).apply {
                hint = field.hint
                inputType = when (field.inputType) {
                    UserSettingInputType.PASSWORD ->
                        InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
                    UserSettingInputType.URL ->
                        InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI
                    UserSettingInputType.NUMBER ->
                        InputType.TYPE_CLASS_NUMBER
                    else ->
                        InputType.TYPE_CLASS_TEXT
                }
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                )
                // Restore from prefs, or use default
                val stored = settingsPrefs.getString(field.key, null)
                setText(stored ?: field.defaultValue)
            }
            llSettings.addView(editText)
            settingEdits[field.key] = editText
        }

        // Build the initial applied settings from stored/default values.
        appliedSettings = buildSettingsMap(fields)
    }

    /** Save current input values to SharedPreferences and update [appliedSettings]. */
    private fun applySettings() {
        val e = entry ?: return
        val fields = e.userSettings()
        val editor = settingsPrefs.edit()
        for (field in fields) {
            val value = settingEdits[field.key]?.text?.toString().orEmpty()
            editor.putString(field.key, value)
        }
        editor.apply()
        appliedSettings = buildSettingsMap(fields)
        appendLog("Settings applied.")

        // For RayNeo: also push the settings file to the glasses via ADB so the
        // on-glasses host can read them.
        pushSettingsToRayNeoIfNeeded()
    }

    /**
     * If the current (or last-configured) glasses model is RAYNEO and we have an IP,
     * push the settings JSON to the glasses via ADB.
     */
    private fun pushSettingsToRayNeoIfNeeded() {
        if (appliedSettings.isEmpty()) return

        // Use existing client if it's already a RayNeo installer …
        val rayNeoClient = client as? RayNeoInstallerGlassesClient

        // … otherwise create a transient one if the user has selected RAYNEO and entered an IP.
        val selected = spDevice.selectedItem?.toString()
        val ip = etRayNeoIp.text?.toString()?.trim().orEmpty()

        if (rayNeoClient == null && (selected != "RAYNEO" || ip.isBlank())) return

        scope.launch {
            try {
                val pusher = rayNeoClient ?: RayNeoInstallerGlassesClient(
                    context = this@MainActivity,
                    config = RayNeoInstallerConfig(
                        host = ip,
                        apk = RayNeoApkSource.Asset("rayneo_glass_app.apk"),
                    ),
                )
                val r = pusher.pushUserSettings(appliedSettings)
                if (r.isSuccess) {
                    appendLog("Settings pushed to RayNeo glasses.")
                } else {
                    appendLog("Settings push failed: ${r.exceptionOrNull()?.message}")
                }
            } catch (e: Exception) {
                appendLog("Settings push error: ${e.message}")
            }
        }
    }

    /** Build a key→value map from current SharedPreferences (or defaults). */
    private fun buildSettingsMap(fields: List<UserSettingField>): Map<String, String> {
        return fields.associate { field ->
            val stored = settingsPrefs.getString(field.key, null)
            field.key to (stored ?: field.defaultValue)
        }
    }

    // ===================================================================

    private fun loadEntryOrNull(): UniversalAppEntry? {
        val cls = try {
            val appInfo = packageManager.getApplicationInfo(packageName, android.content.pm.PackageManager.GET_META_DATA)
            appInfo.metaData?.getString("com.universalglasses.app_entry_class")?.trim().orEmpty()
        } catch (_: Throwable) {
            ""
        }
        if (cls.isBlank()) return null
        return try {
            val k = Class.forName(cls)
            k.getDeclaredConstructor().newInstance() as UniversalAppEntry
        } catch (_: Throwable) {
            null
        }
    }
}


