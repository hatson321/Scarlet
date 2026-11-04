package net.sybyline.scarlet.util;

import java.io.BufferedInputStream;
import java.io.ByteArrayOutputStream;
import java.io.EOFException;
import java.io.FilterInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.io.OutputStreamWriter;
import java.io.Reader;
import java.io.Writer;
import java.lang.reflect.Type;
import java.net.HttpURLConnection;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ProtocolException;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.Socket;
import java.net.URL;
import java.security.KeyStore;
import java.security.cert.CertificateFactory;
import java.security.cert.X509Certificate;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.Predicate;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;

import javax.net.ssl.HttpsURLConnection;
import javax.net.ssl.SSLParameters;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLSocket;
import javax.net.ssl.TrustManager;
import javax.net.ssl.TrustManagerFactory;
import javax.net.ssl.X509TrustManager;

import com.google.gson.Gson;
import com.google.gson.reflect.TypeToken;
import com.google.gson.stream.JsonReader;
import com.google.gson.stream.JsonWriter;

import net.sybyline.scarlet.Scarlet;

public class HttpURLInputStream extends FilterInputStream
{
    private static final int MAX_REDIRECTS = 5;

    public static final Predicate<String> PUBLIC_ONLY = HttpURLInputStream::isPublicHttpUrl;

    public static final Func.V1<IOException, HttpURLConnection> DISABLE_REDIRECTS = $ -> $.setInstanceFollowRedirects(false);

    public static HttpURLInputStream get(String url) throws IOException
    {
        return of(url, null, null, null);
    }
    public static HttpURLInputStream get(String url, Func.V1<IOException, HttpURLConnection> init) throws IOException
    {
        return of(url, null, init, null);
    }
    public static HttpURLInputStream get(String url, Predicate<String> validator) throws IOException
    {
        return of(url, null, null, null, validator);
    }
    public static HttpURLInputStream get(String url, Func.V1<IOException, HttpURLConnection> init, Predicate<String> validator) throws IOException
    {
        return of(url, null, init, null, validator);
    }

    public static HttpURLInputStream head(String url) throws IOException
    {
        return of(url, "HEAD", null, null);
    }
    public static HttpURLInputStream head(String url, Func.V1<IOException, HttpURLConnection> init) throws IOException
    {
        return of(url, "HEAD", init, null);
    }

    public static HttpURLInputStream post(String url) throws IOException
    {
        return of(url, "POST", null, null);
    }
    public static HttpURLInputStream post(String url, Func.V1<IOException, OutputStream> send) throws IOException
    {
        return of(url, "POST", null, send);
    }
    public static HttpURLInputStream post(String url, Func.V1<IOException, HttpURLConnection> init, Func.V1<IOException, OutputStream> send) throws IOException
    {
        return of(url, "POST", init, send);
    }

    public static HttpURLInputStream put(String url, Func.V1<IOException, OutputStream> send) throws IOException
    {
        return of(url, "PUT", null, send);
    }
    public static HttpURLInputStream put(String url, Func.V1<IOException, HttpURLConnection> init, Func.V1<IOException, OutputStream> send) throws IOException
    {
        return of(url, "PUT", init, send);
    }

    public static HttpURLInputStream delete(String url) throws IOException
    {
        return of(url, "DELETE", null, null);
    }
    public static HttpURLInputStream delete(String url, Func.V1<IOException, OutputStream> send) throws IOException
    {
        return of(url, "DELETE", null, send);
    }
    public static HttpURLInputStream delete(String url, Func.V1<IOException, HttpURLConnection> init, Func.V1<IOException, OutputStream> send) throws IOException
    {
        return of(url, "DELETE", init, send);
    }

    @Deprecated
    public static HttpURLInputStream patch(String url, Func.V1<IOException, OutputStream> send) throws IOException
    {
        return of(url, "PATCH", null, send);
    }
    @Deprecated
    public static HttpURLInputStream patch(String url, Func.V1<IOException, HttpURLConnection> init, Func.V1<IOException, OutputStream> send) throws IOException
    {
        return of(url, "PATCH", init, send);
    }

