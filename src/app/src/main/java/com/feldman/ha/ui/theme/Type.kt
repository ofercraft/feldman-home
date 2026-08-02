package com.feldman.ha.ui.theme

import androidx.compose.material3.Typography
import androidx.compose.ui.text.ExperimentalTextApi
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontVariation
import androidx.compose.ui.text.font.FontWeight
import com.feldman.ha.R

@OptIn(ExperimentalTextApi::class)
val googleSans =
    FontFamily(
        Font(
            resId = R.font.google_sans_flex,
            variationSettings = FontVariation.Settings(
                FontVariation.weight(450),
                FontVariation.Setting("ROND", 200f)
            )
        ),
//        Font(
//            resId = R.font.fredoka,
//            variationSettings = FontVariation.Settings(
//                FontVariation.weight(600),
//            )
//        )
    )

@OptIn(ExperimentalTextApi::class)
val googleSansBold =
    FontFamily(
        Font(
            resId = R.font.google_sans_flex,
            variationSettings = FontVariation.Settings(
                FontVariation.weight(750),
                FontVariation.Setting("ROND", 200f)
            )
        )
    )

val AppTypography = Typography(
    displayLarge = TextStyle(fontFamily = googleSansBold, fontWeight = FontWeight.ExtraBold),
    displayMedium = TextStyle(fontFamily = googleSansBold, fontWeight = FontWeight.Bold),
    displaySmall = TextStyle(fontFamily = googleSansBold, fontWeight = FontWeight.Bold),
    headlineLarge = TextStyle(fontFamily = googleSansBold, fontWeight = FontWeight.Bold),
    headlineMedium = TextStyle(fontFamily = googleSansBold, fontWeight = FontWeight.SemiBold),
    headlineSmall = TextStyle(fontFamily = googleSans, fontWeight = FontWeight.SemiBold),
    titleLarge = TextStyle(fontFamily = googleSansBold, fontWeight = FontWeight.Bold),
    titleMedium = TextStyle(fontFamily = googleSans, fontWeight = FontWeight.Medium),
    titleSmall = TextStyle(fontFamily = googleSans, fontWeight = FontWeight.Medium),
    bodyLarge = TextStyle(fontFamily = googleSans),
    bodyMedium = TextStyle(fontFamily = googleSans),
    bodySmall = TextStyle(fontFamily = googleSans),
    labelLarge = TextStyle(fontFamily = googleSans, fontWeight = FontWeight.SemiBold),
    labelMedium = TextStyle(fontFamily = googleSans, fontWeight = FontWeight.Medium),
    labelSmall = TextStyle(fontFamily = googleSans)
)
