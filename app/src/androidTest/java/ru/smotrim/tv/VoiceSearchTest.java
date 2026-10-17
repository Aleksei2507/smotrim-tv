package ru.smotrim.tv;

import android.content.Intent;
import android.speech.RecognizerIntent;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import java.util.ArrayList;
import java.util.Arrays;
import org.junit.Test;
import org.junit.runner.RunWith;
import static org.junit.Assert.*;

@RunWith(AndroidJUnit4.class)
public class VoiceSearchTest {
    @Test public void requestsRussianSpeechWithCorrectPrompt() {
        for (boolean channels : new boolean[]{false,true}) {
            Intent intent=VoiceSearch.intent(channels);
            assertEquals(RecognizerIntent.ACTION_RECOGNIZE_SPEECH,intent.getAction());
            assertEquals("ru-RU",intent.getStringExtra(RecognizerIntent.EXTRA_LANGUAGE));
            assertEquals(RecognizerIntent.LANGUAGE_MODEL_FREE_FORM,intent.getStringExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL));
            assertEquals(1,intent.getIntExtra(RecognizerIntent.EXTRA_MAX_RESULTS,0));
            assertEquals(channels?"Назовите телеканал":"Назовите фильм, сериал или шоу",intent.getStringExtra(RecognizerIntent.EXTRA_PROMPT));
        }
    }
    @Test public void acceptsFirstNonEmptyRecognitionAndLimitsLength() {
        Intent data=new Intent().putStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS,new ArrayList<>(Arrays.asList(" ",null," Пятница ","Другой канал")));
        assertEquals("Пятница",VoiceSearch.result(data));
        data.putStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS,new ArrayList<>(Arrays.asList("А".repeat(120))));
        assertEquals(100,VoiceSearch.result(data).length());
    }
    @Test public void missingOrEmptyResultKeepsSearchUnchanged() {
        assertEquals("",VoiceSearch.result(null));
        assertEquals("",VoiceSearch.result(new Intent()));
        assertEquals("",VoiceSearch.result(new Intent().putStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS,new ArrayList<>(Arrays.asList(" ")))));
    }
}
