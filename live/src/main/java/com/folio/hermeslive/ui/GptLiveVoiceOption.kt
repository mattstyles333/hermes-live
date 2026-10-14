package com.folio.hermeslive.ui

import androidx.compose.runtime.Composable
import com.folio.hermeslive.data.LiveSettings

/**
 * Settings › Voice with GPT-Live chosen: GPT-Live's own options ([GptLiveOptions]): the
 * parallel-agents switch (on by default, used only while signed in) and the voice, read-only
 * (the Hermes server sets it). Neither lives in [LiveSettings], so [settings] and [onChange] are
 * unused; the signature stays the engine blocks' shape.
 */
@Composable
fun GptLiveVoiceOption(
    @Suppress("UNUSED_PARAMETER") settings: LiveSettings,
    @Suppress("UNUSED_PARAMETER") onChange: ((LiveSettings) -> LiveSettings) -> Unit,
) {
    GptLiveOptions()
}
