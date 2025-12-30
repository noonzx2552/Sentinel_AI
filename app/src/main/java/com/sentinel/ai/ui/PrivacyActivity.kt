package com.sentinel.ai.ui

import android.content.Intent
import android.graphics.Typeface
import android.os.Bundle
import android.text.SpannableString
import android.text.Spanned
import android.text.method.LinkMovementMethod
import android.text.style.ClickableSpan
import android.text.style.ForegroundColorSpan
import android.view.View
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.sentinel.ai.R
import com.sentinel.ai.databinding.ActivityPrivacyBinding
import com.sentinel.ai.utils.PermissionUtils

class PrivacyActivity : AppCompatActivity() {

    private lateinit var binding: ActivityPrivacyBinding

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityPrivacyBinding.inflate(layoutInflater)
        setContentView(binding.root)

        bindFooterLinks()

        binding.btnAgree.setOnClickListener {
            val target = if (PermissionUtils.allEssentialGranted(this)) {
                Intent(this, HomeActivity::class.java)
            } else {
                Intent(this, SetupActivity::class.java)
            }
            startActivity(target)
            finish()
        }
    }

    private fun bindFooterLinks() {
        val privacyText = getString(R.string.privacy_footer_policy)
        val termsText = getString(R.string.privacy_footer_terms)
        val fullText = getString(R.string.privacy_footer, privacyText, termsText)

        val privacyStart = fullText.indexOf(privacyText)
        val termsStart = fullText.indexOf(termsText)
        if (privacyStart == -1 || termsStart == -1) {
            binding.footerLinks.text = fullText
            return
        }

        val spannable = SpannableString(fullText)
        val linkColor = ContextCompat.getColor(this, R.color.splash_logo_bg)
        val linkSpan = object : ClickableSpan() {
            override fun onClick(widget: View) {
                // Placeholder: hook to real policy/terms screens if needed.
            }

            override fun updateDrawState(ds: android.text.TextPaint) {
                super.updateDrawState(ds)
                ds.isUnderlineText = false
                ds.typeface = Typeface.DEFAULT_BOLD
            }
        }

        spannable.setSpan(
            ForegroundColorSpan(linkColor),
            privacyStart,
            privacyStart + privacyText.length,
            Spanned.SPAN_EXCLUSIVE_EXCLUSIVE
        )
        spannable.setSpan(
            linkSpan,
            privacyStart,
            privacyStart + privacyText.length,
            Spanned.SPAN_EXCLUSIVE_EXCLUSIVE
        )
        spannable.setSpan(
            ForegroundColorSpan(linkColor),
            termsStart,
            termsStart + termsText.length,
            Spanned.SPAN_EXCLUSIVE_EXCLUSIVE
        )
        spannable.setSpan(
            linkSpan,
            termsStart,
            termsStart + termsText.length,
            Spanned.SPAN_EXCLUSIVE_EXCLUSIVE
        )

        binding.footerLinks.text = spannable
        binding.footerLinks.movementMethod = LinkMovementMethod.getInstance()
        binding.footerLinks.highlightColor = ContextCompat.getColor(this, android.R.color.transparent)
    }
}
