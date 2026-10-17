package ru.smotrim.tv;
import org.junit.Test;import static org.junit.Assert.*;
public class SleepTimerTest {
 @Test public void expiresAtDeadline(){SleepTimer t=new SleepTimer();t.set(30,500);assertFalse(t.expired(1800499));assertTrue(t.expired(1800500));assertEquals(0,t.remaining(1800600));}
 @Test public void episodeAndCancel(){SleepTimer t=new SleepTimer();t.set(-1,0);assertTrue(t.afterEpisode());assertFalse(t.expired(Long.MAX_VALUE));t.clear();assertFalse(t.afterEpisode());assertEquals(0,t.remaining(1000));}
 @Test(expected=IllegalArgumentException.class) public void rejectsInvalidDuration(){new SleepTimer().set(999,0);}
}
