package io.joynr.generator;

/*
 * #%L
 * %%
 * Copyright (C) 2011 - 2015 BMW Car IT GmbH
 * %%
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 * 
 *      http://www.apache.org/licenses/LICENSE-2.0
 * 
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 * #L%
 */

import io.joynr.generator.util.InvocationArguments;

import java.util.Set;

import org.apache.maven.plugin.logging.Log;
import org.eclipse.xtext.generator.IGenerator;

public class GeneratorTask {

    InvocationArguments arguments;
    private Executor executor;

    public GeneratorTask(InvocationArguments arguments) {
        this.arguments = arguments;
        this.executor = new Executor(arguments);
    }

    public void generate(Log log) throws ClassNotFoundException, InstantiationException, IllegalAccessException {
        executor.generate(prepareGenerator());
    }

    public void printHelp(Log log) throws ClassNotFoundException, InstantiationException, IllegalAccessException {
        IGenerator generator = prepareGenerator();
        if (generator instanceof IJoynrGenerator) {
            IJoynrGenerator joynrGenerator = (IJoynrGenerator) generator;
            Set<String> parameters = joynrGenerator.supportedParameters();
            StringBuffer sb = new StringBuffer();
            sb.append("Supported configuration parameters by the generator: ");
            if (parameters != null && parameters.size() > 0) {
                for (String parameter : parameters) {
                    sb.append(parameter + ",");
                }
                sb.deleteCharAt(sb.length() - 1);
            } else {
                sb.append("none");
            }
            log.info(sb.toString());
        } else {
            log.info("no additional information available for the provider generator");
        }
    }

    public IGenerator prepareGenerator() throws ClassNotFoundException, InstantiationException, IllegalAccessException {
        if (arguments.isValid()) {
            return executor.setup();
        } else {
            throw new IllegalArgumentException(arguments.getErrorMessage());
        }

    }
}
