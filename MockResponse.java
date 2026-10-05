package org.framework.properties;

import com.sun.net.httpserver.Headers;
import org.framework.core.AbstractService;
import org.framework.core.ServerManager;
import org.framework.types.StringToStringsMap;
import org.framework.utils.AddCustomResponseToOperation;
import org.framework.utils.Logger;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardOpenOption;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

import static org.framework.constants.PathConstants.VS_XML_DIRECTORY;
import static org.framework.constants.PathConstants.VS_FILE_EXTENSION;

public class MockResponse {

    String name;
    Headers headers;
    int statusCode;
    String responseContent;

    /** The service this response belongs to; needed to copy a response without mutating it. */
    public String getServiceName() {
        return serviceName;
    }

    public String getOperationName() {
        return operationName;
    }

    public void setOperationName(String operationName) {
        this.operationName = operationName;
    }

    String operationName;
    String serviceName;
    String source;
    AddCustomResponseToOperation add = new AddCustomResponseToOperation();
    private static final Map<String, String> pendingUpdates = new ConcurrentHashMap<>();
    private static final ScheduledExecutorService scheduler = Executors.newScheduledThreadPool(1);

    static {
        // Schedule periodic XML updates every 30 seconds
        scheduler.scheduleAtFixedRate(() -> {
            if (!pendingUpdates.isEmpty()) {
                Logger.getInstance().info("Flushing pending XML updates to directory...");
                pendingUpdates.forEach((serviceName, updatedXml) -> {
                    try {

                        // Write to actual XML file in directory
                        Path xmlPath = Paths.get(VS_XML_DIRECTORY, serviceName + VS_FILE_EXTENSION);
                        Files.write(xmlPath, updatedXml.getBytes(StandardCharsets.UTF_8),
                                StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING);

                        Logger.getInstance().info("XML file updated for service: " + serviceName);
                    } catch (Exception e) {
                        Logger.getInstance().error("Error writing XML for service: " + serviceName, e);
                    }
                });
                pendingUpdates.clear();
            }
        }, 0, 10, TimeUnit.SECONDS); // Adjust interval as needed
    }

    public String getSource() {
        return source;
    }

    public void setSource(String source) {
        this.source = source;
    }

    public MockResponse(String serviceName, String name) {
        this.serviceName = serviceName;
        this.name = name;
    }

    // httpResponse
    public MockResponse(Headers headers, int statusCode, byte[] responseBytes, String responseScript) {
        this.headers = headers;
        this.statusCode = statusCode;
        this.responseBytes = responseBytes;
        this.script = responseScript;
        this.responseContent = new String(responseBytes, StandardCharsets.UTF_8);
    }

    public MockResponse(String name, byte[] responseBytes, String responseScript, String operationName,
            String serviceName) {
        this.name = name;
        this.responseBytes = responseBytes;
        this.script = responseScript;
        this.responseContent = new String(responseBytes, StandardCharsets.UTF_8);
        this.operationName = operationName;
        this.serviceName = serviceName;
        if (name.toLowerCase().contains("live")) {
            this.source = "live";
        } else
            this.source = "mock";
    }

    public MockResponse(String name) {
        this.name = name;
        if (name.toLowerCase().contains("live")) {
            this.source = "live";
        } else
            this.source = "mock";
    }

    public MockResponse(String name, Headers headers, int statusCode, byte[] responseBytes, String responseScript,
            String operationName, String serviceName) {
        this.name = name;
        this.headers = headers;
        this.statusCode = statusCode;
        this.responseBytes = responseBytes;
        this.script = responseScript;
        this.responseContent = new String(responseBytes, StandardCharsets.UTF_8);
        this.serviceName = serviceName;
        this.operationName = operationName;
        if (name.toLowerCase().contains("live")) {
            this.source = "live";
        } else
            this.source = "mock";
    }

    public MockResponse(String name, int statusCode, byte[] responseBytes, String responseScript) {
        this.name = name;
        this.statusCode = statusCode;
        this.responseBytes = responseBytes;
        this.script = responseScript;
        this.responseContent = new String(responseBytes, StandardCharsets.UTF_8);
        if (name.toLowerCase().contains("live")) {
            this.source = "live";
        } else
            this.source = "mock";
    }

    public MockResponse(byte[] responseBytes) {
        this.responseBytes = responseBytes;
    }

    public String getResponseContent() {
        return new String(this.responseBytes, StandardCharsets.UTF_8);
    }

    synchronized public void setResponseContent(String responseContent) {
        this.responseContent = responseContent;
        this.responseBytes = responseContent.getBytes(StandardCharsets.UTF_8);
        Logger.getInstance().info("service name " + this.serviceName);
        AbstractService service = ServerManager.getInstance().getServices().get(this.serviceName);
        if (!Objects.equals(this.name, "LIVE")) {
            String updatedXml = add.addMockResponseContentInXml(this.operationName, this.name,
                    service.getConfig().getXmlFileContent(), responseContent);

            service.getConfig().setXmlFileContent(updatedXml);
            pendingUpdates.put(this.serviceName, updatedXml);
        }
    }

    public synchronized void setBinaryData(byte[] responseBytes) throws Exception {
        this.responseBytes = responseBytes;
        Logger.getInstance().info("service name " + this.serviceName);
        // store in xml
        AbstractService service = ServerManager.getInstance().getServices().get(this.serviceName);
        if (service == null) {
            throw new Exception(" Service not found " + this.serviceName);
        }

        // Thread.sleep(200);

        String updatedXml = add.addMockResponseByteInXml(this.operationName, this.name,
                service.getConfig().getXmlFileContent(), responseBytes);
        // System.out.println("updated xml "+updatedXml);
        service.getConfig().setXmlFileContent(updatedXml);
        pendingUpdates.put(this.serviceName, updatedXml);
    }

    public String getScript() {
        return script;
    }

    public void setScript(String script) {
        this.script = script;
    }

    String script;

    byte[] responseBytes;

    public void setName(String name) {
        this.name = name;
    }

    public String getName() {
        return name;
    }

    public Headers getHeaders() {
        return headers;
    }

    public void setHeaders(Headers headers) {
        this.headers = headers;
    }

    public void setResponseHeaders(StringToStringsMap headers) {
        this.headers.putAll(headers.getMap());
    }

    public int getStatusCode() {
        return statusCode;
    }

    public void setStatusCode(int statusCode) {
        this.statusCode = statusCode;
    }

    public void setResponseHttpStatus(int statusCode) {
        this.statusCode = (statusCode);
    }

    public byte[] getResponseBytes() {
        return responseBytes;
    }

    public void setResponseBytes(byte[] responseBytes) {
        this.responseBytes = responseBytes;
    }

    public MockResponse clone() {
        return new MockResponse(this.name, this.headers, this.statusCode, this.responseBytes, this.script,
                this.operationName, this.serviceName);
    }
}
