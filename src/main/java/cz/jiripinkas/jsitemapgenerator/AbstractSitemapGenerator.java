package cz.jiripinkas.jsitemapgenerator;

import cz.jiripinkas.jsitemapgenerator.exception.InvalidPriorityException;
import cz.jiripinkas.jsitemapgenerator.exception.InvalidUrlException;
import cz.jiripinkas.jsitemapgenerator.exception.WebmasterToolsException;

import java.io.*;
import java.lang.reflect.Method;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.util.Date;
import java.util.function.Consumer;
import java.util.function.Supplier;
import java.util.zip.GZIPOutputStream;

public abstract class AbstractSitemapGenerator<T extends AbstractGenerator> extends AbstractGenerator<T> {

    protected W3CDateFormat dateFormat = new W3CDateFormat();

    private ChangeFreq defaultChangeFreq;

    private Double defaultPriority;

    private String defaultDir;

    private String defaultExtension;

    private Date defaultLastMod;

    private HttpClient httpClient;

    public AbstractSitemapGenerator(String baseUrl) {
        super(baseUrl);
        httpClient = new HttpClient();
    }

    public abstract String[] toStringArray();

    /**
     * Construct sitemap into String
     *
     * @return sitemap
     */
    public String toString() {
        String[] sitemapArray = toStringArray();
        StringBuilder result = new StringBuilder();
        for (String line : sitemapArray) {
            result.append(line);
        }
        return result.toString();
    }

    /**
     * Construct sitemap into String which is consumed by supplied Consumer
     *
     * @param stringConsumer Consumer which consumes generated String
     * @return this
     */
    public T toString(Consumer<String> stringConsumer) {
        stringConsumer.accept(toString());
        return getThis();
    }

    /**
     * Construct sitemap into prettified String
     *
     * @param indent Indentation
     * @return sitemap
     */
    public String toPrettyString(int indent) {
        return toPrettyXmlString(toString(), indent).replace("\r\n", "\n");
    }

    /**
     * Construct sitemap into prettified String which is consumed by supplied Consumer
     *
     * @param indent         Indentation
     * @param stringConsumer Consumer which consumes generated String
     * @return this
     */
    public T toPrettyString(int indent, Consumer<String> stringConsumer) {
        stringConsumer.accept(toPrettyString(indent));
        return getThis();
    }

    private ByteArrayOutputStream gzipIt(InputStream inputStream) {
        ByteArrayOutputStream outputStream = new ByteArrayOutputStream();
        byte[] buffer = new byte[1024];
        try {
            try (GZIPOutputStream gzos = new GZIPOutputStream(outputStream);
                 InputStream in = inputStream) {
                int len;
                while ((len = in.read(buffer)) > 0) {
                    gzos.write(buffer, 0, len);
                }
            }
        } catch (IOException ex) {
            throw new RuntimeException("Cannot perform gzip", ex);
        }
        return outputStream;
    }

    /**
     * Construct sitemap into gzipped file
     *
     * @return byte array
     */
    public byte[] toGzipByteArray() {
        String sitemap = this.toString();
        ByteArrayInputStream inputStream = new ByteArrayInputStream(sitemap.getBytes(StandardCharsets.UTF_8));
        ByteArrayOutputStream outputStream = gzipIt(inputStream);
        return outputStream.toByteArray();
    }

    /**
     * Construct sitemap into gzipped byte array which is consumed by supplied Consumer
     *
     * @param byteArrayConsumer Consumer which consumes generated byte array
     * @return this
     */
    public T toGzipByteArray(Consumer<byte[]> byteArrayConsumer) {
        byteArrayConsumer.accept(toGzipByteArray());
        return getThis();
    }