    public static HttpURLInputStream of(String url, String method, Func.V1<IOException, HttpURLConnection> init, Func.V1<IOException, OutputStream> send) throws IOException
    {
        return of(url, method, init, send, null);
    }
    public static HttpURLInputStream of(String url, String method, Func.V1<IOException, HttpURLConnection> init, Func.V1<IOException, OutputStream> send, Predicate<String> validator) throws IOException
    {
        HttpURLConnection connection = openConnection(url, method, init, validator);
        try
        {
            if (send != null)
            {
                connection.setDoOutput(true);
                try (OutputStream out = connection.getOutputStream())
                {
                    send.invoke(out);
                    out.flush();
                }
            }
            return new HttpURLInputStream(connection);
        }
        catch (IOException ioex)
        {
            connection.disconnect();
            throw ioex;
        }
    }

    public static HttpURLConnection openConnection(String url, String method, Func.V1<IOException, HttpURLConnection> init) throws IOException
    {
        return openConnection(url, method, init, null);
    }
    public static HttpURLConnection openConnection(String url, String method, Func.V1<IOException, HttpURLConnection> init, Predicate<String> validator) throws IOException
    {
        HttpURLConnection connection = openSingleConnection(url, method, init, validator);
        if (validator == null || !canSafelyFollowRedirects(method))
            return connection;
        return followRedirects(connection, method, init, validator);
    }

    private static HttpURLConnection openSingleConnection(String url, String method, Func.V1<IOException, HttpURLConnection> init, Predicate<String> validator) throws IOException
    {
        if (validator == PUBLIC_ONLY)
            return openPinnedPublicConnection(url, method, init);
        if (validator != null && !validator.test(url))
            throw new IOException("Blocked unsafe URL: " + url);
        URL url0 = new URL(url);
        HttpURLConnection connection = (HttpURLConnection)url0.openConnection();
        if (validator != null)
            connection.setInstanceFollowRedirects(false);
        if (connection instanceof HttpsURLConnection)
            applyCompatTls((HttpsURLConnection)connection);
        connection.setRequestProperty("User-Agent", Scarlet.USER_AGENT);
        connection.setConnectTimeout(5_000);
        connection.setReadTimeout(5_000);
        if (method != null)
            connection.setRequestMethod(method);
        try
        {
            if (init != null)
                init.invoke(connection);
            return connection;
        }
        catch (IOException ioex)
        {
            connection.disconnect();
            throw ioex;
        }
    }

    private static HttpURLConnection openPinnedPublicConnection(String url, String method, Func.V1<IOException, HttpURLConnection> init) throws IOException
    {
        PublicUrlTarget target = resolvePublicUrlTarget(url);
        HttpURLConnection connection = new PinnedPublicHttpURLConnection(target);
        connection.setInstanceFollowRedirects(false);
        connection.setRequestProperty("User-Agent", Scarlet.USER_AGENT);
        connection.setConnectTimeout(5_000);
        connection.setReadTimeout(5_000);
        if (method != null)
            connection.setRequestMethod(method);
        try
        {
            if (init != null)
                init.invoke(connection);
            return connection;
        }
        catch (IOException ioex)
        {
            connection.disconnect();
            throw ioex;
        }
    }

    private static boolean canSafelyFollowRedirects(String method)
    {
        return method == null || "GET".equalsIgnoreCase(method) || "HEAD".equalsIgnoreCase(method);
    }

    private static HttpURLConnection followRedirects(HttpURLConnection connection, String method, Func.V1<IOException, HttpURLConnection> init, Predicate<String> validator) throws IOException
    {
        for (int redirects = 0; ; redirects++)
        {
            int status;
            try
            {
                status = connection.getResponseCode();
            }
            catch (IOException ioex)
            {
                connection.disconnect();
                throw ioex;
            }
            if (!isRedirect(status))
                return connection;

            String location = connection.getHeaderField("Location");
            if (location == null || location.trim().isEmpty())
                return connection;
            if (redirects >= MAX_REDIRECTS)
            {
                connection.disconnect();
                throw new IOException("Too many redirects for URL: " + connection.getURL());
            }

            String next;
            try
            {
                next = resolveRedirectUrl(connection.getURL(), location, validator);
            }
            finally
            {
                connection.disconnect();
            }
            connection = openSingleConnection(next, method, init, validator);
        }
    }

    private static boolean isRedirect(int status)
    {
        return status == HttpURLConnection.HTTP_MOVED_PERM
            || status == HttpURLConnection.HTTP_MOVED_TEMP
            || status == HttpURLConnection.HTTP_SEE_OTHER
            || status == 307
            || status == 308;
    }

    static String resolveRedirectUrl(URL currentUrl, String location, Predicate<String> validator) throws IOException
    {
        String next = new URL(currentUrl, location).toExternalForm();
        if (validator != null && !validator.test(next))
            throw new IOException("Blocked unsafe redirect URL: " + next);
        return next;
    }

