package br.com.fatec.syncro

import android.content.Intent
import android.os.Bundle
import android.view.KeyEvent
import android.view.inputmethod.EditorInfo
import android.widget.Button
import android.widget.ImageButton
import android.widget.TextView
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.widget.doAfterTextChanged
import br.com.fatec.syncro.ui.SyncroTextInput

class PasswordActivity : AppCompatActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContentView(R.layout.passwordlogin)

        ViewCompat.setOnApplyWindowInsetsListener(findViewById(R.id.passwordRoot)) { view, insets ->
            val systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            view.setPadding(systemBars.left, systemBars.top, systemBars.right, systemBars.bottom)
            insets
        }

        val email = intent.getStringExtra(EXTRA_EMAIL).orEmpty()
        findViewById<TextView>(R.id.selectedEmail).text = email

        findViewById<ImageButton>(R.id.backButton).setOnClickListener {
            finish()
        }

        val passwordInputLayout = findViewById<SyncroTextInput>(R.id.passwordInputLayout)
        val passwordInput = passwordInputLayout.input
        val loginButton = findViewById<Button>(R.id.passwordLoginButton)

        passwordInput.doAfterTextChanged {
            passwordInputLayout.error = null
        }

        loginButton.setOnClickListener {
            val password = passwordInput.text?.toString().orEmpty()
            passwordInputLayout.error = if (password.isBlank()) {
                getString(R.string.password_required_error)
            } else {
                null
            }

            if (passwordInputLayout.error == null) {
                startActivity(
                    Intent(this, TeamsActivity::class.java).apply {
                        flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
                    }
                )
            }
        }

        passwordInput.setOnEditorActionListener { _, actionId, event ->
            val enterPressed = event?.keyCode == KeyEvent.KEYCODE_ENTER &&
                event.action == KeyEvent.ACTION_DOWN

            if (actionId == EditorInfo.IME_ACTION_DONE || enterPressed) {
                loginButton.performClick()
                true
            } else {
                false
            }
        }
    }

    companion object {
        const val EXTRA_EMAIL = "extra_email"
    }
}
