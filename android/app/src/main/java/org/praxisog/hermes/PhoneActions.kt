package org.praxisog.hermes

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.AlarmClock
import java.net.URI
import java.util.Locale

/** Only direct user input reaches this parser; model output and notification text never do. */
internal sealed interface PhoneAction {
    data class Timer(val seconds: Int) : PhoneAction
    data class Alarm(val hour: Int, val minute: Int) : PhoneAction
    data class App(val query: String) : PhoneAction
    data class Website(val url: String) : PhoneAction
    data class Maps(val destination: String) : PhoneAction
    data class Dial(val number: String) : PhoneAction
    data class Message(val number: String, val body: String) : PhoneAction
    data class Share(val text: String) : PhoneAction
    data class Clarify(val message: String) : PhoneAction
    data object Conversation : PhoneAction
}

internal object PhoneActions {
    private val timer = Regex("(?:set |start )?(?:a )?timer(?: for)? (.+)", RegexOption.IGNORE_CASE)
    private val duration = Regex("([a-z -]+|[0-9]+) (hours?|hrs?|minutes?|mins?|seconds?|secs?)", RegexOption.IGNORE_CASE)
    private val alarm = Regex("(?:set )?(?:an? )?alarm(?: for| at)? (\\d{1,2})(?::(\\d{2}))?\\s*(a\\.?m\\.?|p\\.?m\\.?)?", RegexOption.IGNORE_CASE)
    private val numbers = listOf("zero", "one", "two", "three", "four", "five", "six", "seven", "eight", "nine", "ten", "eleven", "twelve", "thirteen", "fourteen", "fifteen", "sixteen", "seventeen", "eighteen", "nineteen")
    private fun number(value: String): Long? {
        val words = value.trim().lowercase(Locale.ROOT).replace('-', ' ').split(Regex("\\s+"))
        value.trim().toLongOrNull()?.let { return it }
        if (words.size == 1) {
            if (words[0] in setOf("a", "an")) return 1
            numbers.indexOf(words[0]).takeIf { it >= 0 }?.let { return it.toLong() }
        }
        val tens = mapOf("twenty" to 20, "thirty" to 30, "forty" to 40, "fifty" to 50, "sixty" to 60)
        val base = tens[words[0]] ?: return null
        return when {
            words.size == 1 -> base.toLong()
            words.size == 2 -> numbers.indexOf(words[1]).takeIf { it in 1..9 }?.let { (base + it).toLong() }
            else -> null
        }
    }
    fun parse(input: String): PhoneAction {
        val text = input.trim().removePrefix("Please ").removePrefix("please ").trim()
        if (text.isEmpty() || text.length > 4000) return PhoneAction.Clarify("Enter a request under 4,000 characters.")
        timer.matchEntire(text.trimEnd('.', '!', '?'))?.let { match ->
            val parts = match.groupValues[1].split(Regex("\\s+and\\s+", RegexOption.IGNORE_CASE))
            var seconds = 0L
            for (part in parts) {
                val parsed = duration.matchEntire(part) ?: return PhoneAction.Clarify("Try ‘timer for 10 minutes’ or ‘timer for 1 hour and 30 minutes’.")
                val amount = number(parsed.groupValues[1])?.takeIf { it in 0..86400 }
                    ?: return PhoneAction.Clarify("Use a timer duration between 1 second and 24 hours.")
                val unit = parsed.groupValues[2].lowercase(Locale.ROOT)
                seconds += amount * when { unit.startsWith("h") -> 3600; unit.startsWith("m") -> 60; else -> 1 }
            }
            return if (seconds in 1..86400) PhoneAction.Timer(seconds.toInt()) else PhoneAction.Clarify("Use a timer duration between 1 second and 24 hours.")
        }
        alarm.matchEntire(text.trimEnd('.', '!', '?'))?.let { match ->
            val hour = match.groupValues[1].toInt()
            val minute = match.groupValues[2].toIntOrNull() ?: 0
            val period = match.groupValues[3].lowercase(Locale.ROOT).replace(".", "")
            if (minute !in 0..59 || hour !in 0..23 || (period.isNotEmpty() && hour !in 1..12))
                return PhoneAction.Clarify("Use a valid time, such as 7:30 am or 19:30.")
            if (period.isEmpty() && hour in 1..12) return PhoneAction.Clarify("AM or PM? Try ‘alarm for 7:30 am’.")
            return PhoneAction.Alarm(if (period.isEmpty()) hour else hour % 12 + if (period == "pm") 12 else 0, minute)
        }
        Regex("(?:open|launch) (.+)", RegexOption.IGNORE_CASE).matchEntire(text)?.let {
            val rawTarget = it.groupValues[1].trim()
            val target = if (rawTarget.contains("://")) rawTarget else rawTarget.trimEnd('.', '!', '?')
            if (target.contains(":") || (!target.contains(' ') && target.contains(".")) || target.contains("/")) {
                if (Regex("^[a-zA-Z][a-zA-Z0-9+.-]*:").containsMatchIn(target) &&
                    !target.startsWith("https://", true) && !target.startsWith("http://", true))
                    return PhoneAction.Clarify("Use a complete http:// or https:// website address.")
                val url = if (target.startsWith("https://", true) || target.startsWith("http://", true)) target else "https://$target"
                val uri = runCatching { URI(url) }.getOrNull()
                return if (uri?.scheme?.lowercase(Locale.ROOT) in setOf("http", "https") && !uri?.host.isNullOrBlank() && uri?.rawUserInfo == null && (uri?.port == -1 || uri?.port in 1..65535))
                    PhoneAction.Website(url) else PhoneAction.Clarify("Use a complete http:// or https:// website address.")
            }
            return PhoneAction.App(target)
        }
        Regex("(?:navigate to|directions to|map) (.+)", RegexOption.IGNORE_CASE).matchEntire(text)?.let { return PhoneAction.Maps(it.groupValues[1]) }
        Regex("(?:call|dial) ([+0-9() -]+)", RegexOption.IGNORE_CASE).matchEntire(text)?.let {
            val number = phoneNumber(it.groupValues[1]) ?: return PhoneAction.Clarify("Use a phone number with 3–15 digits.")
            return PhoneAction.Dial(number)
        }
        Regex("(?:text|message) ([+0-9() -]+)(?:: | saying )(.+)", RegexOption.IGNORE_CASE).matchEntire(text)?.let {
            val number = phoneNumber(it.groupValues[1]) ?: return PhoneAction.Clarify("Use a phone number with 3–15 digits.")
            return PhoneAction.Message(number, it.groupValues[2])
        }
        Regex("share (.+)", RegexOption.IGNORE_CASE).matchEntire(text)?.let { return PhoneAction.Share(it.groupValues[1]) }
        // Incomplete/ambiguous device commands stay local instead of pretending to execute.
        if (Regex("^(?:(?:set|start) )?(?:an? )?(?:timer|alarm)\\b.*", RegexOption.IGNORE_CASE).matches(text))
            return PhoneAction.Clarify("Try ‘timer for 10 minutes’ or ‘alarm for 7:30 am’. Dated and recurring alarms belong in Clock.")
        if (Regex("^(?:call|dial|text|message)\\b.*", RegexOption.IGNORE_CASE).matches(text))
            return PhoneAction.Clarify("Use a phone number: ‘dial 5551234567’ or ‘text 5551234567: Running late’. Contact-name lookup is not available yet.")
        return PhoneAction.Conversation
    }
    private fun phoneNumber(value: String): String? = value.filterNot { it in " ()-" }
        .takeIf { Regex("\\+?\\d{3,15}").matches(it) }