    protected HttpURLInputStream(HttpURLConnection connection) throws IOException
    {
        super(connection.getInputStream());
        this.connection = connection;
    }

    private final HttpURLConnection connection;

    public HttpURLConnection connection()
    {
        return this.connection;
    }

    @Override
    public void close() throws IOException
    {
        try
        {
            super.close();
        }
        finally
        {
            this.connection.disconnect();
        }
    }

    static boolean isPublicHttpUrl(String url)
    {
        try
        {
            resolvePublicUrlTarget(url);
            return true;
        }
        catch (Exception ex)
        {
            return false;
        }
    }

    static PublicUrlTarget resolvePublicUrlTarget(String url) throws IOException
    {
        try
        {
            URI uri = new URI(url == null ? "" : url.trim());
            String scheme = uri.getScheme();
            if (!"http".equalsIgnoreCase(scheme) && !"https".equalsIgnoreCase(scheme))
                throw new IOException("URL scheme is not http or https: " + url);
            if (!uri.isAbsolute() || uri.isOpaque() || uri.getAuthority() == null)
                throw new IOException("URL must be hierarchical with an authority: " + url);
            if (uri.getRawUserInfo() != null)
                throw new IOException("URL userinfo is not allowed: " + url);
            String host = uri.getHost();
            if (host == null || host.isEmpty())
                throw new IOException("URL host is missing: " + url);
            InetAddress[] addresses = InetAddress.getAllByName(host);
            if (addresses.length == 0)
                throw new IOException("URL host did not resolve: " + host);
            for (InetAddress address : addresses)
            {
                if (!isPublicAddress(address))
                    throw new IOException("Blocked unsafe resolved address for " + host + ": " + address.getHostAddress());
            }
            return new PublicUrlTarget(uri.toURL(), host, addresses[0]);
        }
        catch (URISyntaxException ex)
        {
            throw new IOException("Invalid URL: " + url, ex);
        }
        catch (Exception ex)
        {
            if (ex instanceof IOException)
                throw (IOException)ex;
            throw new IOException("Invalid public URL: " + url, ex);
        }
    }

    static boolean isPublicAddress(InetAddress address)
    {
        if (address == null)
            return false;
        if (address.isAnyLocalAddress() || address.isLoopbackAddress() || address.isSiteLocalAddress() || address.isLinkLocalAddress() || address.isMulticastAddress())
            return false;
        byte[] bytes = address.getAddress();
        if (bytes.length == 16
            && (bytes[0]&0xFF)==0x00 && (bytes[1]&0xFF)==0x64 && (bytes[2]&0xFF)==0xFF && (bytes[3]&0xFF)==0x9B
            && bytes[4]==0 && bytes[5]==0 && bytes[6]==0 && bytes[7]==0
            && bytes[8]==0 && bytes[9]==0 && bytes[10]==0 && bytes[11]==0)
            // NAT64 (64:ff9b::/96) embeds an IPv4 address; reject so an internal IPv4 can't be reached via IPv6.
            return false;
        if (bytes.length == 4)
        {
            int b0 = bytes[0] & 0xFF;
            int b1 = bytes[1] & 0xFF;
            int b2 = bytes[2] & 0xFF;
            if (b0 == 0 || b0 == 10 || b0 == 127)
                return false;
            if (b0 == 100 && 64 <= b1 && b1 <= 127)
                return false;
            if (b0 == 169 && b1 == 254)
                return false;
            if (b0 == 172 && 16 <= b1 && b1 <= 31)
                return false;
            if (b0 == 192 && b1 == 0)
                return false;
            if (b0 == 192 && b1 == 168)
                return false;
            if (b0 == 198 && (b1 == 18 || b1 == 19))
                return false;
            if (b0 == 198 && b1 == 51 && b2 == 100)
                return false;
            if (b0 == 203 && b1 == 0 && b2 == 113)
                return false;
            if (224 <= b0)
                return false;
        }
        else if (bytes.length == 16)
        {
            int b0 = bytes[0] & 0xFF;
            int b1 = bytes[1] & 0xFF;
            int b2 = bytes[2] & 0xFF;
            int b3 = bytes[3] & 0xFF;
            boolean zeroPrefix96 = true;
            for (int i = 0; i < 12; i++)
                zeroPrefix96 &= bytes[i] == 0;
            if (zeroPrefix96)
                return false;
            boolean ipv4Mapped = true;
            for (int i = 0; i < 10; i++)
                ipv4Mapped &= bytes[i] == 0;
            if (ipv4Mapped && (bytes[10] & 0xFF) == 0xFF && (bytes[11] & 0xFF) == 0xFF)
                return false;
            if ((b0 & 0xFE) == 0xFC)
                return false;
            if (b0 == 0xFE && (b1 & 0xC0) == 0x80)
                return false;
            if (b0 == 0xFF)
                return false;
            if (b0 == 0x20 && b1 == 0x01 && b2 == 0x0D && b3 == 0xB8)
                return false;
        }
        return true;
    }

