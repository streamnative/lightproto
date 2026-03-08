/**
 * Copyright 2026 StreamNative
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package io.github.merlimat.lightproto.maven.plugin;

import com.google.protobuf.DescriptorProtos.FileDescriptorProto;
import com.google.protobuf.DescriptorProtos.FileDescriptorSet;
import io.github.merlimat.lightproto.generator.DescriptorConverter;
import io.github.merlimat.lightproto.generator.LightProtoGenerator;
import io.github.merlimat.lightproto.generator.ProtoFileDescriptor;
import org.apache.maven.plugin.AbstractMojo;
import org.apache.maven.plugin.MojoExecutionException;
import org.apache.maven.plugins.annotations.Component;
import org.apache.maven.plugins.annotations.LifecyclePhase;
import org.apache.maven.plugins.annotations.Mojo;
import org.apache.maven.plugins.annotations.Parameter;
import org.apache.maven.plugins.annotations.ResolutionScope;
import org.apache.maven.project.MavenProject;
import org.eclipse.aether.RepositorySystem;
import org.eclipse.aether.RepositorySystemSession;
import org.eclipse.aether.artifact.DefaultArtifact;
import org.eclipse.aether.repository.RemoteRepository;
import org.eclipse.aether.resolution.ArtifactRequest;
import org.eclipse.aether.resolution.ArtifactResult;

import java.io.*;
import java.util.*;

/**
 * Goal which generates Java code from the proto definition
 */
@Mojo(
        name = "generate",
        defaultPhase = LifecyclePhase.GENERATE_SOURCES,
        requiresDependencyResolution = ResolutionScope.COMPILE,
        threadSafe = true
)
public class LightProtoMojo extends AbstractMojo {

    @Parameter(property = "classPrefix", defaultValue = "", required = false)
    private String classPrefix;

    @Parameter(property = "singleOuterClass", defaultValue = "false", required = false)
    private boolean singleOuterClass;

    @Parameter(property = "sources", required = false)
    private List<File> sources;

    @Parameter(defaultValue = "${project}", required = true, readonly = true)
    private MavenProject project;

    @Parameter(defaultValue = "generated-sources/protobuf/java", required = false)
    private String targetSourcesSubDir;

    @Parameter(defaultValue = "generated-test-sources/protobuf/java", required = false)
    private String targetTestSourcesSubDir;

    @Parameter(property = "protocVersion", defaultValue = "4.34.0", required = false)
    private String protocVersion;

    @Parameter(property = "protocPath", required = false)
    private String protocPath;

    @Component
    private RepositorySystem repoSystem;

    @Parameter(defaultValue = "${repositorySystemSession}", readonly = true)
    private RepositorySystemSession repoSession;

    @Parameter(defaultValue = "${project.remoteProjectRepositories}", readonly = true)
    private List<RemoteRepository> remoteRepos;

    private void generate(File protoDir, List<File> protoFiles, File outputDirectory) throws MojoExecutionException {
        try {
            File protocFile = resolveProtoc();

            // Run protoc to generate descriptor set
            File descriptorSetFile = File.createTempFile("lightproto-descriptor", ".pb");
            descriptorSetFile.deleteOnExit();

            List<String> command = new ArrayList<>();
            command.add(protocFile.getAbsolutePath());
            command.add("--descriptor_set_out=" + descriptorSetFile.getAbsolutePath());
            command.add("--proto_path=" + protoDir.getAbsolutePath());
            for (File f : protoFiles) {
                command.add(f.getAbsolutePath());
            }

            getLog().info("Running protoc: " + String.join(" ", command));

            ProcessBuilder pb = new ProcessBuilder(command);
            pb.redirectErrorStream(true);
            Process process = pb.start();

            String output;
            try (BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream()))) {
                StringBuilder sb = new StringBuilder();
                String line;
                while ((line = reader.readLine()) != null) {
                    sb.append(line).append("\n");
                }
                output = sb.toString();
            }

            int exitCode = process.waitFor();
            if (exitCode != 0) {
                throw new MojoExecutionException("protoc failed with exit code " + exitCode + ":\n" + output);
            }

            // Parse descriptor set
            FileDescriptorSet descriptorSet;
            try (FileInputStream fis = new FileInputStream(descriptorSetFile)) {
                descriptorSet = FileDescriptorSet.parseFrom(fis);
            }

            // Convert to internal model, only including explicitly requested files
            Set<String> requestedFiles = new HashSet<>();
            for (File f : protoFiles) {
                requestedFiles.add(protoDir.toPath().relativize(f.toPath()).toString());
            }