    /**
     * Construct and save sitemap to output file
     *
     * @param file Output file
     * @return this
     * @throws IOException when error
     */
    public T toFile(File file) throws IOException {
        if (file.exists()) {
            if (file.isDirectory()) {
                throw new IOException("File '" + file + "' exists but is a directory");
            }
            if (!file.canWrite()) {
                throw new IOException("File '" + file + "' cannot be written to");
            }
        } else {
            final File parent = file.getParentFile();
            if (parent != null && (!parent.mkdirs() && !parent.isDirectory())) {
                throw new IOException("Directory '" + parent + "' could not be created");
            }
        }
        String[] sitemap = toStringArray();
        try (BufferedWriter writer = new BufferedWriter(new FileWriter(file))) {
            for (String string : sitemap) {
                writer.write(string);
            }
        }
        return getThis();
    }

    /**
     * Construct and save sitemap to output file
     *
     * @param fileSupplier Supplier which supplies output file
     * @return this
     * @throws IOException when error
     */
    public T toFile(Supplier<File> fileSupplier) throws IOException {
        return toFile(fileSupplier.get());
    }

    /**
     * Construct and save sitemap to output file
     *
     * @param path Output file
     * @return this
     * @throws IOException when error
     */
    public T toFile(Path path) throws IOException {
        return toFile(path.toFile());
    }

    /**
     * Construct and save sitemap to output file
     *
     * @param first The path string or initial part of the path string
     * @param more  Additional strings to be joined to form the path string
     * @return this
     * @throws IOException when error
     */
    public T toFile(String first, String... more) throws IOException {
        return toFile(Paths.get(first, more));
    }

    /**
     * Construct and save sitemap to output file
     *
     * @param parent The parent abstract pathname
     * @param child  The child pathname string
     * @return this
     * @throws IOException when error
     */
    public T toFile(File parent, String child) throws IOException {
        return toFile(new File(parent, child));
    }