    static final class PublicUrlTarget
    {
        final URL url;
        final String host;
        final InetAddress address;
        final int port;
        final boolean secure;

        PublicUrlTarget(URL url, String host, InetAddress address)
        {
            this.url = url;
            this.host = host;
            this.address = address;
            this.port = url.getPort() >= 0 ? url.getPort() : url.getDefaultPort();
            this.secure = "https".equalsIgnoreCase(url.getProtocol());
        }
    }

    private static final class PinnedPublicHttpURLConnection extends HttpURLConnection
    {
        private static final int MAX_HEADER_LINE_LENGTH = 64 * 1024;

        private final PublicUrlTarget target;
        private Socket socket;
        private InputStream responseBody;
        private Map<String, List<String>> responseHeaders = Collections.emptyMap();
        private final List<String> responseHeaderKeys = new ArrayList<>();
        private final List<String> responseHeaderValues = new ArrayList<>();

        PinnedPublicHttpURLConnection(PublicUrlTarget target)
        {
            super(target.url);
            this.target = target;
        }

        @Override
        public void connect() throws IOException
        {
            if (this.connected)
                return;
            Map<String, List<String>> requestHeaders = new LinkedHashMap<>();
            for (Map.Entry<String, List<String>> entry : this.getRequestProperties().entrySet())
                requestHeaders.put(entry.getKey(), new ArrayList<>(entry.getValue()));

            Socket socket = null;
            try
            {
                socket = this.openSocket();
                OutputStream out = socket.getOutputStream();
                this.writeRequest(out, requestHeaders);
                InputStream in = new BufferedInputStream(socket.getInputStream());
                this.readResponse(in);
                this.responseBody = this.isChunked() ? new ChunkedInputStream(in) : in;
                this.socket = socket;
                this.connected = true;
            }
            catch (IOException | RuntimeException ex)
            {
                closeSocket(socket);
                throw ex;
            }
        }

        private Socket openSocket() throws IOException
        {
            Socket plain = new Socket();
            plain.connect(new InetSocketAddress(this.target.address, this.target.port), this.getConnectTimeout());
            plain.setSoTimeout(this.getReadTimeout());
            if (!this.target.secure)
                return plain;

            SSLSocket ssl = null;
            try
            {
                ssl = (SSLSocket)sslSocketFactory().createSocket(plain, this.target.host, this.target.port, true);
                SSLParameters sslParameters = ssl.getSSLParameters();
                sslParameters.setEndpointIdentificationAlgorithm("HTTPS");
                ssl.setSSLParameters(sslParameters);
                ssl.startHandshake();
                return ssl;
            }
            catch (IOException | RuntimeException ex)
            {
                closeSocket(ssl != null ? ssl : plain);
                throw ex;
            }
        }

        private void writeRequest(OutputStream out, Map<String, List<String>> requestHeaders) throws IOException
        {
            String file = this.url.getFile();
            if (file == null || file.isEmpty())
                file = "/";
            else if (file.charAt(0) == '?')
                file = "/" + file;
            StringBuilder request = new StringBuilder(512);
            request.append(this.method == null ? "GET" : this.method).append(' ').append(file).append(" HTTP/1.1\r\n");
            request.append("Host: ").append(this.hostHeader()).append("\r\n");
            request.append("Connection: close\r\n");
            for (Map.Entry<String, List<String>> entry : requestHeaders.entrySet())
            {
                String key = entry.getKey();
                if (key == null || "host".equalsIgnoreCase(key) || "connection".equalsIgnoreCase(key))
                    continue;
                for (String value : entry.getValue())
                    if (value != null)
                        appendHeader(request, key, value);
            }
            request.append("\r\n");
            out.write(request.toString().getBytes(StandardCharsets.ISO_8859_1));
            out.flush();
        }

