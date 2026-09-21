package br.com.fatec.syncro

import android.content.Intent
import android.os.Bundle
import android.util.Patterns
import android.view.KeyEvent
import android.view.inputmethod.EditorInfo
import android.widget.Button
import android.widget.ImageButton
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.widget.doAfterTextChanged
import br.com.fatec.syncro.ui.SyncroTextInput

class CadastroActivity : AppCompatActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContentView(R.layout.cadastro)

        ViewCompat.setOnApplyWindowInsetsListener(findViewById(R.id.registerRoot)) { view, insets ->
            val systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            view.setPadding(systemBars.left, systemBars.top, systemBars.right, systemBars.bottom)
            insets
        }

        findViewById<ImageButton>(R.id.registerBackButton).setOnClickListener {
            finish()
        }

        val nameInputLayout = findViewById<SyncroTextInput>(R.id.nameInputLayout)
        val nameInput = nameInputLayout.input
        val emailInputLayout = findViewById<SyncroTextInput>(R.id.registerEmailInputLayout)
        val emailInput = emailInputLayout.input
        val passwordInputLayout = findViewById<SyncroTextInput>(R.id.registerPasswordInputLayout)
        val passwordInput = passwordInputLayout.input
        val confirmPasswordInputLayout =
            findViewById<SyncroTextInput>(R.id.confirmPasswordInputLayout)
        val confirmPasswordInput = confirmPasswordInputLayout.input
        val registerButton = findViewById<Button>(R.id.registerButton)

        fun validateFields(): Boolean {
            val name = nameInput.text?.toString()?.trim().orEmpty()
            val email = emailInput.text?.toString()?.trim().orEmpty()
            val password = passwordInput.text?.toString().orEmpty()
            val confirmPassword = confirmPasswordInput.text?.toString().orEmpty()

            nameInputLayout.error = if (name.isBlank()) {
                getString(R.string.name_required_error)
            } else {
                null
            }

            emailInputLayout.error = when {
                email.isBlank() -> getString(R.string.email_required_error)
                !Patterns.EMAIL_ADDRESS.matcher(email).matches() ->
                    getString(R.string.email_invalid_error)
                else -> null
            }

            passwordInputLayout.error = if (password.isBlank()) {
                getString(R.string.password_required_error)
            } else {
                null
            }

            confirmPasswordInputLayout.error = when {
                confirmPassword.isBlank() -> getString(R.string.confirm_password_required_error)
                password != confirmPassword -> getString(R.string.passwords_do_not_match_error)
                else -> null
            }

            return nameInputLayout.error == null &&
                emailInputLayout.error == null &&
                passwordInputLayout.error == null &&
                confirmPasswordInputLayout.error == null
        }

        nameInput.doAfterTextChanged {
            nameInputLayout.error = null
        }
        emailInput.doAfterTextChanged {
            emailInputLayout.error = null
        }
        passwordInput.doAfterTextChanged {
            passwordInputLayout.error = null
            confirmPasswordInputLayout.error = null
        }
        confirmPasswordInput.doAfterTextChanged {
            confirmPasswordInputLayout.error = null
        }

        registerButton.setOnClickListener {
            if (validateFields()) {
                Workspace.profile.name = nameInput.text.toString().trim()
                Workspace.profile.email = emailInput.text.toString().trim()
                Workspace.profile.password = passwordInput.text.toString()
                startActivity(
                    Intent(this, TeamsActivity::class.java).apply {
                        flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
                    }
                )
            }
        }

        confirmPasswordInput.setOnEditorActionListener { _, actionId, event ->
            val enterPressed = event?.keyCode == KeyEvent.KEYCODE_ENTER &&
                event.action == KeyEvent.ACTION_DOWN

            if (actionId == EditorInfo.IME_ACTION_DONE || enterPressed) {
                registerButton.performClick()
                true
            } else {
                false
            }
        }
    }
}
