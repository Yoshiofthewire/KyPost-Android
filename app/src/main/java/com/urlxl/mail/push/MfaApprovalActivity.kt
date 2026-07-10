package com.urlxl.mail.push

import android.os.Bundle
import android.widget.Button
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.urlxl.mail.R
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

class MfaApprovalActivity : AppCompatActivity() {
    private var challengeId: String = ""
    private var resolveJob: Job? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_mfa_approval)

        challengeId = intent.getStringExtra(PushNotificationDispatcher.EXTRA_MFA_CHALLENGE_ID).orEmpty()
        if (challengeId.isBlank()) {
            finish()
            return
        }

        findViewById<Button>(R.id.btnMfaApprove).setOnClickListener { resolve(approve = true) }
        findViewById<Button>(R.id.btnMfaDeny).setOnClickListener { resolve(approve = false) }
    }

    override fun onNewIntent(intent: android.content.Intent) {
        super.onNewIntent(intent)
        setIntent(intent)

        val newChallengeId = intent.getStringExtra(PushNotificationDispatcher.EXTRA_MFA_CHALLENGE_ID).orEmpty()
        if (newChallengeId.isBlank()) {
            finish()
            return
        }

        // A different challenge was tapped while this singleTop instance was already
        // on top showing the previous one. Cancel any in-flight resolve() for the old
        // challenge so it can't finish() this screen out from under the new challenge,
        // then swap in the new challenge and put the buttons back in a fresh state.
        resolveJob?.cancel()
        resolveJob = null
        challengeId = newChallengeId
        findViewById<Button>(R.id.btnMfaApprove).isEnabled = true
        findViewById<Button>(R.id.btnMfaDeny).isEnabled = true
    }

    private fun resolve(approve: Boolean) {
        findViewById<Button>(R.id.btnMfaApprove).isEnabled = false
        findViewById<Button>(R.id.btnMfaDeny).isEnabled = false
        resolveJob = lifecycleScope.launch {
            MfaResponseReceiver.respond(applicationContext, challengeId, approve)
            finish()
        }
    }
}