        private static void appendHeader(StringBuilder request, String key, String value) throws IOException
        {
            if (containsHeaderDelimiter(key) || containsHeaderDelimiter(value))
                throw new IOException("HTTP request headers must not contain CR or LF characters");
            request.append(key).append(": ").append(value).append("\r\n");
        }

        private static boolean containsHeaderDelimiter(String value)
        {
            return value.indexOf('\r') >= 0 || value.indexOf('\n') >= 0;
        }

        private String hostHeader()
        {
            String host = this.target.host.indexOf(':') >= 0 && !this.target.host.startsWith("[") ? "[" + this.target.host + "]" : this.target.host;
            int defaultPort = this.target.secure ? 443 : 80;
            if (this.target.port == defaultPort)
                return host;
            return host + ":" + this.target.port;
        }

        private void readResponse(InputStream in) throws IOException
        {
            String statusLine = readHeaderLine(in);
            if (statusLine == null)
                throw new EOFException("No HTTP response status line");
            while (statusLine.startsWith("HTTP/1.") && statusLine.length() >= 12 && statusLine.substring(9, 12).matches("1\\d\\d"))
            {
                this.readHeaders(in, statusLine);
                statusLine = readHeaderLine(in);
                if (statusLine == null)
                    throw new EOFException("No final HTTP response status line");
            }
            this.readHeaders(in, statusLine);
        }

        private void readHeaders(InputStream in, String statusLine) throws IOException
        {
            if (!statusLine.startsWith("HTTP/"))
                throw new IOException("Invalid HTTP response status line: " + statusLine);
            int firstSpace = statusLine.indexOf(' ');
            int secondSpace = firstSpace < 0 ? -1 : statusLine.indexOf(' ', firstSpace + 1);
            if (firstSpace < 0)
                throw new IOException("Invalid HTTP response status line: " + statusLine);
            String code = secondSpace < 0 ? statusLine.substring(firstSpace + 1) : statusLine.substring(firstSpace + 1, secondSpace);
            try
            {
                this.responseCode = Integer.parseInt(code);
            }
            catch (NumberFormatException ex)
            {
                throw new IOException("Invalid HTTP response status code: " + statusLine, ex);
            }
            this.responseMessage = secondSpace < 0 ? "" : statusLine.substring(secondSpace + 1);

            Map<String, List<String>> headers = new LinkedHashMap<>();
            this.responseHeaderKeys.clear();
            this.responseHeaderValues.clear();
            headers.put(null, Collections.singletonList(statusLine));
            this.responseHeaderKeys.add(null);
            this.responseHeaderValues.add(statusLine);

            for (String line; (line = readHeaderLine(in)) != null && !line.isEmpty();)
            {
                int colon = line.indexOf(':');
                if (colon <= 0)
                    continue;
                String key = line.substring(0, colon).trim();
                String value = line.substring(colon + 1).trim();
                headers.computeIfAbsent(key, $ -> new ArrayList<>()).add(value);
                this.responseHeaderKeys.add(key);
                this.responseHeaderValues.add(value);
            }
            this.responseHeaders = headers;
        }

        private boolean isChunked()
        {
            for (Map.Entry<String, List<String>> entry : this.responseHeaders.entrySet())
                if ("Transfer-Encoding".equalsIgnoreCase(entry.getKey()))
                    for (String value : entry.getValue())
                        if (value != null && value.toLowerCase(Locale.ROOT).contains("chunked"))
                            return true;
            return false;
        }

        @Override
        public InputStream getInputStream() throws IOException
        {
            this.connect();
            if (this.responseCode >= HTTP_BAD_REQUEST)
                throw new IOException("Server returned HTTP response code: " + this.responseCode + " for URL: " + this.url);
            return this.responseBody;
        }

        @Override
        public InputStream getErrorStream()
        {
            try
            {
                this.connect();
                return this.responseCode >= HTTP_BAD_REQUEST ? this.responseBody : null;
            }
            catch (IOException ex)
            {
                return null;
            }
        }

        @Override
        public OutputStream getOutputStream() throws IOException
        {
            throw new ProtocolException("Request bodies are not supported for pinned public URL connections");
        }

        @Override
        public int getResponseCode() throws IOException
        {
            this.connect();
            return this.responseCode;
        }

        @Override
        public String getResponseMessage() throws IOException
        {
            this.connect();
            return this.responseMessage;
        }

        @Override
        public String getHeaderField(String name)
        {
            try
            {
                this.connect();
            }
            catch (IOException ex)
            {
                return null;
            }
            for (Map.Entry<String, List<String>> entry : this.responseHeaders.entrySet())
            {
                if (name == null ? entry.getKey() == null : name.equalsIgnoreCase(entry.getKey()))
                {
                    List<String> values = entry.getValue();
                    return values.isEmpty() ? null : values.get(values.size() - 1);
                }
            }
            return null;
        }

