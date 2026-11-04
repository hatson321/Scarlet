package net.sybyline.scarlet.util;

import static org.junit.jupiter.api.Assertions.*;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class FileBackupsTest
{
    @TempDir Path directory;

    @Test void serializationFailurePreservesPreviousContent() throws Exception
    {
        Path file = directory.resolve("settings.json");
        Files.writeString(file, "{\"valid\":true}");
        assertThrows(IllegalStateException.class, () -> FileBackups.write(file.toFile(), 0, writer -> {
            writer.write("{\"partial\":");
            throw new IllegalStateException("synthetic serializer failure");
        }));
        assertEquals("{\"valid\":true}", Files.readString(file));
        try (java.util.stream.Stream<Path> files = Files.list(directory)) { assertEquals(1, files.count()); }
    }

    @Test void closeWithoutCommitAbortsAndOverlappingTransactionsDoNotMix() throws Exception
    {
        Path file = directory.resolve("state.json");
        Files.writeString(file, "old");
        try (FileBackups.AtomicWriter aborted = FileBackups.writer(file.toFile(), 0)) { aborted.write("partial"); }
        assertEquals("old", Files.readString(file));
        try (FileBackups.AtomicWriter first = FileBackups.writer(file.toFile(), 0);
             FileBackups.AtomicWriter second = FileBackups.writer(file.toFile(), 0))
        {
            first.write("{\"longerValue\":123456789}");
            second.write("{\"b\":1}");
            first.commit();
            assertEquals("{\"longerValue\":123456789}", Files.readString(file));
            second.commit();
        }
        assertEquals("{\"b\":1}", Files.readString(file));
    }

    @Test void failedByteSerializationAndReplacementPreserveExistingData() throws Exception
    {
        Path file = directory.resolve("secret.dat");
        Files.writeString(file, "original");
        assertThrows(IOException.class, () -> FileBackups.writeBytes(file.toFile(), 0, output -> {
            output.write(1); throw new IOException("synthetic write failure");
        }));
        assertEquals("original", Files.readString(file));
        Path occupied = Files.createDirectory(directory.resolve("occupied"));
        Files.writeString(occupied.resolve("sentinel"), "preserved");
        assertThrows(IOException.class, () -> FileBackups.write(occupied.toFile(), 0, writer -> writer.write("new")));
        assertEquals("preserved", Files.readString(occupied.resolve("sentinel")));
    }

    @Test void concurrentCallbacksPublishCompleteUtf8Files() throws Exception
    {
        Path file = directory.resolve("parallel.json");
        java.util.concurrent.ExecutorService executor = java.util.concurrent.Executors.newFixedThreadPool(4);
        try
        {
            java.util.List<java.util.concurrent.Future<?>> saves = new java.util.ArrayList<>();
            for (int i = 0; i < 24; i++)
            {
                final String content = "{\"value\":\"日本語-" + i + "\"}";
                saves.add(executor.submit(() -> {
                    FileBackups.write(file.toFile(), 0, writer -> writer.write(content)); return null;
                }));
            }
            for (java.util.concurrent.Future<?> save : saves) save.get(10, java.util.concurrent.TimeUnit.SECONDS);
            assertTrue(Files.readString(file).matches("\\{\"value\":\"日本語-\\d+\"}"));
        }
        finally { executor.shutdownNow(); }
    }
}
