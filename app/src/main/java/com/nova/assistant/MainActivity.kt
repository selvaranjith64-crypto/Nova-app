package com.nova.assistant

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.text.InputType
import android.view.Gravity
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView

class MainActivity : Activity() {

    private val needed: Array<String> by lazy {
        val list = mutableListOf(
            Manifest.permission.RECORD_AUDIO,
            Manifest.permission.READ_CONTACTS,
            Manifest.permission.CALL_PHONE
        )
        if (Build.VERSION.SDK_INT >= 33) list.add(Manifest.permission.POST_NOTIFICATIONS)
        list.toTypedArray()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val prefs = getSharedPreferences("nova", MODE_PRIVATE)
        val pad = (24 * resources.displayMetrics.density).toInt()
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(pad, pad * 2, pad, pad)
        }
        root.addView(TextView(this).apply { text = "Nova"; textSize = 40f })
        root.addView(TextView(this).apply {
            text = "Say \"Nova\" then talk to me like a friend, or give a command:\n\n" +
                "Nova, open Instagram\nNova, call Amma\nNova, play Anirudh on Spotify\n" +
                "Nova, set a timer for 5 minutes\nNova, how are you today?"
            textSize = 16f
            setPadding(0, pad / 2, 0, pad)
        })
        root.addView(Button(this).apply {
            text = "1. Allow \"Display over other apps\""
            setOnClickListener {
                startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName")))
            }
        })
        root.addView(Button(this).apply {
            text = "2. Allow \"Do not disturb access\" (silences beeps)"
            setOnClickListener { startActivity(Intent(Settings.ACTION_NOTIFICATION_POLICY_ACCESS_SETTINGS)) }
        })
        root.addView(TextView(this).apply {
            text = "3. For real conversation, paste your Anthropic API key (stays on this phone only):"
            textSize = 14f
            setPadding(0, pad / 2, 0, 0)
        })
        val keyBox = EditText(this).apply {
            hint = "sk-ant-..."
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
            setText(prefs.getString("key", ""))
        }
        root.addView(keyBox)
        val status = TextView(this).apply { textSize = 14f }
        root.addView(Button(this).apply {
            text = "Save key"
            setOnClickListener {
                prefs.edit().putString("key", keyBox.text.toString().trim()).apply()
                status.text = "Key saved."
            }
        })
        root.addView(status)
        root.addView(Button(this).apply {
            text = "4. Start Nova"
            setOnClickListener { startNova() }
        })
        root.addView(Button(this).apply {
            text = "Stop Nova"
            setOnClickListener { stopService(Intent(this@MainActivity, NovaService::class.java)) }
        })
        setContentView(ScrollView(this).apply { addView(root) })
    }

    private fun startNova() {
        val missing = needed.filter { checkSelfPermission(it) != PackageManager.PERMISSION_GRANTED }
        if (missing.isNotEmpty()) {
            requestPermissions(missing.toTypedArray(), 1)
            return
        }
        val i = Intent(this, NovaService::class.java)
        if (Build.VERSION.SDK_INT >= 26) startForegroundService(i) else startService(i)
    }

    override fun onRequestPermissionsResult(code: Int, perms: Array<out String>, results: IntArray) {
        super.onRequestPermissionsResult(code, perms, results)
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) startNova()
    }
}
