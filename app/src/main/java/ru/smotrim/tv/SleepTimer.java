package ru.smotrim.tv;

/** Session timer. Elapsed realtime avoids changes to the television's wall clock. */
final class SleepTimer {
    private long deadline;
    private boolean episode;
    void set(int minutes,long now){
        if(minutes!=-1&&minutes!=0&&minutes!=30&&minutes!=60&&minutes!=90)throw new IllegalArgumentException("Неизвестный таймер");
        episode=minutes==-1;deadline=minutes>0?now+minutes*60000L:0;
    }
    boolean expired(long now){return deadline>0&&now>=deadline;}
    boolean afterEpisode(){return episode;}
    long remaining(long now){return Math.max(0,deadline-now);}
    void clear(){deadline=0;episode=false;}
}
