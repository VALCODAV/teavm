/*
 *  Copyright 2015 Alexey Andreev.
 *
 *  Licensed under the Apache License, Version 2.0 (the "License");
 *  you may not use this file except in compliance with the License.
 *  You may obtain a copy of the License at
 *
 *       http://www.apache.org/licenses/LICENSE-2.0
 *
 *  Unless required by applicable law or agreed to in writing, software
 *  distributed under the License is distributed on an "AS IS" BASIS,
 *  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *  See the License for the specific language governing permissions and
 *  limitations under the License.
 */
package org.teavm.platform.plugin;

import java.io.IOException;
import org.teavm.codegen.SourceWriter;
import org.teavm.platform.metadata.ClassResource;

/**
 *
 * @author Alexey Andreev
 */
class BuildTimeClassResource implements ClassResource, ResourceWriter {
    private String className;

    public BuildTimeClassResource(String className) {
        this.className = className;
    }

    public String getClassName() {
        return className;
    }

    @Override
    public void write(SourceWriter writer) throws IOException {
        writer.appendClass(className);
    }
}