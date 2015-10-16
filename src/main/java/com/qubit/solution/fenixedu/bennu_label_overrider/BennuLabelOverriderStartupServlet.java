package com.qubit.solution.fenixedu.bennu_label_overrider;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.net.MalformedURLException;
import java.net.URISyntaxException;
import java.net.URL;
import java.nio.charset.Charset;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.util.Enumeration;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;
import java.util.stream.Stream;

import javax.servlet.ServletContainerInitializer;
import javax.servlet.ServletContext;
import javax.servlet.ServletException;

import org.slf4j.LoggerFactory;

import pt.ist.fenixframework.FenixFramework;
import pt.ist.fenixframework.core.DmlFile;

public class BennuLabelOverriderStartupServlet implements ServletContainerInitializer {

    private static final String PROPERTIES_EXTENSION = ".properties";
    private static final String SPRING_RESOURCES = "META-INF/resources/WEB-INF/resources/";
    private static final String RESOURCES = "resources";

    @Override
    public void onStartup(Set<Class<?>> c, ServletContext ctx) throws ServletException {
        String property = System.getProperty("skipLabelOverride");
        if (property == null || property.equals("true")) {
            LoggerFactory.getLogger(getClass()).info("Not overriding labels");
        }
        String realPath = ctx.getRealPath("/");
        //ensure "/" termination (varies by tomcat version)
        String realPathWithRightTermination = realPath.endsWith("/") ? realPath : realPath + "/";

        List<DmlFile> fullDmlSortedList = FenixFramework.getProject().getFullDmlSortedList();
        Stream<URL> sortedJars = fullDmlSortedList.stream().map(dml -> calculateJarURL(dml.getUrl()));
        sortedJars.forEach(x -> extractProperties(x, realPathWithRightTermination));

    }

    private void extractProperties(URL url, String realPath) {
        try (JarFile jarFile = new JarFile(new File(url.toURI()))) {
            Enumeration<JarEntry> entries = jarFile.entries();
            while (entries.hasMoreElements()) {
                JarEntry nextElement = entries.nextElement();
                String name = nextElement.getName();
                if ((name.startsWith(RESOURCES) || name.startsWith(SPRING_RESOURCES)) && name.endsWith(PROPERTIES_EXTENSION)) {
                    extractProperties(jarFile, nextElement, realPath);
                }
            }

        } catch (IOException | URISyntaxException e) {
            // this should not happen
            throw new RuntimeException("Unable to operate with jar " + url, e);
        }
    }

    private void extractProperties(JarFile jarFile, JarEntry nextElement, String realPath) throws IOException {
        String realName = nextElement.getName();
        String encoding = realName.startsWith("META-INF/resources") ? "UTF-8" : "ISO-8859-1";
        realName =
                realName.startsWith("META-INF/resources") ? realName.replace("META-INF/resources", "") : "WEB-INF/classes/"
                        + realName;
        File tmpFile = new File(realPath + realName + ".tmp");
        tmpFile.getParentFile().mkdirs();
        File finalFile = new File(realPath + realName);
        Set<String> foundKeys = new HashSet<>();

        //save all the lines from the properties file to a temp file, save which keys were found
        BufferedWriter bufferedWriter =
                new BufferedWriter(new OutputStreamWriter(new FileOutputStream(tmpFile), Charset.forName(encoding)));
        BufferedReader jarPropertiesReader =
                new BufferedReader(new InputStreamReader(jarFile.getInputStream(nextElement), Charset.forName(encoding)));
        String readLine = null;
        while ((readLine = jarPropertiesReader.readLine()) != null) {
            int indexOf = readLine.indexOf("=");
            if (indexOf > -1) {
                //blank line or comment, or just invalid
                String trimmedKey = readLine.substring(0, indexOf).trim();
                foundKeys.add(trimmedKey);
            }
            bufferedWriter.write(readLine);
            bufferedWriter.newLine();
        }

        if (finalFile.exists()) {
            //save from the real file to the temp file, skip the already existing keys
            BufferedReader finalFileReader =
                    new BufferedReader(new InputStreamReader(new FileInputStream(finalFile), Charset.forName(encoding)));
            while ((readLine = finalFileReader.readLine()) != null) {
                int indexOf = readLine.indexOf("=");
                if (indexOf > -1) {
                    String trimmedKey = readLine.substring(0, indexOf).trim();
                    if (foundKeys.contains(trimmedKey)) {
                        continue;
                    }
                }
                //An existing (minor) bug is that, since we are not checking for comments and blank lines duplication, these can be duplicated in the final file at its end (giving an awkward result)
                bufferedWriter.write(readLine);
                bufferedWriter.newLine();
            }
            finalFileReader.close();
        }
        bufferedWriter.close();
        jarPropertiesReader.close();

        //copy tmp file to final file and delete the tmp
        Files.copy(tmpFile.toPath(), finalFile.toPath(), StandardCopyOption.REPLACE_EXISTING);
        Files.delete(tmpFile.toPath());
        //Force cache clear just in case any other web-listener accessed an old label
//        ResourceBundle.clearCache();
    }

    URL calculateJarURL(URL dmlUrl) {
        int lastIndexOf = dmlUrl.toString().lastIndexOf("!");
        try {
            //remove the "jar:"
            String substring = dmlUrl.toString().substring(4, lastIndexOf);
            return new URL(substring);
        } catch (MalformedURLException e) {
            // should not happen unless a dml has '!' in its name
            throw new RuntimeException("Unable to calculate jarname for dml file " + dmlUrl, e);
        }
    }

}
