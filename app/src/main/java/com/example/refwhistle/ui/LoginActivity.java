package com.example.refwhistle.ui;

import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Bundle;
import android.text.InputType;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;

import com.example.refwhistle.cloud.Cloud;
import com.google.android.gms.tasks.OnCompleteListener;
import com.google.android.gms.tasks.Task;
import com.google.android.material.button.MaterialButton;
import com.google.android.material.textfield.TextInputEditText;
import com.google.android.material.textfield.TextInputLayout;
import com.google.firebase.auth.AuthResult;
import com.google.firebase.auth.FirebaseAuth;

/** Firebase email/password login. "Continue offline" keeps the app fully usable without an account. */
public class LoginActivity extends AppCompatActivity {
    private TextInputEditText etEmail, etPass;
    private TextView tvMsg;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        LinearLayout body = UiKit.screen(this, "Welcome", "Sign in to back up and share your tournament.");
        // no back arrow needed here: this is the entry screen
        LinearLayout card = UiKit.card(this, body);
        UiKit.text(this, card, "RefWhistle account", 18, true);

        TextInputLayout tilE = new TextInputLayout(this);
        tilE.setHint("Email");
        etEmail = new TextInputEditText(tilE.getContext());
        etEmail.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_EMAIL_ADDRESS);
        tilE.addView(etEmail);
        card.addView(tilE, lp(12));

        TextInputLayout tilP = new TextInputLayout(this);
        tilP.setHint("Password (min 6 characters)");
        tilP.setEndIconMode(TextInputLayout.END_ICON_PASSWORD_TOGGLE);
        etPass = new TextInputEditText(tilP.getContext());
        etPass.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD);
        tilP.addView(etPass);
        card.addView(tilP, lp(8));

        MaterialButton in = button("Sign in");
        in.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) { signIn(); }
        });
        card.addView(in, lp(14));

        MaterialButton up = button("Create account");
        up.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) { signUp(); }
        });
        card.addView(up, lp(6));

        tvMsg = UiKit.text(this, card, "", 13, false);
        tvMsg.setPadding(0, UiKit.dp(this, 8), 0, 0);

        LinearLayout off = UiKit.card(this, body);
        UiKit.text(this, off, "No internet at the ground?", 15, true);
        UiKit.text(this, off, "Everything works offline. Sign in later to back up.", 13, false);
        MaterialButton skip = button("Continue offline");
        skip.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                getSharedPreferences("refwhistle", Context.MODE_PRIVATE).edit().putBoolean("offline_ok", true).apply();
                goMain();
            }
        });
        off.addView(skip, lp(10));
    }

    private LinearLayout.LayoutParams lp(int topDp) {
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(-1, -2);
        p.topMargin = UiKit.dp(this, topDp);
        return p;
    }

    private MaterialButton button(String s) {
        MaterialButton b = new MaterialButton(this);
        b.setText(s);
        return b;
    }

    private boolean valid(String email, String pass) {
        if (email.isEmpty() || pass.length() < 6) {
            tvMsg.setText("Enter an email and a password of at least 6 characters.");
            return false;
        }
        return true;
    }

    private void signIn() {
        String e = String.valueOf(etEmail.getText()).trim(), p = String.valueOf(etPass.getText());
        if (!valid(e, p)) return;
        tvMsg.setText("Signing in...");
        FirebaseAuth.getInstance().signInWithEmailAndPassword(e, p).addOnCompleteListener(done("Signed in"));
    }

    private void signUp() {
        String e = String.valueOf(etEmail.getText()).trim(), p = String.valueOf(etPass.getText());
        if (!valid(e, p)) return;
        tvMsg.setText("Creating account...");
        FirebaseAuth.getInstance().createUserWithEmailAndPassword(e, p).addOnCompleteListener(done("Account created"));
    }

    private OnCompleteListener<AuthResult> done(final String ok) {
        return new OnCompleteListener<AuthResult>() {
            public void onComplete(Task<AuthResult> t) {
                if (t.isSuccessful()) {
                    Toast.makeText(LoginActivity.this, ok, Toast.LENGTH_SHORT).show();
                    goMain();
                } else {
                    tvMsg.setText(String.valueOf(t.getException() == null ? "Failed" : t.getException().getMessage()));
                }
            }
        };
    }

    private void goMain() {
        startActivity(new Intent(this, MainActivity.class));
        finish();
    }

    @Override
    public void onBackPressed() {
        finishAffinity();
    }
}
