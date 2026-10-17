package com.wifi.filetransfer;

import org.junit.Assert;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.File;

public class HttpServerStorageTest {

    @Rule
    public TemporaryFolder tempFolder = new TemporaryFolder();

    @Test
    public void testGetStorageInfo_NullFile() {
        HttpServer.StorageInfo info = HttpServer.getStorageInfo(null);
        Assert.assertNotNull(info);
        Assert.assertEquals(0, info.totalSpace);
        Assert.assertEquals(0, info.freeSpace);
        Assert.assertEquals(0, info.usedSpace);
    }

    @Test
    public void testGetStorageInfo_ValidDirectory() {
        File folder = tempFolder.getRoot();
        HttpServer.StorageInfo info = HttpServer.getStorageInfo(folder);
        Assert.assertNotNull(info);
        Assert.assertTrue(info.totalSpace >= 0);
        Assert.assertTrue(info.freeSpace >= 0);
        Assert.assertTrue(info.usedSpace >= 0);
        Assert.assertTrue(info.totalSpace >= info.freeSpace);
        Assert.assertEquals(info.totalSpace - info.freeSpace, info.usedSpace);
    }
}
