package ru.smotrim.tv;

import org.junit.Test;
import static org.junit.Assert.*;
import static ru.smotrim.tv.TorrentBufferPolicy.*;

public class TorrentBufferPolicyTest {
    @Test public void diskCacheReservesStorageAndFallsBackWhenFull() {
        assertEquals(2048,diskEffective(2048,4096*MIB));
        assertEquals(1024,diskEffective(2048,2500*MIB));
        assertEquals(512,diskEffective(512,4096*MIB));
        assertEquals(256,diskEffective(512,1500*MIB));
        assertEquals(256,diskEffective(512,1408*MIB));
        assertEquals(0,diskEffective(512,1408*MIB-1));
        assertEquals(0,diskEffective(512,0));
        assertEquals(0,diskEffective(512,-1));
        assertEquals(512,diskRequested(Integer.MAX_VALUE));
        assertFalse(diskSupported(96));
    }
    @Test public void diskCacheDoesNotRaiseTheRamBudget() {
        for(int mb:new int[]{256,512,1024,2048})assertEquals(224,runtimeLimitMiB(mb*MIB,true));
        assertEquals(416,runtimeLimitMiB(256*MIB,false));
    }
    @Test public void acceptsOnlyBoundedSizesAndDefaultsOldSettings() {
        for (int mb : new int[]{64,96,128,256,512}) assertEquals(mb, requested(mb));
        for (int mb : new int[]{-1,0,32,1024,Integer.MAX_VALUE}) assertEquals(96, requested(mb));
    }
    @Test public void usesRequestedSizeWhenThereIsHeadroom() {
        for (int mb : new int[]{64,96,128,256,512}) assertEquals(mb, effective(mb, 4096*MIB, 1400*MIB, 150*MIB, false));
    }
    @Test public void stepsDownWithoutIncreasingTheRequestedSize() {
        assertEquals(256, effective(512,2048*MIB,850*MIB,150*MIB,false));
        assertEquals(128, effective(512,2048*MIB,700*MIB,150*MIB,false));
        assertEquals(96, effective(512,2048*MIB,670*MIB,150*MIB,false));
        assertEquals(96, effective(96,4096*MIB,3000*MIB,100*MIB,false));
    }
    @Test public void respectsDeviceSizeAndSystemLowMemoryThreshold() {
        assertEquals(96, effective(512,1024*MIB,1000*MIB,100*MIB,false));
        assertEquals(64, effective(512,2048*MIB,900*MIB,400*MIB,false));
        assertEquals(64, effective(512,2048*MIB,1500*MIB,100*MIB,true));
    }
    @Test public void unavailableMemoryInformationUsesDefault() {
        assertEquals(64, effective(512,0,0,0,false));
        assertEquals(64, effective(512,2048*MIB,200*MIB,100*MIB,false));
    }
    @Test public void reservesSpaceForDecoderAndPlayerOnBusyTv() {
        assertEquals(64,effective(256,2048*MIB,500*MIB,150*MIB,false));
        assertEquals(64,effective(64,4096*MIB,3000*MIB,100*MIB,false));
        assertEquals(256,effective(512,2048*MIB,1600*MIB,100*MIB,false));
    }
    @Test public void engineMemoryBudgetGrowsBeyondTheCache() {
        assertEquals(224, runtimeLimitMiB(8*MIB));
        assertEquals(256, runtimeLimitMiB(96*MIB));
        assertEquals(416, runtimeLimitMiB(256*MIB));
        assertEquals(672, runtimeLimitMiB(512*MIB));
    }
}
