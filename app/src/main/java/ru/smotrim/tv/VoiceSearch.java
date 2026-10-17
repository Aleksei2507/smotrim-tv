package ru.smotrim.tv;

import android.content.Intent;
import android.speech.RecognizerIntent;
import java.util.ArrayList;

final class VoiceSearch {
    static final int MOVIES = 4101, CHANNELS = 4102;

    static Intent intent(boolean channels) {
        return new Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH)
            .putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            .putExtra(RecognizerIntent.EXTRA_LANGUAGE, "ru-RU")
            .putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1)
            .putExtra(RecognizerIntent.EXTRA_PROMPT, channels ? "Назовите телеканал" : "Назовите фильм, сериал или шоу");
    }

    static String result(Intent data) {
        if (data == null) return "";
        ArrayList<String> values = data.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS);
        if (values == null) return "";
        for (String value : values) {
            if (value == null || value.trim().isEmpty()) continue;
            value = value.trim();
            return value.substring(0, Math.min(100, value.length()));
        }
        return "";
    }
}
