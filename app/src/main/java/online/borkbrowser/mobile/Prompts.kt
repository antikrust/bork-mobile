package online.borkbrowser.mobile

import android.app.DatePickerDialog
import android.app.TimePickerDialog
import android.content.Intent
import android.graphics.Color
import android.net.Uri
import android.text.InputType
import android.view.ViewGroup
import android.widget.EditText
import android.widget.LinearLayout
import androidx.appcompat.app.AppCompatActivity
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import org.mozilla.geckoview.AllowOrDeny
import org.mozilla.geckoview.GeckoResult
import org.mozilla.geckoview.GeckoSession
import org.mozilla.geckoview.GeckoSession.PromptDelegate
import org.mozilla.geckoview.GeckoSession.PromptDelegate.PromptResponse
import java.util.Calendar
import java.util.Locale

/**
 * Everything a page can ask the user: alert/confirm/prompt, <select>, HTTP
 * auth, file uploads, date and colour pickers, "leave this page?", popups.
 *
 * GeckoView draws none of these itself. Without a delegate a <select> simply
 * does not open, which on a phone breaks half the forms on the web.
 */
class Prompts(
    private val activity: AppCompatActivity,
    private val pickFiles: (mimeTypes: Array<String>, multiple: Boolean, done: (List<Uri>) -> Unit) -> Unit,
) : PromptDelegate {

    private fun dp(value: Int) = (value * activity.resources.displayMetrics.density).toInt()

    private fun builder(title: String?) = MaterialAlertDialogBuilder(activity).apply {
        if (!title.isNullOrBlank()) setTitle(title)
    }

    override fun onAlertPrompt(session: GeckoSession, prompt: PromptDelegate.AlertPrompt): GeckoResult<PromptResponse> {
        val result = GeckoResult<PromptResponse>()
        builder(prompt.title)
            .setMessage(prompt.message)
            .setPositiveButton(android.R.string.ok) { _, _ -> result.complete(prompt.dismiss()) }
            .setOnCancelListener { result.complete(prompt.dismiss()) }
            .show()
        return result
    }

    override fun onButtonPrompt(session: GeckoSession, prompt: PromptDelegate.ButtonPrompt): GeckoResult<PromptResponse> {
        val result = GeckoResult<PromptResponse>()
        builder(prompt.title)
            .setMessage(prompt.message)
            .setPositiveButton(android.R.string.ok) { _, _ ->
                result.complete(prompt.confirm(PromptDelegate.ButtonPrompt.Type.POSITIVE))
            }
            .setNegativeButton(android.R.string.cancel) { _, _ ->
                result.complete(prompt.confirm(PromptDelegate.ButtonPrompt.Type.NEGATIVE))
            }
            .setOnCancelListener { result.complete(prompt.dismiss()) }
            .show()
        return result
    }

    override fun onTextPrompt(session: GeckoSession, prompt: PromptDelegate.TextPrompt): GeckoResult<PromptResponse> {
        val result = GeckoResult<PromptResponse>()
        val input = EditText(activity).apply { setText(prompt.defaultValue ?: "") }
        builder(prompt.title)
            .setMessage(prompt.message)
            .setView(padded(input))
            .setPositiveButton(android.R.string.ok) { _, _ -> result.complete(prompt.confirm(input.text.toString())) }
            .setNegativeButton(android.R.string.cancel) { _, _ -> result.complete(prompt.dismiss()) }
            .setOnCancelListener { result.complete(prompt.dismiss()) }
            .show()
        return result
    }

    override fun onAuthPrompt(session: GeckoSession, prompt: PromptDelegate.AuthPrompt): GeckoResult<PromptResponse> {
        val result = GeckoResult<PromptResponse>()
        val onlyPassword =
            prompt.authOptions.flags and PromptDelegate.AuthPrompt.AuthOptions.Flags.ONLY_PASSWORD != 0
        val user = EditText(activity).apply {
            hint = "Username"
            setText(prompt.authOptions.username ?: "")
        }
        val pass = EditText(activity).apply {
            hint = "Password"
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
        }
        val box = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            if (!onlyPassword) addView(user)
            addView(pass)
        }
        builder(prompt.title ?: "Sign in")
            .setMessage(prompt.message)
            .setView(padded(box))
            .setPositiveButton("Sign in") { _, _ ->
                result.complete(
                    if (onlyPassword) prompt.confirm(pass.text.toString())
                    else prompt.confirm(user.text.toString(), pass.text.toString()),
                )
            }
            .setNegativeButton(android.R.string.cancel) { _, _ -> result.complete(prompt.dismiss()) }
            .setOnCancelListener { result.complete(prompt.dismiss()) }
            .show()
        return result
    }

    override fun onChoicePrompt(session: GeckoSession, prompt: PromptDelegate.ChoicePrompt): GeckoResult<PromptResponse> {
        val result = GeckoResult<PromptResponse>()
        // Option groups arrive nested; a phone list shows them flattened, with
        // the group label as a disabled heading.
        val flat = ArrayList<PromptDelegate.ChoicePrompt.Choice>()
        fun walk(items: Array<PromptDelegate.ChoicePrompt.Choice>?) {
            items?.forEach { choice ->
                if (choice.separator) return@forEach
                flat += choice
                if (choice.items != null) walk(choice.items)
            }
        }
        walk(prompt.choices)
        val labels = flat.map { c -> if (c.items != null) "— ${c.label} —" else c.label }.toTypedArray()

        val dialog = builder(prompt.title ?: prompt.message)
        when (prompt.type) {
            PromptDelegate.ChoicePrompt.Type.MULTIPLE -> {
                val checked = BooleanArray(flat.size) { flat[it].selected }
                dialog.setMultiChoiceItems(labels, checked) { _, which, isChecked -> checked[which] = isChecked }
                    .setPositiveButton(android.R.string.ok) { _, _ ->
                        val picked = flat.filterIndexed { i, c -> checked[i] && c.items == null && !c.disabled }
                        result.complete(prompt.confirm(picked.toTypedArray()))
                    }
                    .setNegativeButton(android.R.string.cancel) { _, _ -> result.complete(prompt.dismiss()) }
            }
            else -> {
                val selected = flat.indexOfFirst { it.selected }
                dialog.setSingleChoiceItems(labels, selected) { d, which ->
                    val choice = flat[which]
                    if (choice.items != null || choice.disabled) return@setSingleChoiceItems
                    d.dismiss()
                    result.complete(prompt.confirm(choice))
                }
            }
        }
        dialog.setOnCancelListener { if (!prompt.isComplete) result.complete(prompt.dismiss()) }
        dialog.show()
        return result
    }

    override fun onColorPrompt(session: GeckoSession, prompt: PromptDelegate.ColorPrompt): GeckoResult<PromptResponse> {
        val result = GeckoResult<PromptResponse>()
        val swatches = listOf(
            "#000000", "#ffffff", "#ff3b30", "#ff9500", "#ffcc00", "#34c759",
            "#00c7be", "#0a84ff", "#5856d6", "#af52de", "#ff2d55", "#8e8e93",
        )
        val input = EditText(activity).apply { setText(prompt.defaultValue ?: "#000000") }
        builder(prompt.title ?: "Pick a colour")
            .setItems(swatches.toTypedArray()) { _, which -> result.complete(prompt.confirm(swatches[which])) }
            .setView(padded(input))
            .setPositiveButton(android.R.string.ok) { _, _ ->
                val value = input.text.toString().trim()
                val ok = runCatching { Color.parseColor(value) }.isSuccess
                result.complete(if (ok) prompt.confirm(value.lowercase(Locale.ROOT)) else prompt.dismiss())
            }
            .setOnCancelListener { result.complete(prompt.dismiss()) }
            .show()
        return result
    }

    override fun onDateTimePrompt(session: GeckoSession, prompt: PromptDelegate.DateTimePrompt): GeckoResult<PromptResponse> {
        val result = GeckoResult<PromptResponse>()
        val now = Calendar.getInstance()
        val type = prompt.type
        fun pickTime(date: String?) {
            TimePickerDialog(activity, { _, h, m ->
                val time = String.format(Locale.ROOT, "%02d:%02d", h, m)
                result.complete(prompt.confirm(if (date != null) "${date}T$time" else time))
            }, now.get(Calendar.HOUR_OF_DAY), now.get(Calendar.MINUTE), true).apply {
                setOnCancelListener { result.complete(prompt.dismiss()) }
            }.show()
        }
        if (type == PromptDelegate.DateTimePrompt.Type.TIME) {
            pickTime(null)
            return result
        }
        DatePickerDialog(activity, { _, y, mo, d ->
            val date = String.format(Locale.ROOT, "%04d-%02d-%02d", y, mo + 1, d)
            when (type) {
                PromptDelegate.DateTimePrompt.Type.DATETIME_LOCAL -> pickTime(date)
                PromptDelegate.DateTimePrompt.Type.MONTH ->
                    result.complete(prompt.confirm(String.format(Locale.ROOT, "%04d-%02d", y, mo + 1)))
                PromptDelegate.DateTimePrompt.Type.WEEK -> {
                    val c = Calendar.getInstance().apply { set(y, mo, d) }
                    result.complete(
                        prompt.confirm(
                            String.format(Locale.ROOT, "%04d-W%02d", c.getWeekYear(), c.get(Calendar.WEEK_OF_YEAR)),
                        ),
                    )
                }
                else -> result.complete(prompt.confirm(date))
            }
        }, now.get(Calendar.YEAR), now.get(Calendar.MONTH), now.get(Calendar.DAY_OF_MONTH)).apply {
            setOnCancelListener { result.complete(prompt.dismiss()) }
        }.show()
        return result
    }

    override fun onFilePrompt(session: GeckoSession, prompt: PromptDelegate.FilePrompt): GeckoResult<PromptResponse> {
        val result = GeckoResult<PromptResponse>()
        val types = prompt.mimeTypes?.filter { it.contains('/') }?.toTypedArray()
            ?.takeIf { it.isNotEmpty() } ?: arrayOf("*/*")
        pickFiles(types, prompt.type == PromptDelegate.FilePrompt.Type.MULTIPLE) { uris ->
            result.complete(
                when {
                    uris.isEmpty() -> prompt.dismiss()
                    uris.size == 1 -> prompt.confirm(activity, uris[0])
                    else -> prompt.confirm(activity, uris.toTypedArray())
                },
            )
        }
        return result
    }

    override fun onPopupPrompt(session: GeckoSession, prompt: PromptDelegate.PopupPrompt): GeckoResult<PromptResponse> {
        // Popups opened without a tap are blocked by Gecko before this is
        // asked; what reaches here the page asked for while the user was
        // using it. Allowed, as Firefox for Android does.
        return GeckoResult.fromValue(prompt.confirm(AllowOrDeny.ALLOW))
    }

    override fun onBeforeUnloadPrompt(
        session: GeckoSession,
        prompt: PromptDelegate.BeforeUnloadPrompt,
    ): GeckoResult<PromptResponse> {
        val result = GeckoResult<PromptResponse>()
        builder("Leave this page?")
            .setMessage("Changes you made may not be saved.")
            .setPositiveButton("Leave") { _, _ -> result.complete(prompt.confirm(AllowOrDeny.ALLOW)) }
            .setNegativeButton("Stay") { _, _ -> result.complete(prompt.confirm(AllowOrDeny.DENY)) }
            .setOnCancelListener { result.complete(prompt.confirm(AllowOrDeny.DENY)) }
            .show()
        return result
    }

    override fun onRepostConfirmPrompt(
        session: GeckoSession,
        prompt: PromptDelegate.RepostConfirmPrompt,
    ): GeckoResult<PromptResponse> {
        val result = GeckoResult<PromptResponse>()
        builder("Resend form?")
            .setMessage("This page will send the data you entered again.")
            .setPositiveButton("Resend") { _, _ -> result.complete(prompt.confirm(AllowOrDeny.ALLOW)) }
            .setNegativeButton(android.R.string.cancel) { _, _ -> result.complete(prompt.confirm(AllowOrDeny.DENY)) }
            .setOnCancelListener { result.complete(prompt.confirm(AllowOrDeny.DENY)) }
            .show()
        return result
    }

    override fun onSharePrompt(session: GeckoSession, prompt: PromptDelegate.SharePrompt): GeckoResult<PromptResponse> {
        val text = listOfNotNull(prompt.title, prompt.text, prompt.uri).joinToString("\n")
        val send = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_TEXT, text)
        }
        return try {
            activity.startActivity(Intent.createChooser(send, null))
            GeckoResult.fromValue(prompt.confirm(PromptDelegate.SharePrompt.Result.SUCCESS))
        } catch (e: Exception) {
            GeckoResult.fromValue(prompt.confirm(PromptDelegate.SharePrompt.Result.FAILURE))
        }
    }

    private fun padded(view: android.view.View): LinearLayout = LinearLayout(activity).apply {
        setPadding(dp(20), dp(8), dp(20), 0)
        addView(view, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
    }
}