        @Override
        public String getHeaderField(int n)
        {
            try
            {
                this.connect();
            }
            catch (IOException ex)
            {
                return null;
            }
            return 0 <= n && n < this.responseHeaderValues.size() ? this.responseHeaderValues.get(n) : null;
        }

        @Override
        public String getHeaderFieldKey(int n)
        {
            try
            {
                this.connect();
            }
            catch (IOException ex)
            {
                return null;
            }
            return 0 <= n && n < this.responseHeaderKeys.size() ? this.responseHeaderKeys.get(n) : null;
        }

        @Override
        public Map<String, List<String>> getHeaderFields()
        {
            try
            {
                this.connect();
            }
            catch (IOException ex)
            {
                return Collections.emptyMap();
            }
            Map<String, List<String>> copy = new LinkedHashMap<>();
            for (Map.Entry<String, List<String>> entry : this.responseHeaders.entrySet())
                copy.put(entry.getKey(), Collections.unmodifiableList(entry.getValue()));
            return Collections.unmodifiableMap(copy);
        }

        @Override
        public void disconnect()
        {
            closeSocket(this.socket);
            this.socket = null;
            this.responseBody = null;
            this.connected = false;
        }

        @Override
        public boolean usingProxy()
        {
            return false;
        }

        private static String readHeaderLine(InputStream in) throws IOException
        {
            ByteArrayOutputStream out = new ByteArrayOutputStream(128);
            for (int b; (b = in.read()) >= 0;)
            {
                if (b == '\n')
                    break;
                if (b != '\r')
                    out.write(b);
                if (out.size() > MAX_HEADER_LINE_LENGTH)
                    throw new IOException("HTTP header line is too long");
            }
            if (out.size() == 0)
                return null;
            return out.toString(StandardCharsets.ISO_8859_1.name());
        }
    }

    private static final class ChunkedInputStream extends InputStream
    {
        private final InputStream in;
        private int remaining;
        private boolean eof;
        private boolean needTerminator;

        ChunkedInputStream(InputStream in)
        {
            this.in = in;
        }

        @Override
        public int read() throws IOException
        {
            byte[] one = new byte[1];
            int read = this.read(one, 0, 1);
            return read < 0 ? -1 : one[0] & 0xFF;
        }

        @Override
        public int read(byte[] b, int off, int len) throws IOException
        {
            if (b == null)
                throw new NullPointerException("b");
            if (off < 0 || len < 0 || len > b.length - off)
                throw new IndexOutOfBoundsException();
            if (len == 0)
                return 0;
            if (this.eof)
                return -1;
            if (this.remaining == 0)
                this.nextChunk();
            if (this.eof)
                return -1;
            int read = this.in.read(b, off, Math.min(len, this.remaining));
            if (read < 0)
                throw new EOFException("Unexpected EOF inside HTTP chunk");
            this.remaining -= read;
            if (this.remaining == 0)
                this.needTerminator = true;
            return read;
        }

        private void nextChunk() throws IOException
        {
            if (this.needTerminator)
            {
                int cr = this.in.read();
                int lf = this.in.read();
                if (cr != '\r' || lf != '\n')
                    throw new IOException("Invalid HTTP chunk terminator");
                this.needTerminator = false;
            }
            String line = PinnedPublicHttpURLConnection.readHeaderLine(this.in);
            if (line == null)
                throw new EOFException("Unexpected EOF before HTTP chunk size");
            int semicolon = line.indexOf(';');
            if (semicolon >= 0)
                line = line.substring(0, semicolon);
            try
            {
                this.remaining = Integer.parseInt(line.trim(), 16);
            }
            catch (NumberFormatException ex)
            {
                throw new IOException("Invalid HTTP chunk size: " + line, ex);
            }
            if (this.remaining < 0)
                throw new IOException("Negative HTTP chunk size: " + line);
            if (this.remaining == 0)
            {
                while ((line = PinnedPublicHttpURLConnection.readHeaderLine(this.in)) != null && !line.isEmpty())
                {
                    // Discard trailers.
                }
                this.eof = true;
            }
        }
    }

    private static javax.net.ssl.SSLSocketFactory sslSocketFactory()
    {
        ensureCompatTlsInitialized();
        return compatSocketFactory != null ? compatSocketFactory : (javax.net.ssl.SSLSocketFactory)javax.net.ssl.SSLSocketFactory.getDefault();
    }

