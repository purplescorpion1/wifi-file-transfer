package com.wifi.filetransfer;

import org.junit.Assert;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.File;
import java.io.IOException;

public class HttpServerUploadTest {

    @Rule
    public TemporaryFolder tempFolder = new TemporaryFolder();

    @Test
    public void testResolveUploadFile_WhenFileDoesNotExist() {
        File dir = tempFolder.getRoot();
        String filename = "test.txt";

        File resolved = HttpServer.resolveUploadFile(dir, filename, false);
        Assert.assertEquals(new File(dir, "test.txt"), resolved);
    }

    @Test
    public void testResolveUploadFile_WithReplaceTrue() throws IOException {
        File dir = tempFolder.getRoot();
        String filename = "test.txt";
        File existing = new File(dir, filename);
        existing.createNewFile();

        File resolved = HttpServer.resolveUploadFile(dir, filename, true);
        Assert.assertEquals(existing, resolved);
    }

    @Test
    public void testResolveUploadFile_WithReplaceFalse_IncrementsFileName() throws IOException {
        File dir = tempFolder.getRoot();
        String filename = "photo.jpg";

        File file1 = new File(dir, "photo.jpg");
        file1.createNewFile();

        File resolved1 = HttpServer.resolveUploadFile(dir, filename, false);
        Assert.assertEquals(new File(dir, "photo (1).jpg"), resolved1);

        File file2 = new File(dir, "photo (1).jpg");
        file2.createNewFile();

        File resolved2 = HttpServer.resolveUploadFile(dir, filename, false);
        Assert.assertEquals(new File(dir, "photo (2).jpg"), resolved2);

        File file3 = new File(dir, "photo (2).jpg");
        file3.createNewFile();

        File resolved3 = HttpServer.resolveUploadFile(dir, filename, false);
        Assert.assertEquals(new File(dir, "photo (3).jpg"), resolved3);
    }

    @Test
    public void testResolveUploadFile_NoExtension() throws IOException {
        File dir = tempFolder.getRoot();
        String filename = "README";
        File file1 = new File(dir, filename);
        file1.createNewFile();

        File resolved = HttpServer.resolveUploadFile(dir, filename, false);
        Assert.assertEquals(new File(dir, "README (1)"), resolved);
    }

    @Test
    public void testResolveUploadFile_MultipleDots() throws IOException {
        File dir = tempFolder.getRoot();
        String filename = "archive.tar.gz";
        File file1 = new File(dir, filename);
        file1.createNewFile();

        File resolved = HttpServer.resolveUploadFile(dir, filename, false);
        Assert.assertEquals(new File(dir, "archive.tar (1).gz"), resolved);
    }
}
