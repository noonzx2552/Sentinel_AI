    package com.sentinel.ai.ui

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import androidx.core.content.ContextCompat
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.google.android.material.bottomsheet.BottomSheetDialogFragment
import com.google.android.material.card.MaterialCardView
import com.sentinel.ai.R
import com.sentinel.ai.databinding.LayoutLanguageBottomSheetBinding
import com.sentinel.ai.utils.LanguageManager

class LanguageBottomSheet : BottomSheetDialogFragment() {

    var onLanguageSelected: ((String) -> Unit)? = null

    private var _binding: LayoutLanguageBottomSheetBinding? = null
    private val binding get() = _binding!!

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setStyle(STYLE_NORMAL, R.style.ThemeOverlay_SentinelAI_BottomSheet)
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = LayoutLanguageBottomSheetBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        val current = LanguageManager.getLanguage(requireContext())
        updateSelection(current)

        binding.langOptionEn.setOnClickListener { selectLanguage(LanguageManager.LANG_EN) }
        binding.langOptionTh.setOnClickListener { selectLanguage(LanguageManager.LANG_TH) }
    }

    override fun onStart() {
        super.onStart()
        (dialog as? BottomSheetDialog)?.behavior?.state = com.google.android.material.bottomsheet.BottomSheetBehavior.STATE_EXPANDED
        dialog?.window?.setDimAmount(0.45f)
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }

    private fun selectLanguage(language: String) {
        val current = LanguageManager.getLanguage(requireContext())
        if (current == language) {
            dismiss()
            return
        }
        LanguageManager.setLanguage(requireContext(), language)
        onLanguageSelected?.invoke(language)
        dismiss()
    }

    private fun updateSelection(language: String) {
        setOptionSelected(binding.langOptionEn, binding.langCheckEn, language == LanguageManager.LANG_EN)
        setOptionSelected(binding.langOptionTh, binding.langCheckTh, language == LanguageManager.LANG_TH)
    }

    private fun setOptionSelected(card: MaterialCardView, check: ImageView, selected: Boolean) {
        val color = if (selected) R.color.profile_icon_blue else R.color.profile_card_border
        card.strokeColor = ContextCompat.getColor(requireContext(), color)
        check.visibility = if (selected) View.VISIBLE else View.INVISIBLE
    }
}
