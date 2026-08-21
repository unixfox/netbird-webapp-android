package com.example.nbbrowser

import android.text.InputType
import android.widget.EditText
import android.widget.LinearLayout
import androidx.appcompat.app.AlertDialog
import nbproxy.Nbproxy

/** The NetBird server settings dialog. Restarts the tunnel only if the server changed. */
internal fun MainActivity.openSettings() {
    val prefs = prefs()
    val mgmtInput = EditText(this)
    mgmtInput.hint = "Management URL"
    mgmtInput.setText(prefs.getString("mgmtUrl", null) ?: MainActivity.DEFAULT_MGMT)
    mgmtInput.inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI
    val keyInput = EditText(this)
    keyInput.hint = "Setup key (leave empty for JWT)"
    keyInput.setText(prefs.getString("setupKey", null) ?: "")
    keyInput.inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD

    val origMgmt = prefs.getString("mgmtUrl", null) ?: MainActivity.DEFAULT_MGMT
    val origKey = prefs.getString("setupKey", null) ?: ""

    val container = LinearLayout(this)
    container.orientation = LinearLayout.VERTICAL
    container.setPadding(48, 8, 48, 8)
    container.addView(mgmtInput)
    container.addView(keyInput)

    AlertDialog.Builder(this)
        .setTitle("NetBird settings")
        .setMessage("Applied on save. Changing the server restarts the tunnel.")
        .setView(container)
        .setPositiveButton("Save") { _, _ ->
            val mgmt = mgmtInput.text.toString().trim()
            val key = keyInput.text.toString().trim()
            if (mgmt.isEmpty()) {
                toast("Management URL cannot be empty")
                return@setPositiveButton
            }
            prefs.edit().apply {
                putString("mgmtUrl", mgmt)
                if (key.isEmpty()) remove("setupKey") else putString("setupKey", key)
            }.apply()

            if (mgmt == origMgmt && key == origKey) return@setPositiveButton

            engineStarted = false
            engineStarting = false
            proxyLive = false
            Thread {
                try {
                    Nbproxy.stop()
                } catch (_: Exception) {
                }
            }.start()
            toast("Restarting tunnel…")
            web.postDelayed({
                val current = web.url
                startEngineThenLoad(current ?: MainActivity.START_PAGE)
            }, 1500)
        }
        .setNegativeButton("Cancel", null)
        .show()
}
