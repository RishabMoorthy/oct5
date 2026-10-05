package org.framework.utils;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.framework.constants.PathConstants.VS_FILE_EXTENSION;

public class MultiPartData {

    private static final Pattern FILENAME = Pattern.compile("filename=\"?([^\";\r\n]*)\"?");

    public File file;
    public String fileName;
    public String backendApplication;
    public String group;
    public String backendType;
    public String storetoMasterCatalog = "false";
    public String envType;

    public MultiPartData extractMultiPart(InputStream is, String boundary) throws IOException {

        MultiPartData data = new MultiPartData();

        String body = new String(is.readAllBytes(), StandardCharsets.ISO_8859_1);

        String[] parts = body.split("--" + boundary);

        for (String part : parts) {
            if (!part.contains("Content-Disposition"))
                continue;

            int headerEnd = part.indexOf("\r\n\r\n");
            if (headerEnd < 0)
                continue;

            String headers = part.substring(0, headerEnd);
            String value = part.substring(headerEnd + 4).trim();

            // ------------------------------
            // 1) Detect file part
            // ------------------------------
            if (headers.contains("filename=")) {
                Matcher m = FILENAME.matcher(headers);
                data.fileName = m.find() ? m.group(1).trim() : null;

                String uniqueFileName = "temp_" + UUID.randomUUID() + VS_FILE_EXTENSION;

                File dir = new File("tempfiles");
                if (!dir.exists())
                    dir.mkdirs();

                File f = new File(dir, uniqueFileName);
                Files.writeString(f.toPath(), value, StandardCharsets.UTF_8);

                data.file = f;
                continue;
            }

            // ------------------------------
            // 2) Detect partner=xxx
            // ------------------------------
            if (headers.contains("name=\"backendApplication\"")) {
                data.backendApplication = value;
            }
            if (headers.contains("name=\"group\"")) {
                data.group = value;
            }
            if (headers.contains("name=\"backendType\"")) {
                data.backendType = value;
            }
            if (headers.contains("name=\"storetoMasterCatalog\"")) {
                data.storetoMasterCatalog = value;
            }
            if (headers.contains("name=\"envType\"")) {
                data.envType = value;
            }
        }

        return data;
    }
}