            List<ProtoFileDescriptor> descriptors = new ArrayList<>();
            List<String> fileNames = new ArrayList<>();
            for (FileDescriptorProto fdp : descriptorSet.getFileList()) {
                if (requestedFiles.contains(fdp.getName())) {
                    descriptors.add(DescriptorConverter.convert(fdp));
                    String name = fdp.getName();
                    int lastSlash = name.lastIndexOf('/');
                    if (lastSlash >= 0) {
                        name = name.substring(lastSlash + 1);
                    }
                    fileNames.add(name);
                }
            }

            LightProtoGenerator.generate(descriptors, outputDirectory, classPrefix, singleOuterClass, fileNames);
        } catch (MojoExecutionException e) {
            throw e;
        } catch (Exception e) {
            getLog().error("Failed to generate lightproto code for " + protoFiles + ": " + e.getMessage(), e);
            throw new MojoExecutionException("Failed to generate lightproto code for " + protoFiles, e);
        }
    }

    private File resolveProtoc() throws MojoExecutionException {
        if (protocPath != null && !protocPath.isEmpty()) {
            File f = new File(protocPath);
            if (!f.exists() || !f.canExecute()) {
                throw new MojoExecutionException(
                        "Specified protocPath does not exist or is not executable: " + protocPath);
            }
            return f;
        }

        String classifier = getOsClassifier();
        getLog().info("Resolving protoc " + protocVersion + " for " + classifier);
        try {
            org.eclipse.aether.artifact.Artifact artifact = new DefaultArtifact(
                    "com.google.protobuf", "protoc", classifier, "exe", protocVersion);
            ArtifactRequest request = new ArtifactRequest(artifact, remoteRepos, null);
            ArtifactResult result = repoSystem.resolveArtifact(repoSession, request);
            File protocFile = result.getArtifact().getFile();
            if (!protocFile.canExecute()) {
                protocFile.setExecutable(true);
            }
            return protocFile;
        } catch (Exception e) {
            throw new MojoExecutionException("Failed to resolve protoc artifact: " + e.getMessage(), e);
        }
    }

    private String getOsClassifier() throws MojoExecutionException {
        String os = System.getProperty("os.name").toLowerCase(Locale.ROOT);
        String arch = System.getProperty("os.arch").toLowerCase(Locale.ROOT);

        String osName;
        if (os.contains("linux")) {
            osName = "linux";
        } else if (os.contains("mac") || os.contains("darwin")) {
            osName = "osx";
        } else if (os.contains("win")) {
            osName = "windows";
        } else {
            throw new MojoExecutionException("Unsupported OS: " + os);
        }

        String archName;
        if (arch.equals("amd64") || arch.equals("x86_64")) {
            archName = "x86_64";
        } else if (arch.equals("aarch64") || arch.equals("arm64")) {
            archName = "aarch_64";
        } else if (arch.contains("ppc64")) {
            archName = "ppcle_64";
        } else if (arch.contains("s390")) {
            archName = "s390_64";
        } else {
            throw new MojoExecutionException("Unsupported architecture: " + arch);
        }

        return osName + "-" + archName;
    }

    public void execute() throws MojoExecutionException {
        File baseDir = project.getBasedir();
        File targetDir = new File(project.getBuild().getDirectory());

        if (sources == null || sources.isEmpty()) {
            File mainProtoDir = new File(baseDir, "src/main/proto");
            File[] mainFilesArray = mainProtoDir.listFiles((dir, name) -> name.endsWith(".proto"));
            if (mainFilesArray != null && mainFilesArray.length > 0) {
                List<File> mainFiles = Arrays.asList(mainFilesArray);
                File generatedSourcesDir = new File(targetDir, targetSourcesSubDir);
                generate(mainProtoDir, mainFiles, generatedSourcesDir);

                project.addCompileSourceRoot(generatedSourcesDir.toString());
            }

            File testProtoDir = new File(baseDir, "src/test/proto");
            File[] testFilesArray = testProtoDir.listFiles((dir, name) -> name.endsWith(".proto"));
            if (testFilesArray != null && testFilesArray.length > 0) {
                List<File> testFiles = Arrays.asList(testFilesArray);
                File generatedTestSourcesDir = new File(targetDir, targetTestSourcesSubDir);
                generate(testProtoDir, testFiles, generatedTestSourcesDir);

                project.addTestCompileSourceRoot(generatedTestSourcesDir.toString());
            }
        } else {
            File protoDir = sources.get(0).getParentFile();
            File generatedSourcesDir = new File(targetDir, targetSourcesSubDir);
            generate(protoDir, sources, generatedSourcesDir);
            project.addCompileSourceRoot(generatedSourcesDir.toString());
        }
    }
}
