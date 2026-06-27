package app.notmumla.ui

import androidx.compose.ui.graphics.Color

/** Sample data mirroring the design mockups, used to render M1 screens. */
object MockData {

    private val orange = Color(0xFFE0613A)
    private val green = Color(0xFF1F8A5B)
    private val purple = Color(0xFF7C5CFF)
    private val gold = Color(0xFFA98600)
    private val blue = Color(0xFF2A6FDB)

    val you = UiUser(0, "You", "YO", blue, isYou = true)

    val generalUsers = listOf(
        you,
        UiUser(1, "Sarah Kim", "SK", orange, UserStatus.SPEAKING),
        UiUser(2, "Marcus Vogel", "MV", green, UserStatus.SPEAKING),
        UiUser(3, "Priya N.", "PN", purple, UserStatus.MUTED),
        UiUser(4, "Devin Cole", "DC", gold, UserStatus.AFK),
    )

    val channels = listOf(
        UiChannel(0, "General", depth = 0, users = generalUsers, isCurrent = true),
        UiChannel(1, "Gaming", depth = 0, users = listOf(
            UiUser(5, "Alex", "AL", purple),
            UiUser(6, "Sam", "SM", orange),
        )),
        UiChannel(2, "Valorant", depth = 1, users = listOf(
            UiUser(7, "Lena", "LN", green),
            UiUser(8, "Tom", "TM", blue),
        )),
        UiChannel(3, "Minecraft", depth = 1),
        UiChannel(4, "Music & Chill", depth = 0, users = listOf(UiUser(9, "Jo", "JO", green))),
        UiChannel(5, "Private", depth = 0, locked = true),
        UiChannel(6, "AFK", depth = 0),
    )

    val servers = listOf(
        UiServer(1, "example.com", 64738, "example.com", "E", green, 12, 18, online = true),
        UiServer(2, "raidnight.gg", 64738, "raidnight.gg", "R", purple, 3, 41, online = false),
    )

    val messages = listOf(
        UiMessage(0, ChatKind.SYSTEM, text = "Sarah Kim joined General"),
        UiMessage(1, ChatKind.OTHER, "Sarah Kim", "SK", orange, "9:24", "hey, ready for the standup?"),
        UiMessage(2, ChatKind.ME, text = "yep, joining now"),
        UiMessage(3, ChatKind.OTHER, "Marcus Vogel", "MV", green, "9:25", "pushed the fix, take a look"),
        UiMessage(4, ChatKind.FILE, "Marcus Vogel", "MV", green, "9:26",
            fileName = "release-notes-1.4.2.pdf", fileSize = "248 KB"),
        UiMessage(5, ChatKind.ME, text = "nice, looks good 🚀"),
    )
}