    fun intent(action: PhoneAction): Intent? = when (action) {
        is PhoneAction.Timer -> Intent(AlarmClock.ACTION_SET_TIMER).putExtra(AlarmClock.EXTRA_LENGTH, action.seconds)
            .putExtra(AlarmClock.EXTRA_SKIP_UI, false)
        is PhoneAction.Alarm -> Intent(AlarmClock.ACTION_SET_ALARM).putExtra(AlarmClock.EXTRA_HOUR, action.hour)
            .putExtra(AlarmClock.EXTRA_MINUTES, action.minute).putExtra(AlarmClock.EXTRA_SKIP_UI, false)
        is PhoneAction.Website -> Intent(Intent.ACTION_VIEW, Uri.parse(action.url)).addCategory(Intent.CATEGORY_BROWSABLE)
        is PhoneAction.Maps -> Intent(Intent.ACTION_VIEW, Uri.parse("geo:0,0?q=${Uri.encode(action.destination)}"))
        is PhoneAction.Dial -> Intent(Intent.ACTION_DIAL, Uri.fromParts("tel", action.number, null))
        is PhoneAction.Message -> Intent(Intent.ACTION_SENDTO, Uri.fromParts("smsto", action.number, null)).putExtra("sms_body", action.body)
        is PhoneAction.Share -> Intent.createChooser(Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, action.text), "Share with")
        else -> null
    }
    fun launch(context: Context, action: PhoneAction): String? {
        val intent = intent(action) ?: return "Choose an app or a chat route."
        return try { context.startActivity(intent); null }
        catch (_: android.content.ActivityNotFoundException) { "No installed app can handle this action." }
        catch (_: SecurityException) { "Android blocked this action. Check the destination app’s permissions." }
    }
}