    /**
     * Ping search engine(s) that sitemap has changed.
     * @param ping Ping object
     * @return true if operation succeeded. If ping.isThrowExceptionOnFailure() == false and operation doesn't succeed, returns false.
     */
    public PingResponse ping(Ping ping) {
        try {
            for (Ping.SearchEngine searchEngine : ping.getSearchEngines()) {
                String resourceUrl;
                if (searchEngine == Ping.SearchEngine.GOOGLE) {
                    resourceUrl = "https://www.google.com/ping?sitemap=";
                } else if (searchEngine == Ping.SearchEngine.BING) {
                    resourceUrl = "https://www.bing.com/ping?sitemap=";
                } else {
                    throw new UnsupportedOperationException("Unknown search engine: " + searchEngine);
                }
                String sitemapUrl;
                if (ping.getSitemapUrl() == null) {
                    sitemapUrl = getAbsoluteUrl("sitemap.xml", false);
                } else {
                    sitemapUrl = getAbsoluteUrl(ping.getSitemapUrl(), false);
                }
                boolean responseIsNot200 = false;
                if (ping.getHttpClientType() == null) {
                    ping(resourceUrl, sitemapUrl, searchEngine.getPrettyName());
                } else if(ping.getHttpClientType() == Ping.HttpClientType.REST_TEMPLATE) {
                    String pingUrl = resourceUrl + sitemapUrl;
                    org.springframework.web.client.RestTemplate restTemplate = (org.springframework.web.client.RestTemplate) ping.getHttpClientImplementation();
                    org.springframework.http.ResponseEntity<String> responseEntity = restTemplate.getForEntity(pingUrl, String.class);
                    if (responseEntity.getStatusCodeValue() != 200) {
                        responseIsNot200 = true;
                    }
                } else if(ping.getHttpClientType() == Ping.HttpClientType.OK_HTTP) {
                    String pingUrl = resourceUrl + URLEncoder.encode(sitemapUrl, "UTF-8");
                    okhttp3.OkHttpClient okHttpClient = (okhttp3.OkHttpClient) ping.getHttpClientImplementation();
                    okhttp3.Request request = new okhttp3.Request.Builder().url(pingUrl).build();
                    try (okhttp3.Response response = okHttpClient.newCall(request).execute()) {
                        if (!response.isSuccessful()) {
                            responseIsNot200 = true;
                        }
                    }
                } else if(ping.getHttpClientType() == Ping.HttpClientType.APACHE_HTTP_CLIENT) {
                    String pingUrl = resourceUrl + URLEncoder.encode(sitemapUrl, "UTF-8");
                    Object httpGet = Class.forName("org.apache.http.client.methods.HttpGet").getDeclaredConstructor(String.class).newInstance(pingUrl);
                    Method execute = Class.forName("org.apache.http.impl.client.CloseableHttpClient").getMethod("execute", Class.forName("org.apache.http.client.methods.HttpUriRequest"));
                    Object httpResponse = null;
                    try {
                        httpResponse = execute.invoke(ping.getHttpClientImplementation(), httpGet);
                        Method getEntity = Class.forName("org.apache.http.HttpResponse").getMethod("getEntity");
                        Object httpEntity = getEntity.invoke(httpResponse);
                        Method consume = Class.forName("org.apache.http.util.EntityUtils").getMethod("consume", Class.forName("org.apache.http.HttpEntity"));
                        consume.invoke(null, httpEntity);
                        Object getStatusLine = Class.forName("org.apache.http.HttpResponse").getMethod("getStatusLine").invoke(httpResponse);
                        int getStatusCode = (Integer) Class.forName("org.apache.http.StatusLine").getMethod("getStatusCode").invoke(getStatusLine);
                        if(getStatusCode != 200) {
                            responseIsNot200 = true;
                        }
                    } finally {
                        if(httpResponse != null) {
                            Class.forName("java.io.Closeable").getMethod("close").invoke(httpResponse);
                        }
                    }
                    // same code without reflection
//                    org.apache.http.impl.client.CloseableHttpClient closeableHttpClient = (org.apache.http.impl.client.CloseableHttpClient) ping.getHttpClientImplementation();
//                    org.apache.http.client.methods.HttpGet httpGet = new org.apache.http.client.methods.HttpGet(pingUrl);
//                    try (org.apache.http.client.methods.CloseableHttpResponse httpResponse = closeableHttpClient.execute(httpGet)) {
//                        org.apache.http.HttpEntity httpEntity = httpResponse.getEntity();
//                        org.apache.http.util.EntityUtils.consume(httpEntity);
//                        if(httpResponse.getStatusLine().getStatusCode() != 200) {
//                            responseIsNot200 = true;
//                        }
//                    }
                } else {
                    throw new UnsupportedOperationException("Unknown HttpClientType!");
                }
                if(responseIsNot200) {
                    throw new WebmasterToolsException(searchEngine.getPrettyName() + " could not be informed about new sitemap! Return code != 200");
                }
            }
        } catch (Exception e) {
            return new PingResponse(true, new WebmasterToolsException(e));
        }
        return new PingResponse(false);
    }

    /**
     * Ping Google that sitemap has changed. Will call this URL:
     * https://www.google.com/ping?sitemap=URL_Encoded_sitemapUrl
     *
     * @param sitemapUrl sitemap url
     * @deprecated Use {@link #ping(Ping)}
     */
    @Deprecated
    public void pingGoogle(String sitemapUrl) {
        ping("https://www.google.com/ping?sitemap=", sitemapUrl, "Google");
    }

    /**
     * Ping Google that sitemap has changed. Will call this URL:
     * https://www.google.com/ping?sitemap=URL_Encoded_sitemapUrl
     *
     * @param sitemapUrl                   sitemap url
     * @param doNotThrowExceptionOnFailure If this is true and it's not possible to ping google,
     *                                     this method won't throw any exception, but will return false.
     * @return If operation succeeded
     * @deprecated Use {@link #ping(Ping)}
     */
    @Deprecated
    public boolean pingGoogle(String sitemapUrl, boolean doNotThrowExceptionOnFailure) {
        try {
            pingGoogle(sitemapUrl);
            return true;
        } catch (Exception e) {
            if (doNotThrowExceptionOnFailure) {
                return false;
            }
            throw e;
        }
    }

