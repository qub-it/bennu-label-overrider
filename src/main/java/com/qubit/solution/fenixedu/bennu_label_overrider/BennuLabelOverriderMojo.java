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
import java.net.URL;
import java.nio.charset.Charset;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.Enumeration;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;

import org.apache.maven.plugin.AbstractMojo;
import org.apache.maven.plugin.MojoExecutionException;
import org.apache.maven.plugin.MojoFailureException;
import org.apache.maven.plugins.annotations.Component;
import org.apache.maven.plugins.annotations.LifecyclePhase;
import org.apache.maven.plugins.annotations.Mojo;
import org.apache.maven.plugins.annotations.Parameter;
import org.apache.maven.plugins.annotations.ResolutionScope;
import org.apache.maven.project.MavenProject;
import org.eclipse.aether.RepositorySystem;
import org.eclipse.aether.RepositorySystemSession;
import org.eclipse.aether.artifact.Artifact;
import org.eclipse.aether.artifact.DefaultArtifact;
import org.eclipse.aether.collection.CollectRequest;
import org.eclipse.aether.collection.CollectResult;
import org.eclipse.aether.collection.DependencyCollectionException;
import org.eclipse.aether.graph.Dependency;
import org.eclipse.aether.graph.DependencyNode;
import org.eclipse.aether.repository.RemoteRepository;
import org.eclipse.aether.util.artifact.JavaScopes;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

@Mojo(name = "bennu-label-overrider-mojo", defaultPhase = LifecyclePhase.INITIALIZE, threadSafe = true,
        requiresDependencyResolution = ResolutionScope.COMPILE)
public class BennuLabelOverriderMojo extends AbstractMojo {

    private static final String PROPERTIES_EXTENSION = ".properties";
    private static final String SPRING_RESOURCES = "META-INF/resources/WEB-INF/resources/";
    private static final String RESOURCES = "resources";
    Logger logger = LoggerFactory.getLogger(getClass());

    @Parameter(property = "project")
    protected MavenProject mavenProject;

    @Component
    private RepositorySystem repoSystem;

    @Parameter(defaultValue = "${repositorySystemSession}")
    private RepositorySystemSession repoSession;

    @Parameter(defaultValue = "${project.remoteProjectRepositories}")
    private List<RemoteRepository> remoteRepos;

    @Override
    public void execute() throws MojoExecutionException, MojoFailureException {
        String artifactCoords = mavenProject.getGroupId() + ":" + mavenProject.getArtifactId() + ":" + mavenProject.getVersion();

        logger.info("Overriding Labels");

        String realPath = mavenProject.getBasedir().toPath().toString() + "/src/main/webapp/";
        List<Artifact> dependencies = new ArrayList<Artifact>();
        try {

            CollectResult collectDependencies =
                    repoSystem.collectDependencies(repoSession, new CollectRequest(new Dependency(new DefaultArtifact(
                            artifactCoords), JavaScopes.RUNTIME), remoteRepos));
            visit(dependencies, collectDependencies.getRoot().getChildren());
            Collections.reverse(dependencies);
        } catch (DependencyCollectionException e) {
            e.printStackTrace();
            throw new RuntimeException("Unable to extract labels");
        }

        Map artifactMap = mavenProject.getArtifactMap();
        dependencies.stream()
                .map(x -> (org.apache.maven.artifact.Artifact) artifactMap.get(x.getGroupId() + ":" + x.getArtifactId()))
                .filter(x -> x != null).map(x -> x.getFile()).forEach(file -> extractProperties(file, realPath));

    }

    private void visit(Collection<Artifact> dependencies, Collection<DependencyNode> dependencyNodes) {
        for (DependencyNode node : dependencyNodes) {
            if (!dependencies.contains(node.getArtifact())) {
                dependencies.add(node.getArtifact());
                visit(dependencies, node.getChildren());
            }
        }
    }

    private void extractProperties(File file, String realPath) {
        try (JarFile jarFile = new JarFile(file)) {
            Enumeration<JarEntry> entries = jarFile.entries();
            while (entries.hasMoreElements()) {
                JarEntry nextElement = entries.nextElement();
                String name = nextElement.getName();
                if ((name.startsWith(RESOURCES) || name.startsWith(SPRING_RESOURCES)) && name.endsWith(PROPERTIES_EXTENSION)) {
                    extractProperties(jarFile, nextElement, realPath);
                }
            }

        } catch (IOException e) {
            // this should not happen
            throw new RuntimeException("Unable to operate with jar " + file, e);
        }
    }

    private void extractProperties(JarFile jarFile, JarEntry nextElement, String realPath) throws IOException {
        logger.debug("extracting " + nextElement + " from " + jarFile.getName());
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
