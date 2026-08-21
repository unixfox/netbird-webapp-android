package com.example.nbbrowser

import android.content.Context
import android.text.Editable
import android.text.TextWatcher
import android.view.View
import android.view.inputmethod.InputMethodManager
import android.widget.Button

/** Wires up the find-in-page bar (called once from onCreate). */
internal fun MainActivity.setupFindBar() {
    findBar = findViewById(R.id.findBar)
    findInput = findViewById(R.id.findInput)
    findCount = findViewById(R.id.findCount)
    val prev = findViewById<Button>(R.id.findPrev)
    val next = findViewById<Button>(R.id.findNext)
    val close = findViewById<Button>(R.id.findClose)

    web.setFindListener { activeMatchOrdinal, numberOfMatches, _ ->
        findCount.text =
            if (numberOfMatches > 0) "${activeMatchOrdinal + 1}/$numberOfMatches" else "0"
    }
    findInput.addTextChangedListener(object : TextWatcher {
        override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
        override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
        override fun afterTextChanged(s: Editable?) {
            val q = s?.toString().orEmpty()
            if (q.isEmpty()) { web.clearMatches(); findCount.text = "" } else web.findAllAsync(q)
        }
    })
    findInput.setOnEditorActionListener { _, _, _ -> web.findNext(true); true }
    prev.setOnClickListener { web.findNext(false) }
    next.setOnClickListener { web.findNext(true) }
    close.setOnClickListener { closeFind() }
}

internal fun MainActivity.openFind() {
    appBar.setExpanded(true, true)
    findBar.visibility = View.VISIBLE
    findInput.setText("")
    findInput.requestFocus()
    val imm = getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
    imm.showSoftInput(findInput, InputMethodManager.SHOW_IMPLICIT)
}

internal fun MainActivity.closeFind() {
    web.clearMatches()
    findBar.visibility = View.GONE
    findCount.text = ""
    val imm = getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
    imm.hideSoftInputFromWindow(findInput.windowToken, 0)
}