    private static void closeSocket(Socket socket)
    {
        if (socket == null)
            return;
        try
        {
            socket.close();
        }
        catch (IOException ignored)
        {
        }
    }

    static Charset charset(Charset charset)
    {
        return charset != null ? charset : StandardCharsets.UTF_8;
    }
    static Gson gson(Gson gson)
    {
        return gson != null ? gson : Scarlet.GSON;
    }

    // In

    public static Reader reader(InputStream in, Charset charset)
    {
        return new InputStreamReader(in, charset(charset));
    }

    public static JsonReader readerJson(InputStream in, Charset charset, Gson gson)
    {
        return gson(gson).newJsonReader(reader(in, charset));
    }

    public static <T> T readJson(InputStream in, Charset charset, Gson gson, Type type)
    {
        return gson(gson).fromJson(reader(in, charset), type);
    }
    public static <T> T readJson(InputStream in, Charset charset, Gson gson, Class<T> type)
    {
        return gson(gson).fromJson(reader(in, charset), type);
    }
    public static <T> T readJson(InputStream in, Charset charset, Gson gson, TypeToken<T> type)
    {
        return gson(gson).fromJson(reader(in, charset), type);
    }

    // Out

    public static Writer writer(OutputStream out, Charset charset)
    {
        return new OutputStreamWriter(out, charset(charset));
    }

    public static JsonWriter writerJson(OutputStream out, Charset charset, Gson gson) throws IOException
    {
        return gson(gson).newJsonWriter(writer(out, charset));
    }

    public static <T> void writeJson(OutputStream out, Charset charset, Gson gson, T value) throws IOException
    {
        gson(gson).toJson(value, writer(out, charset));
    }
    public static <T> void writeJson(OutputStream out, Charset charset, Gson gson, Type type, T value) throws IOException
    {
        gson(gson).toJson(value, type, writer(out, charset));
    }
    public static <T> void writeJson(OutputStream out, Charset charset, Gson gson, Class<T> type, T value) throws IOException
    {
        gson(gson).toJson(value, type, writer(out, charset));
    }
    public static <T> void writeJson(OutputStream out, Charset charset, Gson gson, TypeToken<T> type, T value) throws IOException
    {
        gson(gson).toJson(value, type.getType(), writer(out, charset));
    }

    // In more

    public Reader asReader(Charset charset)
    {
        return reader(this, charset);
    }

    public JsonReader asReaderJson(Charset charset, Gson gson)
    {
        return readerJson(this, charset, gson);
    }
    public <T> T readAsJson(Charset charset, Gson gson, Type type)
    {
        return readJson(this, charset, gson, type);
    }
    public <T> T readAsJson(Charset charset, Gson gson, Class<T> type)
    {
        return readJson(this, charset, gson, type);
    }
    public <T> T readAsJson(Charset charset, Gson gson, TypeToken<T> type)
    {
        return readJson(this, charset, gson, type);
    }

    // Out more

    public static Func.V1<IOException, OutputStream> writeAsJson(Charset charset, Gson gson, Func.V1<? extends IOException, JsonWriter> func)
    {
        return out -> func.invoke(writerJson(out, charset, gson));
    }

    public static <T> Func.V1<IOException, OutputStream> writeAsJson(Charset charset, Gson gson, T value)
    {
        return out -> writeJson(out, charset, gson, value);
    }
    public static <T> Func.V1<IOException, OutputStream> writeAsJson(Charset charset, Gson gson, Type type, T value)
    {
        return out -> writeJson(out, charset, gson, type, value);
    }
    public static <T> Func.V1<IOException, OutputStream> writeAsJson(Charset charset, Gson gson, Class<T> type, T value)
    {
        return out -> writeJson(out, charset, gson, type, value);
    }
    public static <T> Func.V1<IOException, OutputStream> writeAsJson(Charset charset, Gson gson, TypeToken<T> type, T value)
    {
        return out -> writeJson(out, charset, gson, type, value);
    }

    private static final String[] LEGACY_TLS_COMPAT_CERT_RESOURCES =
    {
        "/net/sybyline/scarlet/compat/GoGetSSLECCDVSSLCA2.pem",
        "/net/sybyline/scarlet/compat/SectigoPublicServerAuthenticationRootE46.pem",
        "/net/sybyline/scarlet/compat/SectigoPublicServerAuthenticationCADVE36.pem",
        "/net/sybyline/scarlet/compat/LetsEncryptR12.pem",
        "/net/sybyline/scarlet/compat/ISRGRootX1.pem",
    };
    private static volatile javax.net.ssl.SSLSocketFactory compatSocketFactory;
    private static volatile boolean compatTlsInitAttempted;

