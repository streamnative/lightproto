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

import org.gradle.api.Plugin;
import org.gradle.api.Project;
import org.gradle.api.artifacts.Configuration;
import org.gradle.api.plugins.JavaPlugin;
import org.gradle.api.tasks.SourceSet;
import org.gradle.api.tasks.SourceSetContainer;
import org.gradle.api.tasks.TaskProvider;

public class LightProtoPlugin implements Plugin<Project> {

    @Override
    public void apply(Project project) {
        project.getPluginManager().apply(JavaPlugin.class);

        LightProtoExtension extension = project.getExtensions()
                .create("lightproto", LightProtoExtension.class);

        extension.getClassPrefix().convention("");
        extension.getSingleOuterClass().convention(false);
        extension.getProtocVersion().convention("4.34.0");

        registerTaskForSourceSet(project, extension, "main");
        registerTaskForSourceSet(project, extension, "test");
    }

    private void registerTaskForSourceSet(Project project, LightProtoExtension extension,
                                          String sourceSetName) {
        boolean isTest = "test".equals(sourceSetName);
        String taskName = isTest ? "generateTestLightProto" : "generateLightProto";
        String protoDir = isTest ? "src/test/proto" : "src/main/proto";
        String outputSubDir = "generated/sources/lightproto/" + sourceSetName + "/java";

        TaskProvider<GenerateLightProtoTask> taskProvider =
                project.getTasks().register(taskName, GenerateLightProtoTask.class, task -> {
                    task.getClassPrefix().set(extension.getClassPrefix());
                    task.getSingleOuterClass().set(extension.getSingleOuterClass());
                    task.getProtocVersion().set(extension.getProtocVersion());
                    task.getProtocPath().set(extension.getProtocPath());

                    task.getProtoFiles().from(
                            project.fileTree(protoDir, tree -> tree.include("**/*.proto"))
                    );

                    task.getOutputDirectory().set(
                            project.getLayout().getBuildDirectory().dir(outputSubDir)
                    );

                    // Create a detached configuration for protoc resolution
                    task.getProtocDependency().from(project.provider(() -> {
                        String protocPath = extension.getProtocPath().getOrNull();
                        if (protocPath != null && !protocPath.isEmpty()) {
                            return project.files();
                        }

                        String version = extension.getProtocVersion().get();
                        String classifier = OsDetector.getOsClassifier();

                        Configuration detached = project.getConfigurations().detachedConfiguration(
                                project.getDependencies().create(
                                        "com.google.protobuf:protoc:" + version + ":" + classifier + "@exe"
                                )
                        );
                        detached.setTransitive(false);
                        return detached;
                    }));

                    task.setDescription("Generate LightProto Java sources from " + sourceSetName + " .proto files");
                    task.setGroup("lightproto");

                    task.onlyIf(t -> !((GenerateLightProtoTask) t).getProtoFiles().isEmpty());
                });

        // Wire into source sets and compilation
        project.getPlugins().withType(JavaPlugin.class, javaPlugin -> {
            SourceSetContainer sourceSets = project.getExtensions().getByType(SourceSetContainer.class);
            SourceSet sourceSet = sourceSets.getByName(sourceSetName);

            sourceSet.getJava().srcDir(
                    taskProvider.flatMap(GenerateLightProtoTask::getOutputDirectory)
            );

            String compileTaskName = isTest ? "compileTestJava" : "compileJava";
            project.getTasks().named(compileTaskName, task -> task.dependsOn(taskProvider));
        });
    }
}
