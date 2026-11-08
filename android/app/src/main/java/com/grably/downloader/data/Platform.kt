package com.grably.downloader.data

/** The sites Grably downloads from (the same list as the server's PLATFORMS in server/downloader.py). */
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
    Reddit("reddit", "Reddit", listOf("com.reddit.frontpage"), "https://www.reddit.com"),
    Vimeo("vimeo", "Vimeo", listOf("com.vimeo.android.videoapp"), "https://vimeo.com"),
    Dailymotion("dailymotion", "Dailymotion", listOf("com.dailymotion.dailymotion"), "https://www.dailymotion.com"),
    Snapchat("snapchat", "Snapchat", listOf("com.snapchat.android"), "https://www.snapchat.com/spotlight"),
    Twitch("twitch", "Twitch", listOf("tv.twitch.android.app"), "https://www.twitch.tv"),
    LinkedIn("linkedin", "LinkedIn", listOf("com.linkedin.android"), "https://www.linkedin.com"),
    Tumblr("tumblr", "Tumblr", listOf("com.tumblr"), "https://www.tumblr.com"),
    SoundCloud("soundcloud", "SoundCloud", listOf("com.soundcloud.android"), "https://soundcloud.com"),
    Bilibili("bilibili", "Bilibili", listOf("tv.danmaku.bili", "com.bstar.intl"), "https://www.bilibili.tv");

    companion object {
        fun fromKey(key: String?): Platform? = entries.firstOrNull { it.key == key }

        /** The five most used, shown first and on the welcome screen. */
        val featured: List<Platform> get() = listOf(TikTok, Instagram, Facebook, X, Pinterest)

        /** "TikTok, Instagram, … and Bilibili" */
        val allNames: String get() = entries.dropLast(1).joinToString(", ") { it.displayName } + " and " + entries.last().displayName
    }
}

sealed interface LinkCheck {
    data object Empty : LinkCheck
    data object Invalid : LinkCheck
    data class Unsupported(val host: String) : LinkCheck
    data class Supported(val url: String, val platform: Platform) : LinkCheck
}

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
        Platform.Reddit to listOf("reddit.com", "redd.it"),
        Platform.Vimeo to listOf("vimeo.com"),
        Platform.Dailymotion to listOf("dailymotion.com", "dai.ly"),
        Platform.Snapchat to listOf("snapchat.com"),
        Platform.Twitch to listOf("twitch.tv"),
        Platform.LinkedIn to listOf("linkedin.com", "lnkd.in"),
        Platform.Tumblr to listOf("tumblr.com", "tmblr.co"),
        Platform.SoundCloud to listOf("soundcloud.com"),
        Platform.Bilibili to listOf("bilibili.com", "bilibili.tv", "b23.tv"),
    )

    private const val MAX_LENGTH = 2048

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
