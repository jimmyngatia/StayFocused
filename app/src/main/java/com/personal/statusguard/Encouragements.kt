package com.personal.statusguard

import android.content.Context
import kotlin.random.Random

/** Words shown on the lockout screen. Add your own favourites here, or in the app under "My words". */
object Encouragements {
    /** A random encouragement: about half the time one of your own whys or quotes, if you have any. */
    fun pick(context: Context): Pair<String, String> {
        val words = Words(context)
        val mine = words.whys.map { it to "Your why" } + words.quotes.map { it to "My words" }
        return if (mine.isNotEmpty() && Random.nextBoolean()) mine.random() else quotes.random()
    }

    val headings = listOf(
        "Take a breath.",
        "Not now — and that's okay.",
        "Your time is precious.",
        "You chose this. Good choice.",
        "Eyes up. Life is out there.",
        "Pole pole, rafiki.",
        "Guard your heart.",
    )

    val quotes = listOf(
        "Teach us to number our days, that we may gain a heart of wisdom." to "Psalm 90:12",
        "Be still, and know that I am God." to "Psalm 46:10",
        "Whatever you do, work at it with all your heart, as working for the Lord." to "Colossians 3:23",
        "Above all else, guard your heart, for everything you do flows from it." to "Proverbs 4:23",
        "Let your eyes look straight ahead; fix your gaze directly before you." to "Proverbs 4:25",
        "Making the most of every opportunity, because the days are evil." to "Ephesians 5:16",
        "I can do all things through Christ who strengthens me." to "Philippians 4:13",
        "Whatever is true, whatever is noble, whatever is right... think about such things." to "Philippians 4:8",
        "There is a time for everything, and a season for every activity under the heavens." to "Ecclesiastes 3:1",
        "Haba na haba hujaza kibaba. Little by little fills the measure." to "Swahili proverb",
        "Pole pole ndio mwendo. Slowly, slowly is the way to go." to "Swahili proverb",
        "Usipoziba ufa utajenga ukuta. Fix the crack now, or rebuild the wall later." to "Swahili proverb",
        "Mwenye nia ana njia. Where there is a will, there is a way." to "Swahili proverb",
        "Bandu bandu huisha gogo. Chip by chip, the log is finished." to "Swahili proverb",
        "You will never find time for anything. If you want time, you must make it." to "Charles Buxton",
        "What you stay focused on will grow." to "Roy T. Bennett",
        "The way we spend our days is, of course, the way we spend our lives." to "Annie Dillard",
        "Lost time is never found again." to "Benjamin Franklin",
        "Discipline is choosing between what you want now and what you want most." to "Abraham Lincoln (attributed)",
        "We are what we repeatedly do. Excellence, then, is not an act, but a habit." to "Will Durant",
        "Comparison is the thief of joy." to "Theodore Roosevelt",
        "Almost everything will work again if you unplug it for a few minutes, including you." to "Anne Lamott",
        "Their story is not your story. Go write yours." to "Status Guard",
        "Nobody's status will remember the hours you gave it. Your goals will." to "Status Guard",
        "Curiosity can wait. Your future can't." to "Status Guard",
        "Five minutes from now you won't miss what you didn't see." to "Status Guard",
        "Call someone you love instead of watching them." to "Status Guard",
        "Small wins every day. This is one of them." to "Status Guard",
    )
}
