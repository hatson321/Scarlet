package net.sybyline.scarlet.util;

import static org.junit.jupiter.api.Assertions.*;
import java.io.RandomAccessFile;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class TailBoundedReadTest
{
    @TempDir Path directory;
    static final class Reader extends Tail
    {
        final List<String> lines = new ArrayList<>();
        final List<Boolean> preamble = new ArrayList<>();
        int overflows, rotations;
        Reader(Path file) { super(file.toFile(), StandardCharsets.UTF_8, 1, false, false, 7); }
        @Override protected void on_line(boolean old, String text) { lines.add(text); preamble.add(old); }
        @Override protected void on_overlong_line() { overflows++; }
        @Override protected void on_rotated() { rotations++; }
    }

    @Test void incompleteUtf8AndCrLfAreKeptOnceUntilCompleted() throws Exception
    {
        Path file = directory.resolve("synthetic.txt");
        byte[] bytes = "first\r\nmultibyte \u2603\r\nthird\rfourth\n".getBytes(StandardCharsets.UTF_8);
        Files.write(file, new byte[0]);
        Reader tail = new Reader(file);
        try (RandomAccessFile input = new RandomAccessFile(file.toFile(), "r"))
        {
            for (byte value : bytes)
            {
                Files.write(file, new byte[]{value}, StandardOpenOption.APPEND);
                tail.readLines(bytes.length, input);
                tail.readLines(bytes.length, input);
            }
        }
        assertEquals(List.of("first", "multibyte \u2603", "third", "fourth"), tail.lines);
        assertEquals(List.of(true, true, true, true), tail.preamble);
    }

    @Test void overlongLineIsSkippedWithoutLosingFollowingLine() throws Exception
    {
        Path file = directory.resolve("synthetic.txt");
        Files.writeString(file, "x".repeat(Tail.MAX_LINE_BYTES + 100));
        Reader tail = new Reader(file);
        try (RandomAccessFile input = new RandomAccessFile(file.toFile(), "r"))
        {
            assertEquals(0, tail.readLines(0, input));
            for (int n = 0; n < 10; n++) assertEquals(0, tail.readLines(0, input));
            assertEquals(1, tail.overflows);
            Files.writeString(file, "\nvalid\n", StandardOpenOption.APPEND);
            assertEquals(Files.size(file), tail.readLines(0, input));
        }
        assertEquals(List.of("valid"), tail.lines);
        assertEquals(List.of(false), tail.preamble);
    }

    @Test void truncationInsidePendingLineCannotCombineOldAndNewText() throws Exception
    {
        Path file = directory.resolve("synthetic.txt");
        Files.writeString(file, "old unfinished line");
        Reader tail = new Reader(file);
        try (RandomAccessFile input = new RandomAccessFile(file.toFile(), "r"))
        {
            tail.readLines(0, input);
            Files.writeString(file, "new\n", StandardOpenOption.TRUNCATE_EXISTING);
            tail.readLines(0, input);
        }
        assertEquals(List.of("new"), tail.lines);
        assertEquals(1, tail.rotations);
    }

    @Test void repeatedPartialPollsDoNotReadTheSameBytesAgain() throws Exception
    {
        Path file = directory.resolve("synthetic.txt");
        Files.writeString(file, "x".repeat(32_768));
        Reader tail = new Reader(file);
        long[] bytesRead = { 0 };
        try (RandomAccessFile input = new RandomAccessFile(file.toFile(), "r") {
            @Override public int read(byte[] buffer) throws java.io.IOException
            {
                int count = super.read(buffer);
                if (count > 0) bytesRead[0] += count;
                return count;
            }
        })
        {
            for (int n = 0; n < 20; n++) tail.readLines(0, input);
            assertEquals(32_768, bytesRead[0]);
            Files.writeString(file, "\n", StandardOpenOption.APPEND);
            tail.readLines(0, input);
            assertEquals(32_769, bytesRead[0]);
            assertEquals(1, tail.lines.size());
        }
    }
}
