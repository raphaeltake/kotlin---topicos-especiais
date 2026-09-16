package br.com.fatec.syncro

import android.content.Intent
import android.os.Bundle
import android.util.Patterns
import android.view.KeyEvent
import android.view.inputmethod.EditorInfo
import android.widget.Button
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.widget.doAfterTextChanged
import com.google.android.material.textfield.TextInputEditText
import com.google.android.material.textfield.TextInputLayout

class MainActivity : AppCompatActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContentView(R.layout.emaillogin)
        ViewCompat.setOnApplyWindowInsetsListener(findViewById(R.id.main)) { v, insets ->
            val systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            v.setPadding(systemBars.left, systemBars.top, systemBars.right, systemBars.bottom)
            insets
        }

        val emailInput = findViewById<TextInputEditText>(R.id.emailInput)
        val emailInputLayout = findViewById<TextInputLayout>(R.id.emailInputLayout)
        val loginButton = findViewById<Button>(R.id.button)

        fun continueToPassword() {
            val email = emailInput.text?.toString()?.trim().orEmpty()
            emailInputLayout.error = when {
                email.isEmpty() -> getString(R.string.email_required_error)
                !Patterns.EMAIL_ADDRESS.matcher(email).matches() ->
                    getString(R.string.email_invalid_error)
                else -> null
            }

            if (emailInputLayout.error == null) {
                startActivity(
                    Intent(this, PasswordActivity::class.java)
                        .putExtra(PasswordActivity.EXTRA_EMAIL, email)
                )
            }
        }

        emailInput.doAfterTextChanged {
            emailInputLayout.error = null
        }

        loginButton.setOnClickListener {
            continueToPassword()
        }

        emailInput.setOnEditorActionListener { _, actionId, event ->
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
}