    /**
     * Ping Bing that sitemap has changed. Will call this URL:
     * https://www.bing.com/ping?sitemap=URL_Encoded_sitemapUrl
     *
     * @param sitemapUrl sitemap url
     * @deprecated Use {@link #ping(Ping)}
     */
    @Deprecated
    public void pingBing(String sitemapUrl) {
        ping("https://www.bing.com/ping?sitemap=", sitemapUrl, "Bing");
    }

    /**
     * Ping Bing that sitemap has changed. Will call this URL:
     * https://www.bing.com/ping?sitemap=URL_Encoded_sitemapUrl
     *
     * @param sitemapUrl                   sitemap url
     * @param doNotThrowExceptionOnFailure If this is true and it's not possible to ping google,
     *                                     this method won't throw any exception, but will return false.
     * @return If operation succeeded
     * @deprecated Use {@link #ping(Ping)}
     */
    @Deprecated
    public boolean pingBing(String sitemapUrl, boolean doNotThrowExceptionOnFailure) {
        try {
            pingBing(sitemapUrl);
            return true;
        } catch (Exception e) {
            if (doNotThrowExceptionOnFailure) {
                return false;
            }
            throw e;
        }
    }

    /**
     * Ping Google that sitemap has changed. Sitemap must be on this location:
     * baseUrl/sitemap.xml (for example http://www.javavids.com/sitemap.xml)
     *
     * @deprecated Use {@link #ping(Ping)}
     */
    @Deprecated
    public void pingGoogle() {
        pingGoogle(baseUrl + "sitemap.xml");
    }

    /**
     * Ping Google that sitemap has changed. Sitemap must be on this location:
     * baseUrl/sitemap.xml (for example http://www.javavids.com/sitemap.xml)
     *
     * @param doNotThrowExceptionOnFailure If this is true and it's not possible to ping google,
     *                                     this method won't throw any exception, but will return false.
     * @return If operation succeeded
     * @deprecated Use {@link #ping(Ping)}
     */
    @Deprecated
    public boolean pingGoogle(boolean doNotThrowExceptionOnFailure) {
        try {
            pingGoogle();
            return true;
        } catch (Exception e) {
            if (doNotThrowExceptionOnFailure) {
                return false;
            }
            throw e;
        }
    }

    /**
     * Ping Google that sitemap has changed. Sitemap must be on this location:
     * baseUrl/sitemap.xml (for example http://www.javavids.com/sitemap.xml)
     *
     * @deprecated Use {@link #ping(Ping)}
     */
    @Deprecated
    public void pingBing() {
        pingBing(baseUrl + "sitemap.xml");
    }

    /**
     * Ping Bing that sitemap has changed. Sitemap must be on this location:
     * baseUrl/sitemap.xml (for example http://www.javavids.com/sitemap.xml)
     *
     * @param doNotThrowExceptionOnFailure If this is true and it's not possible to ping google,
     *                                     this method won't throw any exception, but will return false.
     * @return If operation succeeded
     * @deprecated Use {@link #ping(Ping)}
     */
    @Deprecated
    public boolean pingBing(boolean doNotThrowExceptionOnFailure) {
        try {
            pingBing();
            return true;
        } catch (Exception e) {
            if (doNotThrowExceptionOnFailure) {
                return false;
            }
            throw e;
        }
    }

    private void ping(String resourceUrl, String sitemapUrl, String serviceName) {
        try {
            String pingUrl = resourceUrl + URLEncoder.encode(sitemapUrl, "UTF-8");
            // ping Google / Bing
            int returnCode = httpClient.get(pingUrl);
            if (returnCode != 200) {
                throw new WebmasterToolsException(serviceName + " could not be informed about new sitemap! Return code != 200");
            }
        } catch (Exception ex) {
            throw new WebmasterToolsException(serviceName + " could not be informed about new sitemap!", ex);
        }
    }

