package com.android5.ai

import android.app.Activity
import android.content.Intent
import android.os.Bundle

/** Browser handoff only. OAuth stays on the state-checked loopback callback. */
class ChatGptReturnActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val uri = intent.data
        val ticket = uri?.takeIf {
            it.scheme == "aichat" && it.host == "auth-return" && it.path.isNullOrEmpty() &&
                it.queryParameterNames == setOf("ticket") && it.getQueryParameters("ticket").size == 1
        }?.getQueryParameter("ticket")
        val target = ticket?.let { ChatGptAuth.get(this).browserReturn.consume(it) }
        if (target != null) {
            val destination = if (target == ChatGptBrowserReturn.Target.SETUP) SetupActivity::class.java else SettingsActivity::class.java
            startActivity(Intent(this, destination).addFlags(Intent.FLAG_ACTIVITY_REORDER_TO_FRONT or Intent.FLAG_ACTIVITY_SINGLE_TOP))
        }
        finish()
    }
}
