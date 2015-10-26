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
import java.util.Collections;
import java.util.Enumeration;
import java.util.HashMap;
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

    //Building dependency graph utils
    Map<String, DependencyNodeBean> nodes = new HashMap<>();

    @Override
    public void execute() throws MojoExecutionException, MojoFailureException {

        if (mavenProject.getPackaging().equals("pom")) {
            getLog().info("Project is pom type. Skipping resources override.");
            return;
        }

        String artifactCoords = mavenProject.getGroupId() + ":" + mavenProject.getArtifactId() + ":" + mavenProject.getVersion();

        logger.info("Overriding Labels");

        String realPath = mavenProject.getBasedir().toPath().toString() + "/src/main/webapp/";
        List<DependencyNodeBean> topologicalSort = null;
        try {
            Dependency dependency = new Dependency(new DefaultArtifact(artifactCoords), JavaScopes.RUNTIME);
            CollectResult collectDependencies = collectDependencies(dependency);
            visit(collectDependencies.getRoot());
            topologicalSort = topologicalSort();

        } catch (DependencyCollectionException e) {
            e.printStackTrace();
            throw new RuntimeException("Unable to extract labels");
        }

        Map<String, org.apache.maven.artifact.Artifact> artifactMap = mavenProject.getArtifactMap();
        //Was topological sorted, we need to process it in the reverse order
        Collections.reverse(topologicalSort);
        topologicalSort.stream().map(x -> artifactMap.get(x.getId())).filter(x -> x != null).map(x -> x.getFile())
                .forEach(file -> extractProperties(file, realPath));

    }

    private CollectResult collectDependencies(Dependency dependency) throws DependencyCollectionException {
        return repoSystem.collectDependencies(repoSession, new CollectRequest(dependency, remoteRepos));
    }

    class DependencyNodeBean {
        String id;
        Map<String, DependencyNodeBean> dependencies;
        Set<String> dependendBy = new HashSet<String>();

        public DependencyNodeBean(String id) {
            this.id = id;
            dependencies = new HashMap<String, DependencyNodeBean>();
        }

        public String getId() {
            return id;
        }

        public void setId(String id) {
            this.id = id;
        }

        public Map<String, DependencyNodeBean> getDependencies() {
            return dependencies;
        }

        public void setDependencies(Map<String, DependencyNodeBean> dependencies) {
            this.dependencies = dependencies;
        }

        public Set<String> getDependendBy() {
            return dependendBy;
        }

        public void setDependendBy(Set<String> dependendBy) {
            this.dependendBy = dependendBy;
        }
    }

    private List<DependencyNodeBean> topologicalSort() {
        List<DependencyNodeBean> sortedNodes = new ArrayList<BennuLabelOverriderMojo.DependencyNodeBean>();
        while (!nodes.isEmpty()) {
            boolean hadChanges = false;
            outer: for (DependencyNodeBean node : new HashSet<>(nodes.values())) {
                for (String dependedBy : node.getDependendBy()) {
                    if (nodes.containsKey(dependedBy)) {
                        continue outer;
                    }
                }
                sortedNodes.add(node);
                nodes.remove(node.getId());
                hadChanges = true;
            }
            if (hadChanges == false) {
                //Full iteration without any changes : somehow we have a dependency cycle, let's finish here  
                return sortedNodes;
            }
        }
        return sortedNodes;
    }

    private DependencyNodeBean visit(DependencyNode dependency) throws DependencyCollectionException {
        Artifact artifact = dependency.getArtifact();
        String id = artifact.getGroupId() + ":" + artifact.getArtifactId();

        DependencyNodeBean dependencyNodeBean = nodes.get(id);
        //Add new dependency node to the collection if we haven't found it yet  
        if (dependencyNodeBean == null) {
            dependencyNodeBean = new DependencyNodeBean(id);
            nodes.put(dependencyNodeBean.getId(), dependencyNodeBean);
        }

        for (DependencyNode node : dependency.getChildren()) {
            //visit the child node
            DependencyNodeBean visit = nodes.get(node.getArtifact().getGroupId() + ":" + node.getArtifact().getArtifactId());
            if (visit == null) {
                CollectResult collectDependencies = collectDependencies(node.getDependency());
                visit = visit(collectDependencies.getRoot());
            }
            //Add the current node to the child "depended by list" and add the child node to this node dependencies
            visit.getDependendBy().add(dependencyNodeBean.getId());
            if (!dependencyNodeBean.getDependencies().containsKey(visit.getId())) {
                dependencyNodeBean.getDependencies().put(visit.getId(), visit);
            }
        }
        return dependencyNodeBean;
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