    @Override
    protected void beforeAddPageEvent(WebPage webPage) {
        if (defaultDir != null && webPage.getDir() == null) {
            webPage.setName(UrlUtil.connectUrlParts(defaultDir, webPage.constructName()));
        }
        if (defaultExtension != null && webPage.getExtension() == null) {
            webPage.setName(webPage.constructName() + "." + defaultExtension);
        }
        if (defaultPriority != null && webPage.getPriority() == null) {
            webPage.setPriority(defaultPriority);
        }
        if (defaultChangeFreq != null && webPage.getChangeFreq() == null) {
            webPage.setChangeFreq(defaultChangeFreq);
        }
        if (defaultLastMod != null && webPage.getLastMod() == null) {
            webPage.setLastMod(defaultLastMod);
        }
    }

    /**
     * Sets default prefix dir to name for all subsequent WebPages. Final name will be "dirName/name"
     *
     * @param dirName Dir name
     * @return this
     */
    public T defaultDir(String dirName) {
        defaultDir = dirName;
        return getThis();
    }

    /**
     * Sets default prefix dirs to name for all subsequent WebPages. For dirs: ["a", "b", "c"], the final name will be "a/b/c/name"
     *
     * @param dirNames Dir names
     * @return this
     */
    public T defaultDir(String... dirNames) {
        defaultDir = String.join("/", dirNames);
        return getThis();
    }

    /**
     * Reset default dir value
     *
     * @return this
     */
    public T resetDefaultDir() {
        defaultDir = null;
        return getThis();
    }

    /**
     * Sets default suffix extension for all subsequent WebPages. Final name will be "name.extension"
     *
     * @param extension Extension
     * @return this
     */
    public T defaultExtension(String extension) {
        defaultExtension = extension;
        return getThis();
    }

    /**
     * Reset default extension value
     *
     * @return this
     */
    public T resetDefaultExtension() {
        defaultExtension = null;
        return getThis();
    }

    /**
     * Sets default priority for all subsequent WebPages to maximum (1.0)
     *
     * @return this
     */
    public T defaultPriorityMax() {
        defaultPriority = 1.0;
        return getThis();
    }

    /**
     * Sets default priority for all subsequent WebPages
     *
     * @param priority Default priority
     * @return this
     */
    public T defaultPriority(Double priority) {
        if (priority < 0.0 || priority > 1.0) {
            throw new InvalidPriorityException("Priority must be between 0 and 1.0");
        }
        defaultPriority = priority;
        return getThis();
    }

    /**
     * Reset default priority
     *
     * @return this
     */
    public T resetDefaultPriority() {
        defaultPriority = null;
        return getThis();
    }

    /**
     * Sets default changeFreq for all subsequent WebPages
     *
     * @param changeFreq ChangeFreq
     * @return this
     */
    public T defaultChangeFreq(ChangeFreq changeFreq) {
        defaultChangeFreq = changeFreq;
        return getThis();
    }

    /**
     * Sets default changeFreq to ALWAYS for all subsequent WebPages
     *
     * @return this
     */
    public T defaultChangeFreqAlways() {
        defaultChangeFreq = ChangeFreq.ALWAYS;
        return getThis();
    }

    /**
     * Sets default changeFreq to HOURLY for all subsequent WebPages
     *
     * @return this
     */
    public T defaultChangeFreqHourly() {
        defaultChangeFreq = ChangeFreq.HOURLY;
        return getThis();
    }

    /**
     * Sets default changeFreq to DAILY for all subsequent WebPages
     *
     * @return this
     */
    public T defaultChangeFreqDaily() {
        defaultChangeFreq = ChangeFreq.DAILY;
        return getThis();
    }

