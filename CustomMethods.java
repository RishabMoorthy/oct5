package org.framework.utils;

import org.framework.config.ServiceConfig;
import org.framework.constants.PathConstants;
import org.framework.core.ParsedXMLObject;
import org.framework.core.ServerManager;
import org.framework.core.impl.DelayContext;
import org.framework.core.impl.DelayRegistry;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.concurrent.ThreadLocalRandom;
import java.util.Random;
import java.util.random.RandomGenerator;

import static org.eclipse.jetty.util.Pool.StrategyType.RANDOM;
import static org.framework.constants.PathConstants.VS_XML_BACKUP_DIRECTORY;
import static org.framework.constants.PathConstants.VS_XML_DIRECTORY;
import static org.framework.constants.PathConstants.VS_FILE_EXTENSION;

public class CustomMethods {
    private static CustomMethods instance = new CustomMethods();

    public static CustomMethods getInstance() {
        return instance;
    }

    public static String getLocalHostAddress() {
        try {
            return java.net.InetAddress.getLocalHost().getHostAddress();
        } catch (Exception e) {
            throw new RuntimeException("Unable to determine local host IP", e);
        }
    }

    public void removeXmlFile(String name) throws Exception {
        File directory = new File(System.getProperty("user.dir"), "vsfiles");
        if (directory.exists() && directory.isDirectory()) {
            File[] files = directory.listFiles();
            if (files != null) {
                for (File file : files) {
                    System.out.println("File: " + file.getName());
                    if (file.getName().equals(name + VS_FILE_EXTENSION))
                        file.delete();
                }
            }
        }
    }

    public void backupXmlFile(String name) throws Exception {
        File fileDir = new File(VS_XML_BACKUP_DIRECTORY);
        if (!fileDir.exists()) {
            fileDir.mkdirs();
        }
        File destination = new File(fileDir, name + VS_FILE_EXTENSION);
        File source = new File(VS_XML_DIRECTORY, name + VS_FILE_EXTENSION);
        Files.move(source.toPath(), destination.toPath(), StandardCopyOption.REPLACE_EXISTING);
    }

    public String revertXml(String name) {
        File destination = new File(VS_XML_BACKUP_DIRECTORY, name + VS_FILE_EXTENSION); // backup
        File source = new File(VS_XML_DIRECTORY, name + VS_FILE_EXTENSION); // current

        if (!destination.exists()) {
            return "No older version found";
        }
        if (!source.exists()) {
            return "Service does not exist in vsfiles folder";
        }

        Path destPath = destination.toPath();
        Path srcPath = source.toPath();

        // temp file in the same directory as the source (current file)
        Path temp = null;
        try {
            temp = Files.createTempFile(srcPath.getParent(), "revert-swap-", VS_FILE_EXTENSION);

            // 1) Move current source -> temp (so we don't lose it)
            Files.move(srcPath, temp, StandardCopyOption.REPLACE_EXISTING);

            // 2) Move backup destination -> source (making backup the new current)
            Files.move(destPath, srcPath, StandardCopyOption.REPLACE_EXISTING);

            // 3) Move temp (old current) -> destination (becomes the new backup)
            Files.move(temp, destPath, StandardCopyOption.REPLACE_EXISTING);

            return "Reverted successfully";
        } catch (IOException e) {
            // Best-effort rollback: if the backup got moved but replacing source failed,
            // try to restore the original source from temp.
            try {
                if (temp != null && Files.exists(temp) && !Files.exists(srcPath)) {
                    Files.move(temp, srcPath, StandardCopyOption.REPLACE_EXISTING);
                }
            } catch (IOException ignored) {
                // If rollback fails, we'll still report the original error.
            }
            return "Failed to revert: " + e.getMessage();
        } finally {
            // Cleanup temp if left behind
            try {
                if (temp != null && Files.exists(temp)) {
                    Files.delete(temp);
                }
            } catch (IOException ignored) {
            }
        }
    }

    public int calculateDelay(ServiceConfig config) {
        String mode = config.getDelayMode();
        Logger.getInstance().info("Delay ms " + config.getDelayMs() + "  Delay mode " + config.getDelayMode());
        // if delay from db is not null or empty then calculate the delay from db config
        // else send from xml delay
        if ((mode.equalsIgnoreCase("FIXED") && config.getDelayMs() != 0)
                || (config.getUpperMs() != 0 && mode.equalsIgnoreCase("RANDOM")) ||
                (mode.equalsIgnoreCase("LOGNORMAL") && config.getMedianMs() != 0.0) ||
                (mode.equalsIgnoreCase("REALISTIC") && config.getDelayMs() != 0)) {

            switch (mode) {
                case "FIXED": {
                    Logger.getInstance().info("mode " + mode + "  " + config.getDelayMs());
                    return config.getDelayMs();
                }
                case "RANDOM": {
                    int upperMs = config.getUpperMs();
                    int lowerMs = config.getLowerMs();
                    if (lowerMs < 0 || upperMs <= lowerMs) {
                        throw new IllegalArgumentException("Invalid random delay range");
                    }
                    return ThreadLocalRandom.current().nextInt(lowerMs, upperMs + 1);
                }
                case "LOGNORMAL": {
                    double median = config.getMedianMs();
                    double sigma = config.getStandardDeviation();
                    int upperMs = config.getUpperMs();
                    int lowerMs = config.getLowerMs();

                    java.util.Random random = new java.util.Random();
                    double Z = random.nextGaussian();
                    double mu = Math.log(median);
                    double delay = Math.exp(mu + sigma * Z);
                    return (int) Math.round(delay);
                    // return Math.max(lowerMs, Math.min(delayMs, upperMs));

                }
                case "REALISTIC": {
                    String serviceName = config.getServiceName();
                    DelayContext context = DelayRegistry.getContext(serviceName);
                    int maxDelayed = (config.getTotalTxn() * config.getDelayPercent()) / 100;
                    return context.applyDelayIfRequired(maxDelayed, config.getDelayMs(), config.getDelayPercent(),
                            config.getTotalTxn());
                }
            }
        } else {
            Logger.getInstance().info("delay value " + config.getDelay());
            return config.getDelay();
        }

        return 1;
    }
}
