package com.sentinel.ai.ui

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import com.google.android.material.bottomsheet.BottomSheetDialogFragment
import com.sentinel.ai.R

class ProfileDetailBottomSheet : BottomSheetDialogFragment() {

    private var title: String? = null
    private var subtitle: String? = null
    private var body: String? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        arguments?.let {
            title = it.getString(ARG_TITLE)
            subtitle = it.getString(ARG_SUBTITLE)
            body = it.getString(ARG_BODY)
        }
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View? {
        return inflater.inflate(R.layout.layout_profile_detail_bottom_sheet, container, false)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        view.findViewById<TextView>(R.id.sheetTitle).text = title
        view.findViewById<TextView>(R.id.sheetSubtitle).text = subtitle
        view.findViewById<TextView>(R.id.sheetBody).text = body

        view.findViewById<View>(R.id.btnSheetClose).setOnClickListener {
            dismiss()
        }
    }

    companion object {
        private const val ARG_TITLE = "title"
        private const val ARG_SUBTITLE = "subtitle"
        private const val ARG_BODY = "body"

        fun newInstance(title: String, subtitle: String, body: String): ProfileDetailBottomSheet {
            val fragment = ProfileDetailBottomSheet()
            val args = Bundle()
            args.putString(ARG_TITLE, title)
            args.putString(ARG_SUBTITLE, subtitle)
            args.putString(ARG_BODY, body)
            fragment.arguments = args
            return fragment
        }
    }
}