    private static void applyCompatTls(HttpsURLConnection connection)
    {
        ensureCompatTlsInitialized();
        if (compatSocketFactory != null)
            connection.setSSLSocketFactory(compatSocketFactory);
    }
    private static synchronized void ensureCompatTlsInitialized()
    {
        if (compatTlsInitAttempted)
            return;
        compatTlsInitAttempted = true;
        try
        {
            X509TrustManager base = defaultTrustManager();
            X509TrustManager extra = compatTrustManager();
            CompositeX509TrustManager composite = new CompositeX509TrustManager(base, extra);
            SSLContext sslContext = SSLContext.getInstance("TLS");
            sslContext.init(null, new TrustManager[] { composite }, null);
            compatSocketFactory = sslContext.getSocketFactory();
            Scarlet.LOG.warn("Using Hatson321/Scarlet Logger's bundled HTTPS compatibility certificates for legacy Java trust stores");
        }
        catch (Exception ex)
        {
            Scarlet.LOG.debug("Unable to initialize legacy HTTPS compatibility certificates; continuing with the JVM default trust store", ex);
        }
    }
    private static X509TrustManager defaultTrustManager() throws Exception
    {
        TrustManagerFactory tmf = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm());
        tmf.init((KeyStore)null);
        return pickTrustManager(tmf.getTrustManagers());
    }
    private static X509TrustManager compatTrustManager() throws Exception
    {
        KeyStore keyStore = KeyStore.getInstance(KeyStore.getDefaultType());
        keyStore.load(null, null);
        CertificateFactory certificateFactory = CertificateFactory.getInstance("X.509");
        int idx = 0;
        for (String resource : LEGACY_TLS_COMPAT_CERT_RESOURCES)
        {
            try (InputStream in = HttpURLInputStream.class.getResourceAsStream(resource))
            {
                if (in == null)
                    continue;
                X509Certificate cert = (X509Certificate)certificateFactory.generateCertificate(in);
                keyStore.setCertificateEntry("scarlet-compat-" + (idx++), cert);
            }
        }
        TrustManagerFactory tmf = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm());
        tmf.init(keyStore);
        return pickTrustManager(tmf.getTrustManagers());
    }
    private static X509TrustManager pickTrustManager(TrustManager[] trustManagers)
    {
        for (TrustManager trustManager : trustManagers)
            if (trustManager instanceof X509TrustManager)
                return (X509TrustManager)trustManager;
        throw new IllegalStateException("No X509TrustManager available");
    }
    private static final class CompositeX509TrustManager implements X509TrustManager
    {
        private final X509TrustManager[] delegates;
        CompositeX509TrustManager(X509TrustManager... delegates)
        {
            this.delegates = delegates;
        }
        @Override
        public void checkClientTrusted(X509Certificate[] chain, String authType) throws java.security.cert.CertificateException
        {
            java.security.cert.CertificateException last = null;
            for (X509TrustManager delegate : this.delegates) try
            {
                delegate.checkClientTrusted(chain, authType);
                return;
            }
            catch (java.security.cert.CertificateException ex)
            {
                last = ex;
            }
            throw last != null ? last : new java.security.cert.CertificateException("No trust manager accepted the client certificate chain");
        }
        @Override
        public void checkServerTrusted(X509Certificate[] chain, String authType) throws java.security.cert.CertificateException
        {
            java.security.cert.CertificateException last = null;
            for (X509TrustManager delegate : this.delegates) try
            {
                delegate.checkServerTrusted(chain, authType);
                return;
            }
            catch (java.security.cert.CertificateException ex)
            {
                last = ex;
            }
            throw last != null ? last : new java.security.cert.CertificateException("No trust manager accepted the server certificate chain");
        }
        @Override
        public X509Certificate[] getAcceptedIssuers()
        {
            java.util.LinkedHashMap<String, X509Certificate> certs = new java.util.LinkedHashMap<>();
            for (X509TrustManager delegate : this.delegates)
                for (X509Certificate cert : delegate.getAcceptedIssuers())
                    certs.put(cert.getSubjectX500Principal().getName(), cert);
            return certs.values().toArray(new X509Certificate[certs.size()]);
        }
    }

}
