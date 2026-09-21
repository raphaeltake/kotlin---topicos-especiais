package br.com.fatec.syncro

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.res.ColorStateList
import android.graphics.Rect
import android.os.Bundle
import android.util.Patterns
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import android.widget.ImageButton
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.widget.NestedScrollView
import androidx.core.widget.doAfterTextChanged
import br.com.fatec.syncro.ui.SyncroTextInput
import com.google.android.material.button.MaterialButton

class InviteTeamActivity : AppCompatActivity() {
    private val invitedEmails = mutableListOf<String>()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContentView(R.layout.activity_invite_team)
        val currentTeam = Workspace.team(intent.getStringExtra(Navigation.TEAM))
        if (currentTeam == null) { Navigation.home(this); return }
        Navigation.toolbar(this, currentTeam.id)

        ViewCompat.setOnApplyWindowInsetsListener(findViewById(R.id.inviteTeamRoot)) { view, insets ->
            val systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            view.setPadding(systemBars.left, systemBars.top, systemBars.right, systemBars.bottom)
            insets
        }

        findViewById<View>(R.id.btnBack).setOnClickListener {
            finish()
        }

        val teamCode = findViewById<TextView>(R.id.tvTeamCode)
        teamCode.text = currentTeam.code
        findViewById<ImageButton>(R.id.btnCopyTeamCode).setOnClickListener {
            val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            clipboard.setPrimaryClip(
                ClipData.newPlainText(getString(R.string.team_code_label), teamCode.text)
            )
            Toast.makeText(this, R.string.team_code_copied, Toast.LENGTH_SHORT).show()
        }

        val emailInputLayout = findViewById<SyncroTextInput>(R.id.emailInputLayout)
        val emailInput = emailInputLayout.input
        val emailListScroll = findViewById<NestedScrollView>(R.id.emailListScroll)
        val emailList = findViewById<LinearLayout>(R.id.llEmailList)
        val addEmailButton = findViewById<MaterialButton>(R.id.btnAddEmail)

        val inviteContent = findViewById<ViewGroup>(R.id.inviteContent)
        val inputBounds = Rect()
        // Align with the editor itself, excluding the floating label and error text.
        inviteContent.viewTreeObserver.addOnGlobalLayoutListener {
            emailInput.getDrawingRect(inputBounds)
            inviteContent.offsetDescendantRectToMyCoords(emailInput, inputBounds)
            addEmailButton.translationY = inputBounds.exactCenterY() -
                (addEmailButton.top + addEmailButton.height / 2f)
        }

        savedInstanceState?.getStringArrayList(STATE_INVITED_EMAILS)?.let(invitedEmails::addAll)
        renderEmailList(emailList)

        fun addEmail() {
            val email = emailInput.text?.toString()?.trim().orEmpty()
            emailInputLayout.error = when {
                email.isEmpty() -> getString(R.string.invite_email_required_error)
                !Patterns.EMAIL_ADDRESS.matcher(email).matches() ->
                    getString(R.string.invite_email_invalid_error)
                invitedEmails.any { it.equals(email, ignoreCase = true) } ->
                    getString(R.string.invite_email_duplicate_error)
                else -> null
            }

            if (emailInputLayout.error != null) {
                emailInput.requestFocus()
                return
            }

            invitedEmails.add(email)
            emailInput.text?.clear()
            renderEmailList(emailList)
            emailListScroll.post {
                emailListScroll.fullScroll(View.FOCUS_DOWN)
            }
        }

        emailInput.doAfterTextChanged {
            emailInputLayout.error = null
        }

        addEmailButton.setOnClickListener {
            addEmail()
        }

        emailInput.setOnEditorActionListener { _, actionId, event ->
            val enterPressed = event?.keyCode == KeyEvent.KEYCODE_ENTER &&
                event.action == KeyEvent.ACTION_DOWN

            if (actionId == EditorInfo.IME_ACTION_DONE || enterPressed) {
                addEmailButton.performClick()
                true
            } else {
                false
            }
        }

        findViewById<View>(R.id.btnInvite).setOnClickListener {
            if (invitedEmails.isEmpty()) {
                emailInputLayout.error = "Adicione ao menos um e-mail à lista"
                emailInput.requestFocus()
                return@setOnClickListener
            }
            Navigation.open(this, "success", currentTeam.id)
            finish()
        }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        outState.putStringArrayList(STATE_INVITED_EMAILS, ArrayList(invitedEmails))
        super.onSaveInstanceState(outState)
    }

    private fun renderEmailList(container: LinearLayout) {
        container.removeAllViews()

        invitedEmails.forEach { email ->
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = android.view.Gravity.CENTER_VERTICAL
                setBackgroundResource(R.drawable.bg_email_chip)
                setPadding(16.dp, 4.dp, 4.dp, 4.dp)
                layoutParams = LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT
                ).apply {
                    bottomMargin = 8.dp
                }
            }

            val emailText = TextView(this).apply {
                text = email
                textSize = 14f
                setTextColor(ContextCompat.getColor(context, R.color.black))
                layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
            }

            val removeButton = ImageButton(this).apply {
                setImageResource(R.drawable.ic_close_circle)
                imageTintList = ColorStateList.valueOf(
                    ContextCompat.getColor(context, R.color.blue)
                )
                setBackgroundResource(android.R.color.transparent)
                contentDescription = getString(R.string.remove_email, email)
                layoutParams = LinearLayout.LayoutParams(40.dp, 40.dp)
                setOnClickListener {
                    invitedEmails.remove(email)
                    renderEmailList(container)
                }
            }

            row.addView(emailText)
            row.addView(removeButton)
            container.addView(row)
        }
    }

    private val Int.dp: Int
        get() = (this * resources.displayMetrics.density).toInt()

    companion object {
        private const val STATE_INVITED_EMAILS = "invited_emails"
    }
}
