package com.grabbly.downloader.data

/** The sites Grabbly downloads from (the same list as the server's PLATFORMS in server/downloader.py). */
enum class Platform(
    val key: String,
    val displayName: String,
    /** Android package names, tried in order, to open the app so you can copy a link */
    val appPackages: List<String>,
    val website: String,
) {
    TikTok("tiktok", "TikTok", listOf("com.zhiliaoapp.musically", "com.ss.android.ugc.trill"), "https://www.tiktok.com"),
    Instagram("instagram", "Instagram", listOf("com.instagram.android"), "https://www.instagram.com"),
    Facebook("facebook", "Facebook", listOf("com.facebook.katana"), "https://www.facebook.com"),
    X("x", "X", listOf("com.twitter.android"), "https://x.com"),
    Pinterest("pinterest", "Pinterest", listOf("com.pinterest"), "https://www.pinterest.com"),
    Twitch("twitch", "Twitch", listOf("tv.twitch.android.app"), "https://www.twitch.tv"),
    Tumblr("tumblr", "Tumblr", listOf("com.tumblr"), "https://www.tumblr.com");

    companion object {
        fun fromKey(key: String?): Platform? = entries.firstOrNull { it.key == key }

        /** The five most used, shown first and on the welcome screen. */
        val featured: List<Platform> get() = listOf(TikTok, Instagram, Facebook, X, Pinterest)

        /** "TikTok, Instagram, … and Tumblr" */
        val allNames: String get() = entries.dropLast(1).joinToString(", ") { it.displayName } + " and " + entries.last().displayName
    }
}

sealed interface LinkCheck {
    data object Empty : LinkCheck
    data object Invalid : LinkCheck
    data class Unsupported(val host: String) : LinkCheck
    data class Supported(val url: String, val platform: Platform) : LinkCheck
}

/** Links found in pasted text: the supported ones (each once, at most [LinkDetector.MAX_LINKS]) and how many others. */
data class FoundLinks(val supported: List<LinkCheck.Supported>, val unsupported: Int, val tooMany: Boolean)

/**
 * Finds a supported video link in pasted or shared text, such as "Look at this! https://vm.tiktok.com/…".
 * Uses the same allowlist as the server (server/downloader.py) and the website.
 */
object LinkDetector {
    private val urlInText = Regex("""https?://[^\s<>"']+|(?:[a-z0-9-]+\.)+[a-z]{2,}/[^\s<>"']*""", RegexOption.IGNORE_CASE)
    private val authorityOf = Regex("""^https?://([^/?#]+)""", RegexOption.IGNORE_CASE)
    private val pinterestHost = Regex("""(?:^|\.)pinterest\.(?:[a-z]{2,4}|co\.[a-z]{2}|com\.[a-z]{2})$""")

    private val domains = mapOf(
        Platform.TikTok to listOf("tiktok.com"),
        Platform.Instagram to listOf("instagram.com", "instagr.am"),
        Platform.Facebook to listOf("facebook.com", "fb.com", "fb.watch"),
        Platform.X to listOf("x.com", "twitter.com", "t.co"),
        Platform.Pinterest to listOf("pin.it"),
        Platform.Twitch to listOf("twitch.tv"),
        Platform.Tumblr to listOf("tumblr.com", "tmblr.co"),
    )

    private const val MAX_LENGTH = 2048

    /** Most links downloaded in one go (the server allows 30 look-ups a minute). */
    const val MAX_LINKS = 30

    /** Every link in [text], for example several pasted one per line. */
    fun findAll(text: String): FoundLinks {
        val supported = LinkedHashMap<String, LinkCheck.Supported>()
        var unsupported = 0
        for (match in urlInText.findAll(text)) {
            when (val found = check(match.value)) {
                is LinkCheck.Supported -> if (found.url !in supported) supported[found.url] = found
                is LinkCheck.Unsupported -> unsupported++
                else -> Unit
            }
        }
        return FoundLinks(supported.values.take(MAX_LINKS), unsupported, supported.size > MAX_LINKS)
    }

    fun check(text: String): LinkCheck {
        val trimmed = text.trim()
        if (trimmed.isEmpty()) return LinkCheck.Empty
        val found = urlInText.find(trimmed)?.value?.trimEnd('.', ',', ')', ';', ']', '\'', '"')
            ?: return LinkCheck.Invalid
        val url = if (found.startsWith("http://", ignoreCase = true) || found.startsWith("https://", ignoreCase = true)) {
            found
        } else {
            "https://$found"
        }
        val authority = authorityOf.find(url)?.groupValues?.get(1) ?: return LinkCheck.Invalid
        // Like the server: no "user:password@" and no ":port"
        if (authority.contains('@') || authority.contains(':')) return LinkCheck.Invalid
        val host = authority.lowercase().trimEnd('.')
        if (!host.contains('.') || url.length > MAX_LENGTH) return LinkCheck.Invalid
        val platform = Platform.entries.firstOrNull { p ->
            domains[p].orEmpty().any { host == it || host.endsWith(".$it") } ||
                (p == Platform.Pinterest && pinterestHost.containsMatchIn(host))
        } ?: return LinkCheck.Unsupported(host)
        return LinkCheck.Supported(url, platform)
    }
}
