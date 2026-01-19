package com.sentinel.ai.ui

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.core.widget.addTextChangedListener
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.google.android.material.bottomsheet.BottomSheetDialogFragment
import com.sentinel.ai.R
import com.sentinel.ai.databinding.LayoutEditNameBottomSheetBinding
import com.sentinel.ai.utils.ProfilePrefs

class EditNameBottomSheet : BottomSheetDialogFragment() {

    var onNameSaved: ((String) -> Unit)? = null

    private var _binding: LayoutEditNameBottomSheetBinding? = null
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
        _binding = LayoutEditNameBottomSheetBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        val currentName = ProfilePrefs.getName(requireContext())
        binding.editNameInput.setText(currentName)
        binding.editNameInput.setSelection(currentName.length)

        binding.btnCancel.setOnClickListener { dismiss() }
        binding.btnSave.setOnClickListener { saveName() }

        binding.editNameInput.addTextChangedListener { text ->
            val trimmed = text?.toString()?.trim().orEmpty()
            setValidationState(trimmed)
        }

        setValidationState(currentName)
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

    private fun setValidationState(name: String) {
        val valid = name.isNotBlank()
        binding.btnSave.isEnabled = valid
        binding.editNameLayout.error = if (valid) null else getString(R.string.profile_edit_name_error)
    }

    private fun saveName() {
        val name = binding.editNameInput.text?.toString()?.trim().orEmpty()
        if (name.isBlank()) {
            setValidationState(name)
            return
        }
        ProfilePrefs.setName(requireContext(), name)
        onNameSaved?.invoke(name)
        dismiss()
    }
}
