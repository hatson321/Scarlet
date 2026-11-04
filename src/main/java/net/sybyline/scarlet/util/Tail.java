package net.sybyline.scarlet.util;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.charset.Charset;

/**
 * Adapted from org.apache.commons.io.input.Tailer to add preamble and loop functionality
 */
public class Tail implements Runnable
{

    public Tail(File file, Charset charset, long delayMillis, boolean end, boolean reOpen, int bufSize)
    {
        this.file = file;
        this.charset = charset;
        this.delayMillis = delayMillis;
        this.end = end;
        this.reOpen = reOpen;
        this.inbuf = new byte[bufSize];
        this.run = true;
        this.on_init();
    }

    protected final File file;
    protected final Charset charset;
    protected final long delayMillis;
    protected final boolean end;
    protected final boolean reOpen;
    protected final byte[] inbuf;
    protected volatile boolean run;
    static final int MAX_LINE_BYTES = 256 * 1024;
    private final ByteArrayOutputStream lineBuffer = new ByteArrayOutputStream(64);
    private long lineStart = -1, scannedPosition = -1;
    private boolean seenCR, overlongLine;

    protected void on_init() {}
    protected void on_fnf(FileNotFoundException fnfex) {}
    protected void on_rotated() {}
    protected void on_line(boolean inPreamble, String line) {}
    protected void on_exception(Exception ex) {}
    protected void on_eof() {}
    protected void on_loop() {}
    protected void on_overlong_line() {}

    protected boolean isRunning()
    {
        return this.run;
    }

    public void stop()
    {
        this.run = false;
    }

    @Override
    public void run()
    {
        this.tail();
    }

    protected void tail()
    {
        RandomAccessFile reader = null;
        try
        {
            long position = 0,
                 fileInitialLength = 0;
            while (this.isRunning() && reader == null)
            {
                try
                {
                    reader = new RandomAccessFile(this.file, "r");// raf mode
                }
                catch (FileNotFoundException e)
                {
                    this.on_fnf(e);
                }
                if (reader == null)
                {
                    Thread.sleep(this.delayMillis);
                }
                else
                {   // current position
                    fileInitialLength = this.file.length();
                    position = this.end ? fileInitialLength : 0;
                    reader.seek(position);
                }
            }
            while (this.isRunning())
            {
                long length = this.file.length();
                if (length < position)
                {   // rotated
                    this.on_rotated();
                    try (RandomAccessFile save = reader)
                    {
                        reader = new RandomAccessFile(this.file, "r");// raf mode
                        // rotated, read remaining then reopen
                        try
                        {
                            this.readLines(fileInitialLength, save);
                        }
                        catch (IOException ioe)
                        {
                            this.on_exception(ioe);
                        }
                        position = 0;
                        fileInitialLength = 0;
                        this.resetLineState(0);
                    }
                    catch (FileNotFoundException e)
                    {
                        this.on_fnf(e);
                        Thread.sleep(this.delayMillis);
                    }
                    continue;
                }
                // not rotated
                if (length > position)
                {   // read appended
                    position = this.readLines(fileInitialLength, reader);
                }
                // NOTE: There used to be an "else if (newer)" branch here that re-read the whole
                // file from byte 0 (resetting fileInitialLength to 0, so every re-read line was
                // treated as a *live* event) whenever the file's modified time advanced without the
                // length growing past what we'd already read. On Linux, VRChat running under Proton
                // bumps the log's mtime without exposing new readable bytes, so this fired
                // repeatedly and re-announced every join in the log over and over, for hours.
                // Genuine rotation/truncation to a shorter file is already handled by the
                // "length < position" case above, and VRChat session logs are append-only (a new
                // launch writes a brand-new timestamped file), so a same-length in-place overwrite
                // does not occur for them. The branch is removed.
                if (this.reOpen && reader != null)
                {
                    reader.close();
                }
                if (this.isRunning())
                {
                    this.on_loop();
                }
                if (this.isRunning())
                {
                    Thread.sleep(this.delayMillis);
                }
                if (this.isRunning() && this.reOpen)
                {
                    reader = new RandomAccessFile(this.file, "r");// raf mode
                    reader.seek(position);
                }
            }
        }
        catch (InterruptedException e)
        {
            Thread.currentThread().interrupt();
            this.on_exception(e);
        }
        catch (Exception e)
        {
            this.on_exception(e);
        }
        finally
        {
            this.run = false;
            try
            {
                if (reader != null)
                {
                    reader.close();
                }
            }
            catch (IOException e)
            {
                this.on_exception(e);
            }
        }
    }

    /**
     * Read new lines.
     *
     * @param fileInitialLength The length of the file when it was initially opened or zero
     * @param reader The file to read
     * @return The new position after the lines have been read
     * @throws java.io.IOException if an I/O error occurs.
     */
    protected long readLines(long fileInitialLength, RandomAccessFile reader) throws IOException
    {
        long requestedPosition = reader.getFilePointer();
        long length = reader.length();
        if (length < this.scannedPosition)
        {
            this.on_rotated();
            requestedPosition = 0;
            this.resetLineState(0);
        }
        else if (requestedPosition != this.lineStart)
            this.resetLineState(requestedPosition);
        reader.seek(this.scannedPosition);
        int num;
        while (this.isRunning() && ((num = reader.read(this.inbuf)) != -1))
        {
            long start = this.scannedPosition;
            for (int i = 0; i < num; i++)
            {
                byte ch = this.inbuf[i];
                switch (ch)
                {
                case 0x0a: // LF, including CRLF split between reads
                    this.finishLine(fileInitialLength, start + i + 1);
                    break;
                case 0x0d: // CR
                    if (this.seenCR) this.appendLineByte(0x0d);
                    this.seenCR = true;
                    break;
                default:
                    if (this.seenCR)
                    {
                        // This byte belongs to the next line, not the CR-terminated one.
                        this.finishLine(fileInitialLength, start + i);
                    }
                    this.appendLineByte(ch);
                }
            }
            this.scannedPosition = reader.getFilePointer();
        }
        // Keep the incomplete-line position visible to health checks, but retain the
        // bounded pending bytes so the next poll reads only the newly appended suffix.
        reader.seek(this.lineStart);
        this.on_eof();
        return this.lineStart;
    }

    private void resetLineState(long position)
    {
        this.lineBuffer.reset();
        this.lineStart = this.scannedPosition = position;
        this.seenCR = this.overlongLine = false;
    }

    private void appendLineByte(int value)
    {
        if (this.overlongLine) return;
        if (this.lineBuffer.size() >= MAX_LINE_BYTES)
        {
            this.overlongLine = true;
            this.lineBuffer.reset();
            this.on_overlong_line();
        }
        else this.lineBuffer.write(value);
    }

    private void finishLine(long fileInitialLength, long nextStart)
    {
        if (!this.overlongLine)
            this.on_line(this.lineStart < fileInitialLength, new String(this.lineBuffer.toByteArray(), this.charset));
        this.lineBuffer.reset();
        this.lineStart = nextStart;
        this.seenCR = this.overlongLine = false;
    }

}
