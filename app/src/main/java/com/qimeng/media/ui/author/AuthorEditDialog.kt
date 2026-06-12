package com.qimeng.media.ui.author

import android.app.Dialog
import android.os.Bundle
import android.widget.EditText
import androidx.appcompat.app.AlertDialog
import androidx.fragment.app.DialogFragment

class AuthorEditDialog(
    private val onConfirm: (String) -> Unit
) : DialogFragment() {

    override fun onCreateDialog(savedInstanceState: Bundle?): Dialog {
        val editText = EditText(requireContext()).apply {
            hint = "输入作者显示名称"
            setPadding(32, 32, 32, 32)
        }

        return AlertDialog.Builder(requireContext())
            .setTitle("新增作者")
            .setView(editText)
            .setPositiveButton("添加") { _, _ ->
                val name = editText.text.toString().trim()
                if (name.isNotEmpty()) onConfirm(name)
            }
            .setNegativeButton("取消", null)
            .create()
    }
}
