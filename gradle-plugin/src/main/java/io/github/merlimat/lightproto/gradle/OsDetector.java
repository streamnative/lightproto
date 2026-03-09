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

import java.util.Locale;

public class OsDetector {

    public static String getOsClassifier() {
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
            throw new IllegalStateException("Unsupported OS: " + os);
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
            throw new IllegalStateException("Unsupported architecture: " + arch);
        }

        return osName + "-" + archName;
    }
}