    /**
     * Sets default changeFreq to WEEKLY for all subsequent WebPages
     *
     * @return this
     */
    public T defaultChangeFreqWeekly() {
        defaultChangeFreq = ChangeFreq.WEEKLY;
        return getThis();
    }

    /**
     * Sets default changeFreq to MONTHLY for all subsequent WebPages
     *
     * @return this
     */
    public T defaultChangeFreqMonthly() {
        defaultChangeFreq = ChangeFreq.MONTHLY;
        return getThis();
    }

    /**
     * Sets default changeFreq to YEARLY for all subsequent WebPages
     *
     * @return this
     */
    public T defaultChangeFreqYearly() {
        defaultChangeFreq = ChangeFreq.YEARLY;
        return getThis();
    }

    /**
     * Sets default changeFreq to NEVER for all subsequent WebPages
     *
     * @return this
     */
    public T defaultChangeFreqNever() {
        defaultChangeFreq = ChangeFreq.NEVER;
        return getThis();
    }

    /**
     * Reset default changeFreq
     *
     * @return this
     */
    public T resetDefaultChangeFreq() {
        defaultChangeFreq = null;
        return getThis();
    }

    /**
     * Sets default lastMod for all subsequent WebPages
     *
     * @param lastMod lastMod
     * @return this
     */
    public T defaultLastMod(Date lastMod) {
        defaultLastMod = lastMod;
        return getThis();
    }

    /**
     * Sets default lastMod for all subsequent WebPages
     *
     * @param lastMod lastMod
     * @return this
     */
    public T defaultLastMod(LocalDateTime lastMod) {
        defaultLastMod = Timestamp.valueOf(lastMod);
        return getThis();
    }

    /**
     * Sets default lastMod = new Date() for all subsequent WebPages
     *
     * @return this
     */
    public T defaultLastModNow() {
        defaultLastMod = new Date();
        return getThis();
    }

    /**
     * Reset default lastMod
     *
     * @return this
     */
    public T resetDefaultLastMod() {
        defaultLastMod = null;
        return getThis();
    }

    public void setHttpClient(HttpClient httpClient) {
        this.httpClient = httpClient;
    }

    /**
     * Get absolute URL:
     * If webPageName is null, return baseUrl.
     * If webPageName is not null, check if webPageName is absolute (can be URL from CDN) or relative URL.
     * If it's relative URL, prepend baseUrl and return result.
     * This method escapes webPageName's special characters, thus it must not be called for ping Google / Bing functionality!
     *
     * @param webPageName WebPageName
     * @return Correct URL
     */
    protected String getAbsoluteUrl(String webPageName) {
        return getAbsoluteUrl(webPageName, true);
    }

    /**
     * Get absolute URL:
     * If webPageName is null, return baseUrl.
     * If webPageName is not null, check if webPageName is absolute (can be URL from CDN) or relative URL.
     * If it's relative URL, prepend baseUrl and return result
     *
     * @param webPageName WebPageName
     * @param escapeSpecialCharacters Escape special characters?
     *                                Special characters must be escaped if the URL will be stored to sitemap.
     *                                If this method is called for ping Google / Bing functionality, special characters must not be escaped.
     * @return Correct URL
     */
    protected String getAbsoluteUrl(String webPageName, boolean escapeSpecialCharacters) {
        if(escapeSpecialCharacters) {
            webPageName = UrlUtil.escapeXmlSpecialCharacters(webPageName);
        }
        try {
            String resultString;
            if (webPageName != null) {
                URI uri = new URI(webPageName);
                String stringUrl;
                if (uri.isAbsolute()) {
                    stringUrl = webPageName;
                } else {
                    stringUrl = UrlUtil.connectUrlParts(baseUrl, webPageName);
                }
                resultString = stringUrl;
            } else {
                resultString = baseUrl;
            }
            return new URL(resultString).toString();
        } catch (MalformedURLException | URISyntaxException e) {
            throw new InvalidUrlException(e);
        }
    }

}
