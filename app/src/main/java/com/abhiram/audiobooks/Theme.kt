package com.abhiram.audiobooks

import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.wear.compose.material.Colors
import androidx.wear.compose.material.MaterialTheme

private val Amber = Color(0xFFFFC24B)
private val Teal = Color(0xFF4ED8C4)
private val Ink = Color(0xFF14151A)

/** Black background, not dark grey: OLED pixels stay off, which is the battery win on a watch. */
private val BooksColors = Colors(
    primary = Amber,
    primaryVariant = Color(0xFFE0A22E),
    secondary = Teal,
    secondaryVariant = Color(0xFF35A697),
    background = Color.Black,
    surface = Ink,
    error = Color(0xFFFF6B6B),
    onPrimary = Color(0xFF221800),
    onSecondary = Color(0xFF00201C),
    onBackground = Color(0xFFF2F2F4),
    onSurface = Color(0xFFE9EAEE),
    onSurfaceVariant = Color(0xFF9CA2AC),
    onError = Color(0xFF2A0000),
)

/** Each book keeps the same hue across launches, so the list is scannable by colour alone. */
private val ACCENTS = listOf(
    Amber,
    Teal,
    Color(0xFF7FB2FF),
    Color(0xFFC79BFF),
    Color(0xFFFF9E80),
    Color(0xFF9CE37D),
)

fun accentFor(key: String): Color = ACCENTS[(key.hashCode().toUInt() % ACCENTS.size.toUInt()).toInt()]

@Composable
fun BooksTheme(content: @Composable () -> Unit) = MaterialTheme(colors = BooksColors, content = content)
