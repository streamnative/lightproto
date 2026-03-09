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
package io.github.merlimat.lightproto.gradle;

import com.google.protobuf.DescriptorProtos.FileDescriptorProto;
import com.google.protobuf.DescriptorProtos.FileDescriptorSet;
import io.github.merlimat.lightproto.generator.DescriptorConverter;
import io.github.merlimat.lightproto.generator.LightProtoGenerator;
import io.github.merlimat.lightproto.generator.ProtoFileDescriptor;
import org.gradle.api.DefaultTask;
import org.gradle.api.GradleException;
import org.gradle.api.file.ConfigurableFileCollection;
import org.gradle.api.file.DirectoryProperty;
import org.gradle.api.provider.Property;
import org.gradle.api.tasks.*;
import org.gradle.work.NormalizeLineEndings;

import java.io.*;
import java.nio.file.Path;
import java.util.*;

@CacheableTask
public abstract class GenerateLightProtoTask extends DefaultTask {

    @InputFiles
    @PathSensitive(PathSensitivity.RELATIVE)
    @NormalizeLineEndings
    public abstract ConfigurableFileCollection getProtoFiles();

    @Input
    public abstract Property<String> getClassPrefix();

    @Input
    public abstract Property<Boolean> getSingleOuterClass();

    @Input
    public abstract Property<String> getProtocVersion();

    @Internal
    public abstract Property<String> getProtocPath();

    @OutputDirectory
    public abstract DirectoryProperty getOutputDirectory();

    @Classpath
    public abstract ConfigurableFileCollection getProtocDependency();

    @TaskAction
    public void generate() {
        Set<File> protoFiles = getProtoFiles().getFiles();
        if (protoFiles.isEmpty()) {
            getLogger().info("No proto files found, skipping code generation");
            return;
        }

        File protocFile = resolveProtoc();
        File protoDir = findCommonParentDir(protoFiles);
        File outputDir = getOutputDirectory().get().getAsFile();

        try {
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

            getLogger().info("Running protoc: {}", String.join(" ", command));

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
                throw new GradleException("protoc failed with exit code " + exitCode + ":\n" + output);
            }

            // Parse descriptor set
            FileDescriptorSet descriptorSet;
            try (FileInputStream fis = new FileInputStream(descriptorSetFile)) {
                descriptorSet = FileDescriptorSet.parseFrom(fis);
            }

            // Convert to internal model, only including explicitly requested files
            Set<String> requestedFiles = new HashSet<>();
            Path protoDirPath = protoDir.toPath();
            for (File f : protoFiles) {
                requestedFiles.add(protoDirPath.relativize(f.toPath()).toString());
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

            LightProtoGenerator.generate(descriptors, outputDir, getClassPrefix().get(),
                    getSingleOuterClass().get(), fileNames);
        } catch (GradleException e) {
            throw e;
        } catch (Exception e) {
            throw new GradleException("Failed to generate lightproto code: " + e.getMessage(), e);
        }
    }

    private File resolveProtoc() {
        String protocPath = getProtocPath().getOrNull();
        if (protocPath != null && !protocPath.isEmpty()) {
            File f = new File(protocPath);
            if (!f.exists() || !f.canExecute()) {
                throw new GradleException(
                        "Specified protocPath does not exist or is not executable: " + protocPath);
            }
            return f;
        }

        Set<File> files = getProtocDependency().getFiles();
        if (files.isEmpty()) {
            throw new GradleException("Failed to resolve protoc binary");
        }
        File protocFile = files.iterator().next();
        if (!protocFile.canExecute()) {
            protocFile.setExecutable(true);
        }
        return protocFile;
    }

    private File findCommonParentDir(Set<File> files) {
        File parent = null;
        for (File f : files) {
            if (parent == null) {
                parent = f.getParentFile();
            } else {
                // Find common ancestor
                Path parentPath = parent.toPath();
                Path filePath = f.getParentFile().toPath();
                while (!filePath.startsWith(parentPath)) {
                    parentPath = parentPath.getParent();
                    if (parentPath == null) {
                        throw new GradleException("Proto files do not share a common parent directory");
                    }
                }
                parent = parentPath.toFile();
            }
        }
        return parent;
    }
}
