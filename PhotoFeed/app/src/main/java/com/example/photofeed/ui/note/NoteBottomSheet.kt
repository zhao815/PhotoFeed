package com.example.photofeed.ui.note

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.InputMethodManager
import com.example.photofeed.R
import com.example.photofeed.data.MediaItemUi
import com.example.photofeed.data.NoteStore
import com.google.android.material.bottomsheet.BottomSheetDialogFragment
import com.google.android.material.button.MaterialButton
import android.widget.EditText

class NoteBottomSheet : BottomSheetDialogFragment() {

    private val mediaKey: String get() = requireArguments().getString(ARG_MEDIA_KEY, "")

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setStyle(STYLE_NORMAL, R.style.Theme_PhotoFeed_Sheet)
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View = inflater.inflate(R.layout.sheet_note, container, false)

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        val input = view.findViewById<EditText>(R.id.noteInput)
        val btnSave = view.findViewById<MaterialButton>(R.id.btnSave)
        val btnClear = view.findViewById<MaterialButton>(R.id.btnClear)
        val btnCancel = view.findViewById<MaterialButton>(R.id.btnCancel)

        val key = mediaKey
        input.setText(NoteStore.textFor(key) ?: "")
        input.setSelection(input.text?.length ?: 0)
        input.requestFocus()
        input.post {
            val imm = requireContext().getSystemService(InputMethodManager::class.java)
            imm?.showSoftInput(input, InputMethodManager.SHOW_IMPLICIT)
        }

        btnSave.setOnClickListener {
            val args = requireArguments()
            val itemForSave = MediaItemUi(
                mediaKey = key,
                uri = android.net.Uri.parse(args.getString(ARG_URI, "") ?: ""),
                type = MediaItemUi.MediaType.PHOTO,
                dateTaken = args.getLong(ARG_DATE, 0L),
                size = args.getLong(ARG_SIZE, 0L),
                durationMs = null,
                fileName = null
            )
            NoteStore.setNote(itemForSave, input.text?.toString())
            dismiss()
        }
        btnClear.setOnClickListener {
            val args = requireArguments()
            val itemForSave = MediaItemUi(
                mediaKey = key,
                uri = android.net.Uri.parse(args.getString(ARG_URI, "") ?: ""),
                type = MediaItemUi.MediaType.PHOTO,
                dateTaken = args.getLong(ARG_DATE, 0L),
                size = args.getLong(ARG_SIZE, 0L),
                durationMs = null,
                fileName = null
            )
            NoteStore.setNote(itemForSave, null)
            input.setText("")
            dismiss()
        }
        btnCancel.setOnClickListener { dismiss() }
    }

    companion object {
        private const val ARG_MEDIA_KEY = "mediaKey"
        private const val ARG_URI = "uri"
        private const val ARG_DATE = "date"
        private const val ARG_SIZE = "size"

        fun newInstance(item: MediaItemUi): NoteBottomSheet {
            val f = NoteBottomSheet()
            f.arguments = Bundle().apply {
                putString(ARG_MEDIA_KEY, item.mediaKey)
                putString(ARG_URI, item.uri.toString())
                putLong(ARG_DATE, item.dateTaken)
                putLong(ARG_SIZE, item.size)
            }
            return f
        }
    }
}
